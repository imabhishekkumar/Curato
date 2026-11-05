package com.curato.presentation.enquiry

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.curato.domain.ApiResult
import com.curato.domain.DomainError
import com.curato.domain.ErrorCode
import com.curato.domain.model.Contact
import com.curato.domain.usecase.ClearSavedContact
import com.curato.domain.usecase.GetProduct
import com.curato.domain.usecase.ObserveSavedContact
import com.curato.domain.usecase.SaveContact
import com.curato.domain.usecase.SubmitEnquiry
import com.curato.domain.validation.FIELD_EMAIL
import com.curato.domain.validation.FIELD_MESSAGE
import com.curato.domain.validation.FIELD_NAME
import com.curato.domain.validation.FIELD_PHONE
import com.curato.domain.validation.VALIDATION_BANNER_MESSAGE
import com.curato.domain.validation.validate
import com.curato.domain.validation.validateField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val KEY_DRAFT = "enquiryDraft"

/**
 * Enquiry (SPEC.md §7.6) — the app's only write, and the only screen with two stores.
 *
 * **It resolves its own product from [productId] alone** (AC-28). Sharing a ViewModel with
 * the product screen would save one request and break the property that makes every route
 * addressable by URL alone — the same property that makes Home, Category, Search and a
 * future deeplink the same door into Product.
 *
 * Two flows feed one state, split by lifetime rather than by subject:
 *
 * - the **draft**, read straight out of [handle], which is what survives process death
 * - the **transient** half — the resolved product, the saved contact, errors, whether a
 *   request is in flight — none of which should survive, because all of it is either
 *   re-derivable or a statement about a request that is no longer running
 *
 * That split is the design. A `fieldErrors` map persisted across process death would
 * annotate inputs about a validation run that happened in a previous process.
 */
