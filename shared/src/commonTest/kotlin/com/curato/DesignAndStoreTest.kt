package com.curato

import com.curato.data.local.applyRecent
import com.curato.data.local.decodeContact
import com.curato.data.local.decodeRecent
import com.curato.data.local.toStored
import com.curato.data.mapper.toDomain
import com.curato.data.remote.dto.FeaturedDto
import com.curato.domain.model.FeaturedSource
import com.curato.fakes.contact
import com.curato.fakes.recent
import com.curato.presentation.enquiry.EnquiryDraft
import com.curato.presentation.enquiry.decodeDraft
import com.curato.presentation.enquiry.encode
import com.curato.ui.designsystem.formatAcceptedAt
import com.curato.ui.designsystem.formatPrice
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shelf heading's discriminator (SPEC.md §4.1). */
class FeaturedSourceTest {

    @Test
    fun computed_maps_to_COMPUTED() {
        assertEquals(FeaturedSource.COMPUTED, FeaturedDto("computed", emptyList()).toDomain().source)
    }

    @Test
    fun curated_maps_to_CURATED() {
        assertEquals(FeaturedSource.CURATED, FeaturedDto("curated", emptyList()).toDomain().source)
    }

    @Test
    fun an_unrecognised_source_falls_back_to_computed() {
        // "Top rated" describes a ranking and is the backend's own fallback; defaulting
        // to "Featured" would claim an editorial choice nobody made.
        assertEquals(FeaturedSource.COMPUTED, FeaturedDto("something-new", emptyList()).toDomain().source)
    }

    @Test
    fun matching_is_case_insensitive() {
        assertEquals(FeaturedSource.CURATED, FeaturedDto("CURATED", emptyList()).toDomain().source)
    }
}

/** Recently viewed: cap, LRU and de-duplication (SPEC.md §9.1). */
class RecentlyViewedRulesTest {

    @Test
    fun a_new_product_goes_to_the_front() {
        val result = applyRecent(listOf(recent(1), recent(2)), recent(3))
        assertEquals(listOf(3, 1, 2), result.map { it.id })
    }

    @Test
    fun re_viewing_moves_the_existing_entry_rather_than_duplicating() {
        val result = applyRecent(listOf(recent(1), recent(2), recent(3)), recent(3))
        assertEquals(listOf(3, 1, 2), result.map { it.id })
        assertEquals(3, result.size)
    }

    @Test
    fun the_list_is_capped_and_the_oldest_falls_off() {
        val existing = (1..10).map { recent(it) }
        val result = applyRecent(existing, recent(99))
        assertEquals(10, result.size)
        assertEquals(99, result.first().id)
        // id 10 was the oldest in the seeded order, so it is the one evicted.
        assertEquals(false, result.any { it.id == 10 })
    }

    @Test
    fun capping_respects_a_smaller_cap() {
        assertEquals(2, applyRecent(listOf(recent(1), recent(2)), recent(3), cap = 2).size)
    }
}

/** A corrupt store degrades a row, never a screen. */
class RecentlyViewedDecodeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun absent_data_reads_as_empty() {
        assertEquals(emptyList(), decodeRecent(null, json))
        assertEquals(emptyList(), decodeRecent("", json))
        assertEquals(emptyList(), decodeRecent("   ", json))
    }

    @Test
    fun unparseable_data_reads_as_empty_instead_of_throwing() {
        assertEquals(emptyList(), decodeRecent("{not json", json))
        assertEquals(emptyList(), decodeRecent("""{"unexpected":"shape"}""", json))
    }

    @Test
    fun well_formed_data_round_trips() {
        val encoded = """[{"id":7,"title":"T","imageUrl":"u","price":1.5,"currency":"USD","viewedAt":9}]"""
        val decoded = decodeRecent(encoded, json)
        assertEquals(1, decoded.size)
        assertEquals(7, decoded.first().id)
        assertEquals("USD", decoded.first().currency)
    }
}

/** Prices are formatted from the response, never the locale (SPEC.md §10.4). */
class FormatPriceTest {

