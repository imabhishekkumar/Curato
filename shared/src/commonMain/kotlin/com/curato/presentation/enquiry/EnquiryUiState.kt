package com.curato.presentation.enquiry

import com.curato.domain.DomainError
import com.curato.domain.model.Contact
import com.curato.domain.model.EnquiryForm
import com.curato.domain.model.Product

/**
 * What the enquiry screen is about, above the form (SPEC.md §7.6).
 *
 * The kit draws it because "an enquiry with no visible subject is a form a customer
 * cannot check before sending". It is resolved from the route's id, never handed over by
 * the product screen (AC-28).
 */
data class EnquirySubject(
    val title: String,
    val price: Double,
    val currency: String,
    val imageUrl: String,
)

fun Product.toSubject() = EnquirySubject(
    title = title,
    price = price,
    currency = currency,
    imageUrl = imageUrl,
)

/**
 * Which of the kit's three banner frames this is.
 *
 * It picks the leading glyph *and* what the action below is called, because those move
 * together: nothing was attempted when the client's own mirror rejects the form, so
 * calling the button "Retry" there would name an attempt that never happened.
 */
enum class BannerKind {
    /** Client or server validation. The form was never sent, so the action is unchanged. */
    VALIDATION,

    /** A request failed and can be made again — `EMAIL_SEND_FAILED` and friends. */
    WARNING,

    /** `RATE_LIMITED`. Retry is offered, but the copy asks for a pause first. */
    DELAY,
}

/**
 * The one general error string shown on this screen (SPEC.md §5).
 *
 * [traceId] is non-null only for `INTERNAL_ERROR`, and not by a check made here:
 * `DomainError` already drops it at the API boundary for every other code, so the screen
 * cannot surface one by accident. `developerMessage` never reaches this layer at all.
 */
data class EnquiryBanner(
    val message: String,
    val traceId: String? = null,
    val kind: BannerKind = BannerKind.WARNING,
) {
    /** The kit's three labels, read off the frame rather than decided at the call site. */
    val actionLabel: String
        get() = when (kind) {
            BannerKind.VALIDATION -> SubmitLabel
            BannerKind.WARNING -> "Retry"
            BannerKind.DELAY -> "Try again"
        }
}

/** The button's resting label, and what a validation failure leaves it as. */
const val SubmitLabel = "Send enquiry"

/**
 * Enquiry (SPEC.md §7.6).
 *
 * Four states, and the split is by *what the screen is*, not by what failed. A retryable
 * failure while submitting is still the form — annotated, with every value intact — so it
 * is not a state of its own; only the three things that genuinely replace the form are.
 */
sealed interface EnquiryUiState {

    /**
     * The form, from the first frame.
     *
     * [subject] is `null` while the product is still resolving: the fields are composed
     * and editable, the header shimmers, and Submit is disabled (AC-29). Composing the
     * form late instead would move the inputs under a thumb already travelling toward
     * them, the same reason Product's action bar arrives disabled rather than arriving
     * late.
     */
    data class Form(
        val subject: EnquirySubject?,
        val values: EnquiryForm,
        val fieldErrors: Map<String, String>,
        val banner: EnquiryBanner?,
        /** Non-null means a previous enquiry saved details, so the repeat mode applies. */
        val savedContact: Contact?,
        val editing: Boolean,
        val submitting: Boolean,
        val consent: Boolean,
    ) : EnquiryUiState {

        /** AC-29: nothing is submittable until there is a product to enquire about. */
        val canSubmit: Boolean get() = subject != null && !submitting

        /** AC-16: saved details collapse to one line unless the customer asked to edit. */
        val collapsed: Boolean get() = savedContact != null && !editing

        /**
         * The kit's rule: the repeat mode has no checkbox.
         *
         * Consent already resolved on the enquiry that saved these details; unchecking it
         * lives behind Edit, which is also where the fields it governs are.
         */
        val showConsent: Boolean get() = !collapsed
    }

    /**
     * The thank-you (AC-12), a state of this route rather than a destination.
     *
     * Back from here lands on Product and can never re-enter a submitted form (AC-15).
     * Every field is nullable so a draft written by an older build degrades to a quieter
     * confirmation rather than to no confirmation — an enquiry SMTP has accepted must not
     * stop looking accepted because a string was missing.
     */
    data class Sent(
        val acceptedAt: String?,
        val productTitle: String?,
        val email: String?,
    ) : EnquiryUiState

    /**
     * `NOT_FOUND`, from resolving the product *or* from submitting (AC-19).
     *
     * The one failure that replaces the form rather than annotating it: nothing the
     * customer can retype makes a removed product exist, so there is one route out and
     * no Retry.
     *
     * [afterSubmit] exists because the two arrivals need different sentences, and the
     * difference is not cosmetic. Reaching this on arrival and being told "your enquiry
     * was not sent" describes an attempt that never happened; reaching it *after* tapping
     * Send and being told only that the product is gone leaves the one question the
     * customer actually has — did it go? — unanswered.
     */
    data class Unavailable(val afterSubmit: Boolean) : EnquiryUiState

    /**
     * A retryable failure **while resolving the subject** — the form is not composed.
     *
     * Distinct from a failure while submitting, which keeps the form and its values. Here
     * there is nothing to keep: no product resolved, so there is nothing to enquire about
     * and no values have been typed into a form that never appeared.
     */
    data class SubjectFailed(val error: DomainError) : EnquiryUiState
}