class EnquiryViewModel(
    private val productId: Int,
    private val seenPrice: Double?,
    private val handle: SavedStateHandle,
    private val getProduct: GetProduct,
    private val submitEnquiry: SubmitEnquiry,
    private val observeSavedContact: ObserveSavedContact,
    private val saveContact: SaveContact,
    private val clearSavedContact: ClearSavedContact,
) : ViewModel() {

    /** Nothing here survives the process, by design — see the class comment. */
    private data class Transient(
        val subject: EnquirySubject? = null,
        val savedContact: Contact? = null,
        val fieldErrors: Map<String, String> = emptyMap(),
        val banner: EnquiryBanner? = null,
        val submitting: Boolean = false,
        /** Null while the product is fine; otherwise whether a send had been attempted. */
        val unavailableAfterSubmit: Boolean? = null,
        val subjectError: DomainError? = null,
    )

    private val transient = MutableStateFlow(Transient())

    private val draft: StateFlow<EnquiryDraft> = handle
        .getStateFlow(KEY_DRAFT, "")
        .map(::decodeDraft)
        .stateIn(viewModelScope, SharingStarted.Eagerly, decodeDraft(handle[KEY_DRAFT]))

    val state: StateFlow<EnquiryUiState> = combine(draft, transient, ::build)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = build(decodeDraft(handle[KEY_DRAFT]), Transient()),
        )

    init {
        // `submitted` is read **first**, before anything else happens (SPEC.md §7.6,
        // "Restoring after process death"). A confirmed send resolves nothing, observes
        // nothing and requests nothing: the thank-you is already complete in the draft,
        // and a fetch here is the one thing that could replace it with an error (AC-20).
        if (!currentDraft().submitted) {
            load()
            watchSavedContact()
        }
    }

    /** Resolving the subject, and the Retry for [EnquiryUiState.SubjectFailed]. */
    fun load() {
        viewModelScope.launch {
            transient.update {
                it.copy(subject = null, subjectError = null, unavailableAfterSubmit = null)
            }
            when (val result = getProduct(productId)) {
                is ApiResult.Ok ->
                    transient.update { it.copy(subject = result.value.toSubject()) }

                is ApiResult.Fail -> transient.update {
                    // The split comes from the code, not from a second list kept here:
                    // a removed product will not start existing because of a tap.
                    if (result.error.code == ErrorCode.NOT_FOUND) {
                        // Nothing has been sent yet: the customer has only just arrived.
                        it.copy(unavailableAfterSubmit = false)
                    } else {
                        it.copy(subjectError = result.error)
                    }
                }
            }
        }
    }

    /**
     * The saved contact, as a Flow rather than a one-shot read.
     *
     * Unchecking consent clears the store immediately (§9.2), and this is what makes the
     * collapsed summary disappear in the same frame rather than at the next submission.
     */
    private fun watchSavedContact() {
        viewModelScope.launch {
            observeSavedContact().collect { contact ->
                transient.update { it.copy(savedContact = contact) }
                if (contact != null) prefillOnce(contact)
            }
        }
    }

    /**
     * AC-16's one-tap repeat, without overwriting anything the customer typed.
     *
     * Only blank fields are filled, and only once — a half-typed name restored from the
     * draft must win over the saved one, because it is the more recent statement of
     * intent.
     */
    private fun prefillOnce(contact: Contact) {
        val draft = currentDraft()
        if (draft.prefilled) return
        writeDraft(
            draft.copy(
                name = draft.name.ifBlank { contact.name },
                email = draft.email.ifBlank { contact.email },
                phone = draft.phone.ifBlank { contact.phone.orEmpty() },
                prefilled = true,
            ),
        )
    }

    fun onNameChange(value: String) = onFieldChange(FIELD_NAME) { it.copy(name = value) }
    fun onEmailChange(value: String) = onFieldChange(FIELD_EMAIL) { it.copy(email = value) }
    fun onPhoneChange(value: String) = onFieldChange(FIELD_PHONE) { it.copy(phone = value) }
    fun onMessageChange(value: String) =
        onFieldChange(FIELD_MESSAGE) { it.copy(message = value) }

    /**
     * Typing **clears** an error; it never raises one (SPEC.md §6).
     *
     * Validation runs on blur and on submit. Per-keystroke feedback tells a customer their
     * half-typed address is invalid, which they know, and the error then flickers away as
     * they finish the word.
     */
    private fun onFieldChange(field: String, update: (EnquiryDraft) -> EnquiryDraft) {
        writeDraft(update(currentDraft()))
        if (transient.value.fieldErrors.containsKey(field)) {
            transient.update { it.copy(fieldErrors = it.fieldErrors - field) }
        }
    }

    /** §6's "on blur" half: this field only, never the whole form. */
    fun onFieldBlur(field: String) {
        val message = validateField(field, currentDraft().form)
        transient.update {
            it.copy(
                fieldErrors = if (message == null) {
                    it.fieldErrors - field
                } else {
                    it.fieldErrors + (field to message)
                },
            )
        }
    }

    /** The collapsed summary's Edit — one-way, as the kit draws it. */
    fun onEditContact() {
        writeDraft(currentDraft().copy(editing = true))
    }

    /**
     * Unchecking clears the store **immediately** (SPEC.md §9.2), not at the next submit.
     *
     * These are the customer's contact details; withdrawing consent is an instruction to
     * stop holding them, not a preference to be applied later. [EnquiryDraft.prefilled]
     * stays set, so the values on screen are left alone — they were typed into this form
     * and withdrawing consent is about the store, not about the draft.
     */
    fun onConsentChange(checked: Boolean) {
        writeDraft(currentDraft().copy(consent = checked))
        if (!checked) viewModelScope.launch { clearSavedContact() }
    }

    /**
     * Submit, and the Retry for every retryable failure — one function, so retrying is
     * submitting and the two cannot disagree about what gets sent.
     *
     * **Never automatic.** `RATE_LIMITED` is the case that makes this a rule rather than a
     * preference: an auto-retry against a limiter is how a limiter becomes a loop.
     */
    fun submit() {
        // AC-18. Set synchronously, before any suspension point, so the second tap of a
        // double-tap returns here rather than reaching a second request. `PrimaryButton`
        // guards it too; a send that costs an email is worth two gates.
        if (transient.value.submitting) return

        val draft = currentDraft()
        if (draft.submitted) return
        // AC-29: nothing is sent for a product that has not resolved.
        val subject = transient.value.subject ?: return

        val form = draft.form
        val errors = validate(form)
        if (errors.isNotEmpty()) {
            transient.update {
                it.copy(
                    fieldErrors = errors,
                    banner = EnquiryBanner(
                        message = VALIDATION_BANNER_MESSAGE,
                        kind = BannerKind.VALIDATION,
                    ),
                )
            }
            return
        }

        transient.update {
            it.copy(submitting = true, banner = null, fieldErrors = emptyMap())
        }

        viewModelScope.launch {
            val enquiry = form.toEnquiry(productId = productId, seenPrice = seenPrice)
            when (val result = submitEnquiry(enquiry)) {
                is ApiResult.Ok -> {
                    // AC-17, and the whole reason consent is a recorded choice: with the
                    // box unchecked the submission succeeds and leaves nothing behind.
                    if (draft.consent) saveContact(enquiry.contact)

                    // Values go, completion state arrives. In this order, so the draft is
                    // never briefly a filled form that also claims to have been sent.
                    writeDraft(
                        draft.completed(
                            acceptedAt = result.value.acceptedAt,
                            sentToEmail = enquiry.contact.email,
                            productTitle = subject.title,
                        ),
                    )
                    transient.update { it.copy(submitting = false, banner = null) }
                }

                is ApiResult.Fail -> onSubmitFailed(result.error)
            }
        }
    }

    /**
     * Every failure keeps **every** entered value, the message included (AC-13).
     *
     * There is no branch here that clears the form, which is the point: the only state
     * that discards what was typed is success.
     */
    private fun onSubmitFailed(error: DomainError) = transient.update {
        when (error.code) {
            // AC-19. Replaces the form; no Retry, because retrying cannot help. The
            // product stopped existing between opening it and sending, so the copy has
            // to say that the enquiry did not go.
            ErrorCode.NOT_FOUND ->
                it.copy(submitting = false, unavailableAfterSubmit = true)

            // AC-14, §5.1: inline against each input, all values intact, no navigation.
            //
            // The server's map is taken **as the whole truth** rather than merged over a
            // fresh client pass. Nothing reaches here that the client mirror rejected —
            // `submit` gates on it — so a union could only ever equal this, and writing
            // one would imply a disagreement that cannot occur. The reachable case is the
            // opposite: the mirror is looser than §6 on purpose, so the server can reject
            // a field the client passed, and this is the path that renders it.
            ErrorCode.VALIDATION_FAILED -> it.copy(
                submitting = false,
                fieldErrors = error.fieldErrors,
                banner = EnquiryBanner(error.userMessage, kind = BannerKind.VALIDATION),
            )

            else -> it.copy(
                submitting = false,
                banner = EnquiryBanner(
                    message = error.userMessage,
                    // Non-null only on INTERNAL_ERROR, enforced at the API boundary.
                    traceId = error.traceId,
                    kind = if (error.code == ErrorCode.RATE_LIMITED) {
                        BannerKind.DELAY
                    } else {
                        BannerKind.WARNING
                    },
                ),
            )
        }
    }

    /**
     * Draft plus transient, in the order the states exclude each other.
     *
     * `submitted` is tested before anything else, for the reason §7.6 gives: a filled form
     * with a live Submit, for an enquiry already sent, invites a duplicate. It also means
     * no later branch can shadow a confirmed send.
     */
    private fun build(draft: EnquiryDraft, transient: Transient): EnquiryUiState = when {
        draft.submitted -> EnquiryUiState.Sent(
            acceptedAt = draft.acceptedAt,
            productTitle = draft.productTitle,
            email = draft.sentToEmail,
        )

        transient.unavailableAfterSubmit != null ->
            EnquiryUiState.Unavailable(afterSubmit = transient.unavailableAfterSubmit)

        transient.subjectError != null -> EnquiryUiState.SubjectFailed(transient.subjectError)

        else -> EnquiryUiState.Form(
            subject = transient.subject,
            values = draft.form,
            fieldErrors = transient.fieldErrors,
            banner = transient.banner,
            savedContact = transient.savedContact,
            editing = draft.editing,
            submitting = transient.submitting,
            consent = draft.consent,
        )
    }

    private fun currentDraft(): EnquiryDraft = decodeDraft(handle[KEY_DRAFT])

    /**
     * The one place the draft is written.
     *
     * Straight through to the handle with no copy held beside it, so a value on screen and
     * a value that would survive process death are the same value by construction.
     */
    private fun writeDraft(draft: EnquiryDraft) {
        handle[KEY_DRAFT] = draft.encode()
    }
}
