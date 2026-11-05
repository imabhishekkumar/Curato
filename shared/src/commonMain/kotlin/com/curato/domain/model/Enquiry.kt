package com.curato.domain.model

/**
 * The enquiry domain (SPEC.md §3, SPEC.md §7.6).
 *
 * Three types, deliberately distinct: what the customer has typed so far, what is sent,
 * and what came back. Collapsing the first two is how a half-filled form ends up being
 * the thing that gets submitted.
 */

/**
 * The form as it currently stands — raw, untrimmed, possibly incomplete.
 *
 * Everything is a non-null `String` because a text field cannot hold an absence; "the
 * customer has not typed a phone number" and "the customer typed only spaces" are the
 * same thing to them, and both become an absent field at the boundary. [toEnquiry] is
 * the only place that conversion happens.
 */
data class EnquiryForm(
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val message: String = "",
) {
    /**
     * The request, with the §6 normalisation applied once.
     *
     * Trimmed, and blank optionals dropped rather than sent as empty strings — the
     * backend's own `blankToUndefined` rule, mirrored here so the two agree about what
     * "absent" means instead of the server having to interpret `""`.
     */
    fun toEnquiry(productId: Int, seenPrice: Double?) = Enquiry(
        productId = productId,
        seenPrice = seenPrice,
        contact = Contact(
            name = name.trim(),
            email = email.trim(),
            phone = phone.trim().ifBlank { null },
        ),
        message = message.trim().ifBlank { null },
    )
}

/** The reusable half (SPEC.md §9.2). The message is never part of this. */
data class Contact(
    val name: String,
    val email: String,
    val phone: String? = null,
)

/**
 * One submission (SPEC.md §3).
 *
 * [seenPrice] is **untrusted by contract** — the price that was on screen, carried so the
 * email can note drift against the price the backend resolves. It is never authoritative
 * and the backend never stores or echoes it, which is why the client passes along
 * whatever the route held rather than re-reading anything.
 *
 * There is deliberately no title, price or category here. An earlier draft of the API
 * accepted those from the client while validating only the id, which let any caller
 * submit a real product with an invented price and have the backend email it to sales as
 * fact.
 */
data class Enquiry(
    val productId: Int,
    val seenPrice: Double? = null,
    val contact: Contact,
    val message: String? = null,
)

/**
 * The answer to a successful submission (SPEC.md §4.6).
 *
 * [acceptedAt] stays the server's raw ISO-8601 instant rather than a parsed type. Two
 * reasons: an enquiry that SMTP has already accepted must not become a failure because a
 * timestamp would not parse, and this is the string the draft carries across process
 * death, where a primitive is what `SavedStateHandle` holds well.
 *
 * No reference id — nothing is persisted server-side, so an id the customer could not
 * look up would be theatre.
 */
data class EnquiryAccepted(val acceptedAt: String)