    @Test
    fun usd_renders_with_a_dollar_sign_regardless_of_device_locale() {
        assertEquals("$109.95", formatPrice(109.95, "USD"))
    }

    @Test
    fun always_two_decimal_places() {
        assertEquals("$15.00", formatPrice(15.0, "USD"))
        assertEquals("$15.50", formatPrice(15.5, "USD"))
        assertEquals("$0.99", formatPrice(0.99, "USD"))
    }

    @Test
    fun rounds_on_the_minor_unit() {
        assertEquals("$1.00", formatPrice(0.995, "USD"))
        assertEquals("$2.35", formatPrice(2.345, "USD"))
    }

    @Test
    fun an_unknown_currency_prints_its_code_rather_than_guessing_a_glyph() {
        assertEquals("SEK 55.99", formatPrice(55.99, "SEK"))
    }

    @Test
    fun the_currency_drives_the_symbol_not_the_platform() {
        assertEquals("₹55.99", formatPrice(55.99, "INR"))
        assertEquals("€55.99", formatPrice(55.99, "EUR"))
    }
}

/**
 * The saved-contact store's decode (SPEC.md §9.2).
 *
 * `null` is a state this screen already handles — it is the first-enquiry case — so a
 * store written by an older build, or half-written when the process died, degrades to it
 * rather than throwing into a Flow the UI is collecting.
 */
class SavedContactDecodeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun absent_data_reads_as_nothing_saved() {
        assertNull(decodeContact(null, json))
        assertNull(decodeContact("", json))
        assertNull(decodeContact("   ", json))
    }

    @Test
    fun unparseable_data_reads_as_nothing_saved_instead_of_throwing() {
        assertNull(decodeContact("{not json", json))
        assertNull(decodeContact("""{"unexpected":"shape"}""", json))
        // A record missing a required field is as unusable as one that is not JSON.
        assertNull(decodeContact("""{"name":"Ada"}""", json))
    }

    @Test
    fun a_record_without_a_phone_number_round_trips() {
        val decoded = decodeContact("""{"name":"Ada","email":"ada@example.com"}""", json)
        assertEquals("Ada", decoded?.name)
        assertEquals("ada@example.com", decoded?.email)
        // Optional and absent, not an empty string.
        assertNull(decoded?.phone)
    }

    @Test
    fun a_full_record_round_trips() {
        val encoded = json.encodeToString(contact().toStored())
        assertEquals(contact(), decodeContact(encoded, json))
    }

    /**
     * The field list is the point of this one.
     *
     * §9.2 says the message is "never stored", and the strongest form of that guarantee is
     * that the stored shape has nowhere to put one. If a future change added a field here,
     * this test is what would notice.
     */
    @Test
    fun the_stored_shape_holds_only_the_three_reusable_fields() {
        val encoded = json.encodeToString(contact().toStored())
        assertEquals(
            """{"name":"Ada Lovelace","email":"ada@example.com","phone":"+91 9876543210"}""",
            encoded,
        )
    }
}

/**
 * The enquiry draft's own encoding (SPEC.md §7.6).
 *
 * It is one serialized value under one `SavedStateHandle` key, which makes the handle the
 * single source of truth — so the encoding is load-bearing rather than incidental.
 */
class EnquiryDraftTest {

    @Test
    fun a_draft_round_trips_through_its_encoding() {
        val draft = EnquiryDraft(
            name = "Ada",
            email = "ada@example.com",
            message = "half a thought",
            consent = false,
            editing = true,
        )
        assertEquals(draft, decodeDraft(draft.encode()))
    }

    @Test
    fun absent_or_corrupt_data_restores_a_first_enquiry() {
        assertEquals(EnquiryDraft(), decodeDraft(null))
        assertEquals(EnquiryDraft(), decodeDraft(""))
        assertEquals(EnquiryDraft(), decodeDraft("{not json"))
    }

    /** §7.6's "default on and visible" has to be the default of the data, not the UI. */
    @Test
    fun a_fresh_draft_consents_by_default() {
        assertTrue(EnquiryDraft().consent)
        assertTrue(decodeDraft(null).consent)
    }

