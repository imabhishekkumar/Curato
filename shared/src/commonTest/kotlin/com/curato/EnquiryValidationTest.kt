package com.curato

import com.curato.domain.model.EnquiryForm
import com.curato.domain.validation.EMAIL_MESSAGE
import com.curato.domain.validation.FIELD_EMAIL
import com.curato.domain.validation.FIELD_MESSAGE
import com.curato.domain.validation.FIELD_NAME
import com.curato.domain.validation.FIELD_PHONE
import com.curato.domain.validation.MESSAGE_TOO_LONG_MESSAGE
import com.curato.domain.validation.NAME_MESSAGE
import com.curato.domain.validation.PHONE_MESSAGE
import com.curato.domain.validation.validate
import com.curato.domain.validation.validateField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client's validation mirror (SPEC.md §6).
 *
 * Boundaries get their own cases because off-by-one is the whole failure mode here: a
 * name rule of `> 2` rather than `>= 2` rejects "Jo", and nothing about the screen would
 * look wrong while it did.
 */
class EnquiryValidationTest {

    private fun form(
        name: String = "Ada Lovelace",
        email: String = "ada@example.com",
        phone: String = "",
        message: String = "",
    ) = EnquiryForm(name = name, email = email, phone = phone, message = message)

    // ── name: trimmed, 2–80 ──

    @Test
    fun a_name_needs_two_characters() {
        assertEquals(NAME_MESSAGE, validateField(FIELD_NAME, form(name = "")))
        assertEquals(NAME_MESSAGE, validateField(FIELD_NAME, form(name = "J")))
        assertNull(validateField(FIELD_NAME, form(name = "Jo")))
    }

    @Test
    fun a_name_is_capped_at_eighty() {
        assertNull(validateField(FIELD_NAME, form(name = "a".repeat(80))))
        assertEquals(NAME_MESSAGE, validateField(FIELD_NAME, form(name = "a".repeat(81))))
    }

    /** The length rule is applied after trimming, as the backend does. */
    @Test
    fun a_name_of_only_whitespace_is_not_a_name() {
        assertEquals(NAME_MESSAGE, validateField(FIELD_NAME, form(name = "    ")))
        assertNull(validateField(FIELD_NAME, form(name = "  Jo  ")))
    }

    // ── email: permissive on purpose ──

    /**
     * The addresses the mirror must **not** reject.
     *
     * This is the direction that matters: a client stricter than Zod refuses a submission
     * the backend would have accepted, and the customer has no way around it. Every one of
     * these is a real-world address shape.
     */
    @Test
    fun plausible_addresses_are_accepted() {
        listOf(
            "ada@example.com",
            "a+tag@sub.example.co.uk",
            "first.last@example.org",
            "a@b.co",
            "ADA@EXAMPLE.COM",
            "ada_lovelace@example-host.com",
            "ada'quote@example.com",
        ).forEach { address ->
            assertNull(validateField(FIELD_EMAIL, form(email = address)), address)
        }
    }

    /** Only what is unambiguously not an address. */
    @Test
    fun clearly_malformed_addresses_are_rejected() {
        listOf(
            "",
            "ada",
            "ada@example",
            "ada@@example.com",
            "@example.com",
            "ada@",
            "ada lovelace@example.com",
            "ada@exam ple.com",
            "ada@.com",
            "ada@example.",
        ).forEach { address ->
            assertEquals(EMAIL_MESSAGE, validateField(FIELD_EMAIL, form(email = address)), address)
        }
    }

    @Test
    fun an_address_is_capped_at_254_characters() {
        val local = "a".repeat(254 - "@example.com".length)
        assertNull(validateField(FIELD_EMAIL, form(email = "$local@example.com")))
        assertEquals(
            EMAIL_MESSAGE,
            validateField(FIELD_EMAIL, form(email = "a$local@example.com")),
        )
    }

    // ── phone: optional, and blank means absent ──

    /**
     * The case that would be most tempting to get wrong.
     *
     * Phone is optional, so an empty field is an **absence** rather than an invalid value
     * — the backend's own `blankToUndefined`. Treating it as invalid would block every
     * first enquiry from anyone who declines to give a number.
     */
    @Test
    fun an_empty_phone_is_absent_rather_than_invalid() {
        assertNull(validateField(FIELD_PHONE, form(phone = "")))
        assertNull(validateField(FIELD_PHONE, form(phone = "   ")))
    }

    @Test
    fun a_phone_number_is_seven_to_twenty_characters() {
        assertEquals(PHONE_MESSAGE, validateField(FIELD_PHONE, form(phone = "123456")))
        assertNull(validateField(FIELD_PHONE, form(phone = "1234567")))
        assertNull(validateField(FIELD_PHONE, form(phone = "1".repeat(20))))
        assertEquals(PHONE_MESSAGE, validateField(FIELD_PHONE, form(phone = "1".repeat(21))))
    }

