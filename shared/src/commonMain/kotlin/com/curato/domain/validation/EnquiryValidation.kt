package com.curato.domain.validation

import com.curato.domain.model.EnquiryForm

/**
 * The client half of SPEC.md §6, mirrored for immediate feedback.
 *
 * **The server is authoritative and this is a mirror, not a gate.** That asymmetry is
 * load-bearing: a client rule *stricter* than the backend's rejects a submission the
 * backend would have accepted, and the customer has no way to argue with it. So every
 * check here is at most as strict as the server's, and the email check is deliberately
 * looser — see [isPlausibleEmail].
 *
 * The messages are the backend's strings verbatim, held once so the two cannot drift, the
 * same reason `MIN_QUERY_MESSAGE` exists. `EnvelopeMappingTest` asserts them against what
 * a real 422 carries.
 */

const val NAME_MESSAGE = "Enter your name"
const val EMAIL_MESSAGE = "Enter a valid email address"
const val PHONE_MESSAGE = "Enter a valid phone number"
const val MESSAGE_TOO_LONG_MESSAGE = "Message is too long"

/**
 * The banner above the form when validation fails, and the backend's `VALIDATION_FAILED`
 * `userMessage` verbatim.
 *
 * Needed as a constant because the client half can fail *without a round trip*, so there
 * is no envelope to read the string from — and the two must not word the same condition
 * differently depending on which side caught it.
 */
const val VALIDATION_BANNER_MESSAGE = "Please check the highlighted fields."

/**
 * The keys are the backend's **dotted request paths**, not local field names.
 *
 * `fieldErrors` arrives keyed this way (SPEC.md §5), so client-found and server-found
 * errors land in one map addressed one way, and the screen places either under the input
 * it names without a translation layer to get wrong.
 */
const val FIELD_NAME = "contact.name"
const val FIELD_EMAIL = "contact.email"
const val FIELD_PHONE = "contact.phone"
const val FIELD_MESSAGE = "message"

/** Every field the form can produce an error for, in the order they are drawn. */
val EnquiryFields = listOf(FIELD_NAME, FIELD_EMAIL, FIELD_PHONE, FIELD_MESSAGE)

private const val NAME_MIN = 2
private const val NAME_MAX = 80
private const val EMAIL_MAX = 254
private const val PHONE_MIN = 7
private const val PHONE_MAX = 20

/** The cap the backend applies to `message`, and the counter the field shows near it. */
const val MESSAGE_MAX_LENGTH = 1000

/** The backend's `PHONE_SHAPE`: digits and `+ - ( ) space`, nothing else. */
private val PhoneAllowed = "+-() ".toSet()

/**
 * Every problem the form has, keyed by request path. Empty means submittable.
 *
 * Optional fields that are blank are **absent, not invalid** — the backend's own
 * `blankToUndefined`, so an empty phone field is never an error.
 */
fun validate(form: EnquiryForm): Map<String, String> =
    EnquiryFields.mapNotNull { field ->
        validateField(field, form)?.let { field to it }
    }.toMap()

/**
 * One field, for §6's "on blur" half.
 *
 * Validation runs on blur and on submit, never per keystroke — mid-typing errors tell a
 * customer their half-typed email is wrong, which they already know.
 */
fun validateField(field: String, form: EnquiryForm): String? = when (field) {
    FIELD_NAME -> NAME_MESSAGE.takeIf { form.name.trim().length !in NAME_MIN..NAME_MAX }

    FIELD_EMAIL -> {
        val email = form.email.trim()
        EMAIL_MESSAGE.takeIf { email.length > EMAIL_MAX || !isPlausibleEmail(email) }
    }

    FIELD_PHONE -> {
        val phone = form.phone.trim()
        PHONE_MESSAGE.takeIf {
            phone.isNotEmpty() &&
                (phone.length !in PHONE_MIN..PHONE_MAX || !phone.all(::isPhoneCharacter))
        }
    }

    FIELD_MESSAGE ->
        MESSAGE_TOO_LONG_MESSAGE.takeIf { form.message.trim().length > MESSAGE_MAX_LENGTH }

    else -> null
}

private fun isPhoneCharacter(character: Char) =
    character.isDigit() || character in PhoneAllowed

/**
 * Deliberately permissive, and the one rule that is *not* a faithful mirror.
 *
 * The backend validates with Zod's `z.email()`, whose exact grammar no hand-rolled check
 * will reproduce. Of the two ways to be wrong, being stricter than the server is the
 * harmful one: it refuses to send an address the backend would have accepted, with no
 * recourse. Being looser merely means a bad address makes one round trip and comes back
 * as a 422 the screen already knows how to render against the same input.
 *
 * So this catches only what is unambiguously not an address — nothing typed, no `@`, more
 * than one, an empty local part or domain, a domain with no dot, or any whitespace — and
 * leaves the judgement calls to the authority.
 */
internal fun isPlausibleEmail(trimmed: String): Boolean {
    if (trimmed.isEmpty()) return false
    if (trimmed.any { it.isWhitespace() }) return false
    val parts = trimmed.split('@')
    if (parts.size != 2) return false
    val (local, domain) = parts
    if (local.isEmpty() || domain.isEmpty()) return false
    // A dot with something either side of it: `example` is not a domain, `example.com` is.
    val labels = domain.split('.')
    return labels.size >= 2 && labels.all { it.isNotEmpty() }
}