    /**
     * Success clears the values and keeps the completion state.
     *
     * Clearing the whole draft would destroy `submitted` at the exact moment it starts
     * mattering, and a restored screen would then offer a filled form with a live Submit
     * for an enquiry already sent (AC-20).
     */
    @Test
    fun completion_clears_every_value_and_keeps_the_confirmation() {
        val completed = EnquiryDraft(
            name = "Ada",
            email = "ada@example.com",
            phone = "+91 9876543210",
            message = "Is this in black?",
            editing = true,
            prefilled = true,
        ).completed(
            acceptedAt = "2026-11-05T10:15:30.000Z",
            sentToEmail = "ada@example.com",
            productTitle = "Mens Cotton Jacket",
        )

        assertEquals("", completed.name)
        assertEquals("", completed.email)
        assertEquals("", completed.phone)
        assertEquals("", completed.message)
        assertTrue(completed.submitted)
        assertEquals("2026-11-05T10:15:30.000Z", completed.acceptedAt)
        assertEquals("ada@example.com", completed.sentToEmail)
        assertEquals("Mens Cotton Jacket", completed.productTitle)
    }

    @Test
    fun completion_survives_the_encoding() {
        val completed = EnquiryDraft(name = "Ada")
            .completed("2026-11-05T10:15:30.000Z", "ada@example.com", "Jacket")
        val restored = decodeDraft(completed.encode())

        assertTrue(restored.submitted)
        assertEquals("ada@example.com", restored.sentToEmail)
    }
}

/**
 * The thank-you stamp (SPEC.md §4.6, the kit's `.a-thanks .stamp`).
 *
 * The time zone is passed in rather than read from the machine, so this asserts one known
 * output instead of whatever the test runner happens to be set to.
 */
class AcceptedStampTest {

    private val utc = TimeZone.UTC

    @Test
    fun a_server_instant_renders_in_the_kit_s_shape() {
        assertEquals("5 Nov 2026, 10:15", formatAcceptedAt("2026-11-05T10:15:30.000Z", utc))
    }

    @Test
    fun the_day_is_unpadded_and_the_time_is_padded() {
        assertEquals("3 Nov 2026, 09:05", formatAcceptedAt("2026-11-03T09:05:00.000Z", utc))
    }

    /**
     * The conversion is the whole reason this needs a time zone database.
     *
     * The backend sends UTC; a customer in IST reads a clock five and a half hours ahead,
     * and a stamp showing the server's hour would be wrong rather than merely terse.
     */
    @Test
    fun the_instant_is_converted_to_the_reader_s_zone() {
        assertEquals(
            "5 Nov 2026, 15:45",
            formatAcceptedAt("2026-11-05T10:15:30.000Z", TimeZone.of("Asia/Kolkata")),
        )
        // And across a date boundary, which is where an offset is most visible.
        assertEquals(
            "6 Nov 2026, 07:15",
            formatAcceptedAt("2026-11-05T22:15:00.000Z", TimeZone.of("Asia/Tokyo")),
        )
    }

    /**
     * An unreadable timestamp costs the stamp, never the confirmation.
     *
     * The enquiry has already been accepted by the time this runs. A throw here would turn
     * a cosmetic problem into a screen that stopped saying the thing it exists to say.
     */
    @Test
    fun an_unusable_value_yields_no_stamp_rather_than_an_error() {
        assertNull(formatAcceptedAt(null, utc))
        assertNull(formatAcceptedAt("", utc))
        assertNull(formatAcceptedAt("   ", utc))
        assertNull(formatAcceptedAt("not a date", utc))
        assertNull(formatAcceptedAt("2026-13-45T99:99:99Z", utc))
    }

    /** A timestamp without milliseconds is still a valid instant. */
    @Test
    fun seconds_precision_is_accepted() {
        assertEquals("5 Nov 2026, 10:15", formatAcceptedAt("2026-11-05T10:15:30Z", utc))
    }
}