    /** §6's own two examples, which must both pass. */
    @Test
    fun the_spec_examples_are_accepted() {
        assertNull(validateField(FIELD_PHONE, form(phone = "+91 9876543210")))
        assertNull(validateField(FIELD_PHONE, form(phone = "(020) 7946 0991")))
    }

    @Test
    fun a_phone_number_allows_only_digits_and_punctuation() {
        assertNull(validateField(FIELD_PHONE, form(phone = "+1 (555) 010-1234")))
        assertEquals(PHONE_MESSAGE, validateField(FIELD_PHONE, form(phone = "555-CALL-NOW")))
        assertEquals(PHONE_MESSAGE, validateField(FIELD_PHONE, form(phone = "+44 20 ext.5")))
    }

    // ── message: optional, ≤1000 ──

    @Test
    fun a_message_is_optional_and_capped_at_a_thousand() {
        assertNull(validateField(FIELD_MESSAGE, form(message = "")))
        assertNull(validateField(FIELD_MESSAGE, form(message = "a".repeat(1000))))
        assertEquals(
            MESSAGE_TOO_LONG_MESSAGE,
            validateField(FIELD_MESSAGE, form(message = "a".repeat(1001))),
        )
    }

    /** Trailing whitespace must not push a message over the cap it is within. */
    @Test
    fun the_message_cap_is_applied_after_trimming() {
        assertNull(validateField(FIELD_MESSAGE, form(message = "a".repeat(1000) + "   ")))
    }

    // ── the whole form ──

    @Test
    fun a_complete_form_has_no_errors() {
        assertTrue(validate(form()).isEmpty())
    }

    /**
     * Every bad field reports, not just the first.
     *
     * AC-14 requires each message under its own input, so a validator that stopped at the
     * first problem would make the customer fix the form one round trip at a time.
     */
    @Test
    fun every_invalid_field_reports_at_once() {
        val errors = validate(
            EnquiryForm(
                name = "J",
                email = "ada@example",
                phone = "+44 20",
                message = "a".repeat(1001),
            ),
        )
        assertEquals(
            mapOf(
                FIELD_NAME to NAME_MESSAGE,
                FIELD_EMAIL to EMAIL_MESSAGE,
                FIELD_PHONE to PHONE_MESSAGE,
                FIELD_MESSAGE to MESSAGE_TOO_LONG_MESSAGE,
            ),
            errors,
        )
    }

    /**
     * The keys are the backend's dotted request paths.
     *
     * If these drifted to local names, a 422's `fieldErrors` and the client's own errors
     * would land in the same map under different keys — and the screen would show the
     * server's message beside the client's for the same input.
     */
    @Test
    fun errors_are_keyed_by_the_dotted_request_path() {
        val errors = validate(EnquiryForm(name = "", email = ""))
        assertTrue(errors.containsKey("contact.name"))
        assertTrue(errors.containsKey("contact.email"))
    }
}

/**
 * The §6 normalisation, done once on the way out.
 *
 * `EnquiryForm` holds what is on screen; `Enquiry` is what is sent. The gap between them
 * is trimming and blank-to-absent, and both are mechanical enough that a second
 * implementation elsewhere would silently diverge.
 */
class EnquiryFormConversionTest {

    @Test
    fun values_are_trimmed_on_the_way_out() {
        val enquiry = EnquiryForm(
            name = "  Ada Lovelace  ",
            email = "  ada@example.com ",
            phone = " +91 9876543210 ",
            message = "  Is this in black?  ",
        ).toEnquiry(productId = 1, seenPrice = 55.99)

        assertEquals("Ada Lovelace", enquiry.contact.name)
        assertEquals("ada@example.com", enquiry.contact.email)
        assertEquals("+91 9876543210", enquiry.contact.phone)
        assertEquals("Is this in black?", enquiry.message)
    }

    /**
     * Blank optionals become **absent**, not empty strings.
     *
     * The DTO defaults them to null and the client's `Json` omits nulls, so this is what
     * decides whether the key appears in the body at all — and §6 only promises to ignore
     * an *absent* `seenPrice`, not an empty one.
     */
    @Test
    fun blank_optionals_become_null_rather_than_empty_strings() {
        val enquiry = EnquiryForm(name = "Ada", email = "a@b.co", phone = "  ", message = "")
            .toEnquiry(productId = 1, seenPrice = null)

        assertNull(enquiry.contact.phone)
        assertNull(enquiry.message)
        assertNull(enquiry.seenPrice)
    }

    @Test
    fun the_product_id_and_seen_price_ride_through_untouched() {
        val enquiry = EnquiryForm(name = "Ada", email = "a@b.co")
            .toEnquiry(productId = 17, seenPrice = 109.95)

        assertEquals(17, enquiry.productId)
        // Not rounded on the way to a request a reviewer will read in an email.
        assertEquals(109.95, enquiry.seenPrice)
    }
}
