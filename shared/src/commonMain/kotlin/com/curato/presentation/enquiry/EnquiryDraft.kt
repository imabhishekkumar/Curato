package com.curato.presentation.enquiry

import com.curato.domain.model.EnquiryForm
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The ephemeral draft (SPEC.md §7.6, "Two distinct stores").
 *
 * Lives in `SavedStateHandle`, survives process death only, and dies with the route. The
 * reusable contact store is a different thing with the opposite lifetime — §7.6 sets them
 * out side by side because conflating them is the easiest way to get this screen wrong.
 *
 * **One serialized object under one key**, rather than ten keys and a ten-way `combine`.
 * That keeps the handle the *single* source: there is no working copy held beside it that
 * could disagree, which is the property the search query's storage argued for and the
 * reason nothing here can be forgotten on a write.
 *
 * The two halves have deliberately different lifetimes. [submitted], [acceptedAt],
 * [sentToEmail] and [productTitle] outlive the field values **within the same route
 * instance**: clearing the whole draft on success would destroy `submitted` at the exact
 * moment it starts mattering, and a restored screen would offer a filled form with a live
 * Submit for an enquiry already sent (AC-20).
 */
@Serializable
internal data class EnquiryDraft(
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val message: String = "",
    /** §7.6's "default on and visible". A first enquiry starts consenting. */
    val consent: Boolean = true,
    /** Whether the customer expanded the collapsed summary. Survives death with the rest. */
    val editing: Boolean = false,
    /**
     * Whether the saved contact has already been merged in.
     *
     * Without it, a customer who deliberately *clears* a prefilled name would have it
     * written back the next time the store emits. Prefill is a one-time arrival step, not
     * a rule that keeps reasserting itself.
     */
    val prefilled: Boolean = false,

    // ── Completion state. Read before the values above, always. ──
    val submitted: Boolean = false,
    val acceptedAt: String? = null,
    /**
     * Where the confirmation says the reply will go, and the product it was about.
     *
     * Beyond §7.6's `submitted`/`acceptedAt` pair, and deliberately so: both are form
     * values, which §7.6 clears on success, yet the thank-you's copy names them. Holding
     * them here is what lets a restored thank-you render identically **with no network
     * call at all** — so an unreachable catalogue or a since-removed product can never
     * replace a confirmed send with an error. Neither field makes anything submittable,
     * which is the rule §7.6 is actually protecting.
     */
    val sentToEmail: String? = null,
    val productTitle: String? = null,
) {
    val form: EnquiryForm
        get() = EnquiryForm(name = name, email = email, phone = phone, message = message)

    /**
     * Success: the values go, the completion state arrives.
     *
     * Built from a fresh draft rather than a `copy`, so "every field value is cleared" is
     * visible in one expression instead of being four assignments someone could miss one
     * of.
     */
    fun completed(acceptedAt: String, sentToEmail: String, productTitle: String?) =
        EnquiryDraft(
            submitted = true,
            acceptedAt = acceptedAt,
            sentToEmail = sentToEmail,
            productTitle = productTitle,
        )
}

/** One instance, so a keystroke is not also an allocation of a serializer. */
internal val DraftJson = Json { ignoreUnknownKeys = true }

/**
 * Absent or unreadable restores as a blank draft.
 *
 * A draft written by an older build must not take the screen down — and a blank draft is
 * a first enquiry, which is a state this screen already handles.
 */
internal fun decodeDraft(raw: String?): EnquiryDraft {
    if (raw.isNullOrBlank()) return EnquiryDraft()
    return runCatching { DraftJson.decodeFromString<EnquiryDraft>(raw) }
        .getOrElse { EnquiryDraft() }
}

internal fun EnquiryDraft.encode(): String = DraftJson.encodeToString(this)
