package com.curato

import com.curato.data.local.applyRecent
import com.curato.data.local.decodeRecent
import com.curato.data.mapper.toDomain
import com.curato.data.remote.dto.FeaturedDto
import com.curato.domain.model.FeaturedSource
import com.curato.fakes.recent
import com.curato.ui.designsystem.formatPrice
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

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
