package com.curato

import androidx.lifecycle.SavedStateHandle
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
import com.curato.domain.validation.NAME_MESSAGE
import com.curato.fakes.AcceptedAtFixture
import com.curato.fakes.FakeCatalogueRepository
import com.curato.fakes.FakeEnquiryRepository
import com.curato.fakes.FakeSavedContactRepository
import com.curato.fakes.contact
import com.curato.fakes.error
import com.curato.fakes.fieldError
import com.curato.fakes.product
import com.curato.presentation.enquiry.BannerKind
import com.curato.presentation.enquiry.EnquiryDraft
import com.curato.presentation.enquiry.EnquiryUiState
import com.curato.presentation.enquiry.EnquiryViewModel
import com.curato.presentation.enquiry.encode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Enquiry's ViewModel (SPEC.md §7.6 — AC-12 … AC-22, AC-28, AC-29).
 *
 * The densest suite in the client, and deliberately so: this is the app's only write, it
 * causes mail to be sent, and five of its rules — one submission per double tap, nothing
 * stored without consent, a restored thank-you that cannot be resubmitted — are invisible
 * on screen when they are broken.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EnquiryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        id: Int = 1,
        seenPrice: Double? = 55.99,
        catalogue: FakeCatalogueRepository = FakeCatalogueRepository(),
        enquiries: FakeEnquiryRepository = FakeEnquiryRepository(),
        contacts: FakeSavedContactRepository = FakeSavedContactRepository(),
        savedDraft: EnquiryDraft? = null,
    ) = EnquiryViewModel(
        productId = id,
        seenPrice = seenPrice,
        handle = SavedStateHandle(
            savedDraft?.let { mapOf("enquiryDraft" to it.encode()) } ?: emptyMap(),
        ),
        getProduct = GetProduct(catalogue),
        submitEnquiry = SubmitEnquiry(enquiries),
        observeSavedContact = ObserveSavedContact(contacts),
        saveContact = SaveContact(contacts),
        clearSavedContact = ClearSavedContact(contacts),
    )

    /** A draft that will pass the client mirror, so a test can reach the network path. */
    private fun validDraft(message: String = "Is this in black?") = EnquiryDraft(
        name = "Ada Lovelace",
        email = "ada@example.com",
        phone = "+91 9876543210",
        message = message,
    )

    private fun EnquiryViewModel.form() = assertIs<EnquiryUiState.Form>(state.value)

    /**
     * `state` is a `combine(...).stateIn(...)`, so a synchronous interaction reaches it on
     * the next pass of the scheduler rather than inside the call. `runCurrent()` is that
     * pass — it advances no virtual time, so it cannot accidentally complete a request
     * the test meant to leave in flight.
     *
     * This is the same gap Compose sees in production: `collectAsStateWithLifecycle`
     * delivers on the next frame. The ViewModel's own guards deliberately read
     * `transient.value` and the handle directly rather than `state.value`, which is why
     * the non-re-entrancy gate is exact even though the state is not.
     */
    private fun kotlinx.coroutines.test.TestScope.settle() = testScheduler.runCurrent()

    // ── arrival (AC-28, AC-29) ──

    /**
     * AC-29, and the reason the form is composed from the first frame.
     *
     * The inputs exist and are editable before the product lands; only Submit waits. The
     * alternative — composing the form once the subject resolves — moves four inputs and
     * a button under a thumb already travelling toward them.
     */
    @Test
    fun the_form_is_composed_immediately_but_not_submittable() = runTest(dispatcher) {
        val vm = viewModel()

        with(vm.form()) {
            assertNull(subject)
            assertFalse(canSubmit)
        }

        testScheduler.advanceUntilIdle()
        with(vm.form()) {
            assertEquals("Product 1", subject?.title)
            assertTrue(canSubmit)
        }
    }

    /** AC-28: the id and nothing else. */
    @Test
    fun the_product_is_resolved_from_the_route_id_alone() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository()
        viewModel(id = 17, catalogue = catalogue)

        testScheduler.advanceUntilIdle()

        assertEquals(17, catalogue.lastProductId)
        assertEquals(1, catalogue.productCalls)
    }

    /** Nothing can be sent for a product that has not resolved (AC-29). */
    @Test
    fun submitting_before_the_product_resolves_sends_nothing() = runTest(dispatcher) {
        val enquiries = FakeEnquiryRepository()
        val vm = viewModel(enquiries = enquiries, savedDraft = validDraft())

        vm.submit()
        testScheduler.advanceUntilIdle()

        assertEquals(0, enquiries.calls)
    }

    @Test
    fun a_removed_product_replaces_the_form_before_it_is_usable() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            product = ApiResult.Fail(error(ErrorCode.NOT_FOUND))
        }
        val vm = viewModel(catalogue = catalogue)

        testScheduler.advanceUntilIdle()

        // Nothing was attempted, and the copy must not claim otherwise.
        assertFalse(assertIs<EnquiryUiState.Unavailable>(vm.state.value).afterSubmit)
    }

    @Test
    fun a_retryable_resolve_failure_offers_retry_and_recovers() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            product = ApiResult.Fail(error(ErrorCode.UPSTREAM_UNAVAILABLE))
        }
        val vm = viewModel(catalogue = catalogue)
        testScheduler.advanceUntilIdle()

        val failed = assertIs<EnquiryUiState.SubjectFailed>(vm.state.value)
        assertTrue(failed.error.isRetryable)

        catalogue.product = ApiResult.Ok(product(1))
        vm.load()
        testScheduler.advanceUntilIdle()

        assertIs<EnquiryUiState.Form>(vm.state.value)
        assertEquals(2, catalogue.productCalls)
    }

    // ── the two modes (AC-16) ──

    @Test
    fun a_first_enquiry_shows_the_full_form_with_consent_on() = runTest(dispatcher) {
        val vm = viewModel()
        testScheduler.advanceUntilIdle()

        with(vm.form()) {
            assertNull(savedContact)
            assertFalse(collapsed)
            assertTrue(showConsent)
            // §7.6's "default on and visible".
            assertTrue(consent)
            assertEquals("", values.name)
        }
    }

    /** AC-16: prefilled and submittable in one tap. */
    @Test
    fun saved_details_collapse_and_prefill() = runTest(dispatcher) {
        val vm = viewModel(contacts = FakeSavedContactRepository(contact()))
        testScheduler.advanceUntilIdle()

        with(vm.form()) {
            assertTrue(collapsed)
            // The kit's rule: no checkbox in the repeat mode.
            assertFalse(showConsent)
            assertTrue(canSubmit)
            assertEquals("Ada Lovelace", values.name)
            assertEquals("ada@example.com", values.email)
            assertEquals("+91 9876543210", values.phone)
            // Never carried over: it belongs to the product it was written about.
            assertEquals("", values.message)
        }
    }

    @Test
    fun edit_expands_the_form_and_reveals_consent_without_losing_values() =
        runTest(dispatcher) {
            val vm = viewModel(contacts = FakeSavedContactRepository(contact()))
            testScheduler.advanceUntilIdle()

            vm.onEditContact()
            settle()

            with(vm.form()) {
                assertFalse(collapsed)
                assertTrue(showConsent)
                assertEquals("Ada Lovelace", values.name)
            }
        }

    /**
     * A restored draft outranks the store.
     *
     * Both can hold a name. The draft's is the more recent statement of intent — it was
     * typed into *this* form — so prefill fills the blanks around it rather than over it.
     */
    @Test
    fun prefill_never_overwrites_a_value_the_customer_typed() = runTest(dispatcher) {
        val vm = viewModel(
            contacts = FakeSavedContactRepository(contact(name = "Ada Lovelace")),
            savedDraft = EnquiryDraft(name = "Grace H"),
        )
        testScheduler.advanceUntilIdle()

        with(vm.form()) {
            assertEquals("Grace H", values.name)
            // The blank one still gets filled.
            assertEquals("ada@example.com", values.email)
        }
    }

    @Test
    fun clearing_a_prefilled_field_is_not_undone_by_the_store() = runTest(dispatcher) {
        val contacts = FakeSavedContactRepository(contact())
        val vm = viewModel(contacts = contacts)
        testScheduler.advanceUntilIdle()

        vm.onEditContact()
        vm.onNameChange("")
        testScheduler.advanceUntilIdle()

        assertEquals("", vm.form().values.name)
    }

    // ── validation (§6, AC-14) ──

    /** §6: never per keystroke. A half-typed address is not yet wrong. */
    @Test
    fun typing_does_not_validate() = runTest(dispatcher) {
        val vm = viewModel()
        testScheduler.advanceUntilIdle()

        vm.onNameChange("J")
        vm.onEmailChange("ada@")
        settle()

        assertTrue(vm.form().fieldErrors.isEmpty())
    }

    @Test
    fun blur_validates_that_field_and_only_that_field() = runTest(dispatcher) {
        val vm = viewModel()
        testScheduler.advanceUntilIdle()

        vm.onNameChange("J")
        vm.onEmailChange("nonsense")
        vm.onFieldBlur(FIELD_NAME)
        settle()

        with(vm.form().fieldErrors) {
            assertEquals(NAME_MESSAGE, get(FIELD_NAME))
            assertFalse(containsKey(FIELD_EMAIL))
        }
    }

    @Test
    fun typing_clears_an_error_already_showing() = runTest(dispatcher) {
        val vm = viewModel()
        testScheduler.advanceUntilIdle()

        vm.onNameChange("J")
        vm.onFieldBlur(FIELD_NAME)
        settle()
        assertTrue(vm.form().fieldErrors.containsKey(FIELD_NAME))

        vm.onNameChange("Jo")
        settle()

        assertFalse(vm.form().fieldErrors.containsKey(FIELD_NAME))
    }

    /**
     * The client mirror is a gate on the request, not only a decoration.
     *
     * An invalid form must not reach the network: the backend would reject it, and the
     * round trip costs the customer a wait to be told what the client already knew.
     */
    @Test
    fun an_invalid_form_is_not_sent() = runTest(dispatcher) {
        val enquiries = FakeEnquiryRepository()
        val vm = viewModel(enquiries = enquiries, savedDraft = EnquiryDraft(name = "J"))
        testScheduler.advanceUntilIdle()

        vm.submit()
        testScheduler.advanceUntilIdle()

        assertEquals(0, enquiries.calls)
        with(vm.form()) {
            assertEquals(NAME_MESSAGE, fieldErrors[FIELD_NAME])
            // The banner too, so the failure is stated and not only highlighted.
            assertEquals("Please check the highlighted fields.", banner?.message)
            // Nothing the customer typed is lost.
            assertEquals("J", values.name)
        }
    }

    /**
     * AC-14 and §5.1: a server 422 annotates the inputs it names, keeps every value, and
     * does not navigate.
     *
     * Reachable precisely because the client mirror is looser than §6 on purpose — the
     * server can reject an address the client let through.
     */
    @Test
    fun a_server_validation_failure_lands_under_the_named_input() = runTest(dispatcher) {
        val enquiries = FakeEnquiryRepository().apply {
            result = ApiResult.Fail(fieldError(FIELD_EMAIL to "Enter a valid email address"))
        }
        val vm = viewModel(enquiries = enquiries, savedDraft = validDraft())
        testScheduler.advanceUntilIdle()

        vm.submit()
        testScheduler.advanceUntilIdle()

        with(vm.form()) {
            assertEquals("Enter a valid email address", fieldErrors[FIELD_EMAIL])
            assertEquals("Please check the highlighted fields.", banner?.message)
            // Every value intact, the message included.
            assertEquals("Ada Lovelace", values.name)
            assertEquals("Is this in black?", values.message)
        }
    }

    // ── success (AC-12, AC-17, §9.2) ──

    /** AC-12: the backend answers 2xx only once SMTP has accepted. */
    @Test
    fun a_successful_submission_shows_the_thank_you() = runTest(dispatcher) {
        val vm = viewModel(savedDraft = validDraft())
        testScheduler.advanceUntilIdle()

        vm.submit()
        testScheduler.advanceUntilIdle()

        val sent = assertIs<EnquiryUiState.Sent>(vm.state.value)
        assertEquals(AcceptedAtFixture, sent.acceptedAt)
        assertEquals("ada@example.com", sent.email)
        assertEquals("Product 1", sent.productTitle)
    }

    @Test
    fun success_with_consent_saves_the_contact_details() = runTest(dispatcher) {
        val contacts = FakeSavedContactRepository()
        val vm = viewModel(contacts = contacts, savedDraft = validDraft())
        testScheduler.advanceUntilIdle()

        vm.submit()
        testScheduler.advanceUntilIdle()

        assertEquals(1, contacts.saves)
        // Trimmed and normalised, and holding nothing else — a `Contact` has no room for
        // a message, which is the structural half of "the message is never saved".
        assertEquals(
            Contact("Ada Lovelace", "ada@example.com", "+91 9876543210"),
            contacts.saved,
        )
    }

    /**
     * The backend confirmed the send, so a local storage failure must not undo it.
     *
     * Saving details for next time used to run before completion was recorded: a throwing
     * store left the screen on a submitting form, kept `submitted` out of the draft, and
     * escaped the coroutine — a confirmed email presented as a send still in progress,
     * with a restore that would offer the form again.
     */
    @Test
    fun a_failed_contact_save_still_shows_and_keeps_the_thank_you() = runTest(dispatcher) {
        val contacts = FakeSavedContactRepository().apply {
            saveFailure = IllegalStateException("disk full")
        }
        val enquiries = FakeEnquiryRepository()
        val handle = SavedStateHandle(mapOf("enquiryDraft" to validDraft().encode()))
        fun build() = EnquiryViewModel(
            productId = 1,
            seenPrice = 55.99,
            handle = handle,
            getProduct = GetProduct(FakeCatalogueRepository()),
            submitEnquiry = SubmitEnquiry(enquiries),
            observeSavedContact = ObserveSavedContact(contacts),
            saveContact = SaveContact(contacts),
            clearSavedContact = ClearSavedContact(contacts),
        )
        val vm = build()
        testScheduler.advanceUntilIdle()

        vm.submit()
        testScheduler.advanceUntilIdle()

        // The save was attempted, failed, and changed nothing the customer can see.
        assertEquals(1, contacts.saves)
        assertNull(contacts.saved)
        assertEquals(1, enquiries.calls)
        assertEquals(AcceptedAtFixture, assertIs<EnquiryUiState.Sent>(vm.state.value).acceptedAt)

        // Not a retry, not a second send.
        vm.submit()
        testScheduler.advanceUntilIdle()
        assertEquals(1, enquiries.calls)

        // Completion reached the draft, so a process-death restore is a thank-you too.
        val restored = build()
        testScheduler.advanceUntilIdle()
        assertIs<EnquiryUiState.Sent>(restored.state.value)
        assertEquals(1, enquiries.calls)
    }

    /** AC-17: unchecking consent and submitting leaves nothing stored. */
    @Test
    fun success_without_consent_stores_nothing() = runTest(dispatcher) {
        val contacts = FakeSavedContactRepository()
        val vm = viewModel(contacts = contacts, savedDraft = validDraft())
        testScheduler.advanceUntilIdle()

        vm.onConsentChange(false)
        vm.submit()
        testScheduler.advanceUntilIdle()

        assertIs<EnquiryUiState.Sent>(vm.state.value)
        assertEquals(0, contacts.saves)
        assertNull(contacts.saved)
    }

    /**
     * §9.2: "Cleared immediately on unchecking consent" — not at the next submission.
     *
     * Withdrawing consent is an instruction to stop holding the details, so it takes
     * effect on the tap. Deferring it to submit would leave them stored for a customer
     * who then abandoned the form.
     */
    @Test
    fun unchecking_consent_clears_the_store_at_once() = runTest(dispatcher) {
        val contacts = FakeSavedContactRepository(contact())
        val enquiries = FakeEnquiryRepository()
        val vm = viewModel(contacts = contacts, enquiries = enquiries)
        testScheduler.advanceUntilIdle()

        vm.onEditContact()
        vm.onConsentChange(false)
        testScheduler.advanceUntilIdle()

        assertEquals(1, contacts.clears)
        assertNull(contacts.saved)
        // And nothing was sent in the process.
        assertEquals(0, enquiries.calls)
    }

    /** The store emptying un-collapses the summary in the same frame. */
    @Test
    fun clearing_the_store_returns_the_screen_to_a_first_enquiry() = runTest(dispatcher) {
        val contacts = FakeSavedContactRepository(contact())
        val vm = viewModel(contacts = contacts)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.form().collapsed)

        vm.onEditContact()
        vm.onConsentChange(false)
        testScheduler.advanceUntilIdle()

        with(vm.form()) {
            assertNull(savedContact)
            assertFalse(collapsed)
            assertTrue(showConsent)
        }
    }

    // ── failure handling (AC-13, AC-18, AC-19, §5.1) ──

    /**
     * AC-18, and the test that justifies the synchronous guard.
     *
     * The second call lands while the first is still suspended in the repository, which
     * is exactly the double tap. A guard set after the first `await` would let both
     * through and send two emails to sales.
     */
    @Test
    fun a_double_tap_sends_exactly_one_enquiry() = runTest(dispatcher) {
        val enquiries = FakeEnquiryRepository().apply { latencyMillis = 100 }
        val vm = viewModel(enquiries = enquiries, savedDraft = validDraft())
        testScheduler.advanceUntilIdle()

        vm.submit()
        vm.submit()
        vm.submit()
        testScheduler.advanceUntilIdle()

        assertEquals(1, enquiries.calls)
    }

    @Test
    fun the_form_is_inert_while_a_submission_is_in_flight() = runTest(dispatcher) {
        val enquiries = FakeEnquiryRepository().apply { latencyMillis = 100 }
        val vm = viewModel(enquiries = enquiries, savedDraft = validDraft())
        testScheduler.advanceUntilIdle()

        vm.submit()
        testScheduler.advanceTimeBy(1)

        with(vm.form()) {
            assertTrue(submitting)
            assertFalse(canSubmit)
        }
    }

    /** AC-13: every value stays, the message included, and Retry is offered. */
    @Test
    fun a_send_failure_keeps_every_value_and_offers_a_retry() = runTest(dispatcher) {
        val enquiries = FakeEnquiryRepository().apply {
            result = ApiResult.Fail(error(ErrorCode.EMAIL_SEND_FAILED))
        }
        val vm = viewModel(enquiries = enquiries, savedDraft = validDraft())
        testScheduler.advanceUntilIdle()

        vm.submit()
        testScheduler.advanceUntilIdle()

        with(vm.form()) {
            assertFalse(submitting)
            assertTrue(canSubmit)
            assertEquals(BannerKind.WARNING, banner?.kind)
            assertEquals("Ada Lovelace", values.name)
            assertEquals("+91 9876543210", values.phone)
            assertEquals("Is this in black?", values.message)
            // Nothing was stored for a submission that did not succeed.
            assertTrue(fieldErrors.isEmpty())
        }

        enquiries.result = ApiResult.Ok(com.curato.domain.model.EnquiryAccepted(AcceptedAtFixture))
        vm.submit()
        testScheduler.advanceUntilIdle()

        assertEquals(2, enquiries.calls)
        assertIs<EnquiryUiState.Sent>(vm.state.value)
    }

    /**
     * §5.1: Retry is offered but **never fired automatically**.
     *
     * An auto-retry against a rate limiter is how a limiter becomes a loop, so this
     * asserts the count does not move on its own after the failure settles.
     */
    @Test
    fun rate_limiting_waits_for_the_customer() = runTest(dispatcher) {
        val enquiries = FakeEnquiryRepository().apply {
            result = ApiResult.Fail(error(ErrorCode.RATE_LIMITED))
        }
        val vm = viewModel(enquiries = enquiries, savedDraft = validDraft())
        testScheduler.advanceUntilIdle()

        vm.submit()
        testScheduler.advanceUntilIdle()

        // The kit's clock glyph rather than the warning triangle: "wait" and "wrong" ask
        // for different reactions.
        assertEquals(BannerKind.DELAY, vm.form().banner?.kind)
        assertEquals(1, enquiries.calls)
    }

    /** The only state that surfaces a diagnostic, and it surfaces exactly one. */
    @Test
    fun an_internal_error_surfaces_its_trace_id() = runTest(dispatcher) {
        val enquiries = FakeEnquiryRepository().apply {
            result = ApiResult.Fail(
                DomainError(
                    code = ErrorCode.INTERNAL_ERROR,
                    userMessage = "Something went wrong on our end.",
                    traceId = "01JBQ7F3K2",
                ),
            )
        }
        val vm = viewModel(enquiries = enquiries, savedDraft = validDraft())
        testScheduler.advanceUntilIdle()

        vm.submit()
        testScheduler.advanceUntilIdle()

        assertEquals("01JBQ7F3K2", vm.form().banner?.traceId)
    }

    @Test
    fun no_other_failure_carries_a_trace_id() = runTest(dispatcher) {
        val enquiries = FakeEnquiryRepository().apply {
            result = ApiResult.Fail(error(ErrorCode.EMAIL_SEND_FAILED))
        }
        val vm = viewModel(enquiries = enquiries, savedDraft = validDraft())
        testScheduler.advanceUntilIdle()

        vm.submit()
        testScheduler.advanceUntilIdle()

        assertNull(vm.form().banner?.traceId)
    }

    /** AC-19: no Retry, one route back — the product stopped existing mid-flow. */
    @Test
    fun a_removed_product_on_submit_replaces_the_form() = runTest(dispatcher) {
        val enquiries = FakeEnquiryRepository().apply {
            result = ApiResult.Fail(error(ErrorCode.NOT_FOUND))
        }
        val vm = viewModel(enquiries = enquiries, savedDraft = validDraft())
        testScheduler.advanceUntilIdle()

        vm.submit()
        testScheduler.advanceUntilIdle()

        assertIs<EnquiryUiState.Unavailable>(vm.state.value)
    }

    // ── the banner names the frame, and the frame names the action ──

    /**
     * A rejected form was never sent, so the button must not call the next tap a retry.
     *
     * Found on a device: the label keyed off "is a banner showing" rather than "did a
     * request fail", and a client-side validation failure relabelled Submit to "Retry"
     * for an attempt that had not happened. The kit draws all three frames and they
     * disagree on this, which is what makes it a rule rather than a preference.
     */
    @Test
    fun a_validation_failure_leaves_the_action_unchanged() = runTest(dispatcher) {
        val vm = viewModel(savedDraft = EnquiryDraft(name = "J"))
        testScheduler.advanceUntilIdle()

        vm.submit()
        testScheduler.advanceUntilIdle()

        with(assertIs<EnquiryUiState.Form>(vm.state.value).banner!!) {
            assertEquals(BannerKind.VALIDATION, kind)
            assertEquals("Send enquiry", actionLabel)
        }
    }

    /** A server 422 is the same frame: the form was reached, but nothing was sent. */
    @Test
    fun a_server_validation_failure_also_leaves_the_action_unchanged() =
        runTest(dispatcher) {
            val enquiries = FakeEnquiryRepository().apply {
                result = ApiResult.Fail(fieldError(FIELD_EMAIL to "Enter a valid email address"))
            }
            val vm = viewModel(enquiries = enquiries, savedDraft = validDraft())
            testScheduler.advanceUntilIdle()

            vm.submit()
            testScheduler.advanceUntilIdle()

            assertEquals(BannerKind.VALIDATION, vm.form().banner?.kind)
            assertEquals("Send enquiry", vm.form().banner?.actionLabel)
        }

    @Test
    fun a_failed_request_makes_the_next_tap_a_retry() = runTest(dispatcher) {
        val enquiries = FakeEnquiryRepository().apply {
            result = ApiResult.Fail(error(ErrorCode.EMAIL_SEND_FAILED))
        }
        val vm = viewModel(enquiries = enquiries, savedDraft = validDraft())
        testScheduler.advanceUntilIdle()

        vm.submit()
        testScheduler.advanceUntilIdle()

        assertEquals("Retry", vm.form().banner?.actionLabel)
    }

    /** The kit words the rate-limited frame differently, because the ask is different. */
    @Test
    fun rate_limiting_asks_the_customer_to_try_again() = runTest(dispatcher) {
        val enquiries = FakeEnquiryRepository().apply {
            result = ApiResult.Fail(error(ErrorCode.RATE_LIMITED))
        }
        val vm = viewModel(enquiries = enquiries, savedDraft = validDraft())
        testScheduler.advanceUntilIdle()

        vm.submit()
        testScheduler.advanceUntilIdle()

        assertEquals("Try again", vm.form().banner?.actionLabel)
    }

    // ── process death (AC-20, AC-21) ──

    /**
     * AC-20, and the strongest reason the completion state carries its own copy.
     *
     * A restored thank-you fires **nothing**: no product fetch, so no unreachable
     * catalogue and no since-removed product can replace a send the backend confirmed.
     */
    @Test
    fun a_submitted_draft_restores_the_thank_you_and_requests_nothing() =
        runTest(dispatcher) {
            val catalogue = FakeCatalogueRepository()
            val enquiries = FakeEnquiryRepository()
            val vm = viewModel(
                catalogue = catalogue,
                enquiries = enquiries,
                savedDraft = EnquiryDraft(
                    submitted = true,
                    acceptedAt = AcceptedAtFixture,
                    sentToEmail = "ada@example.com",
                    productTitle = "Mens Cotton Jacket",
                ),
            )

            // Before anything runs, and after everything has.
            assertIs<EnquiryUiState.Sent>(vm.state.value)
            testScheduler.advanceUntilIdle()

            val sent = assertIs<EnquiryUiState.Sent>(vm.state.value)
            assertEquals("Mens Cotton Jacket", sent.productTitle)
            assertEquals("ada@example.com", sent.email)
            assertEquals(0, catalogue.productCalls)
            assertEquals(0, enquiries.calls)
        }

    /** And it can never become a form again, whatever is asked of it. */
    @Test
    fun a_restored_thank_you_cannot_be_resubmitted() = runTest(dispatcher) {
        val enquiries = FakeEnquiryRepository()
        val vm = viewModel(
            enquiries = enquiries,
            savedDraft = EnquiryDraft(submitted = true, acceptedAt = AcceptedAtFixture),
        )
        testScheduler.advanceUntilIdle()

        vm.submit()
        vm.onNameChange("someone else")
        vm.submit()
        testScheduler.advanceUntilIdle()

        assertIs<EnquiryUiState.Sent>(vm.state.value)
        assertEquals(0, enquiries.calls)
    }

    /** AC-21: a half-typed message survives, and the form comes back as a form. */
    @Test
    fun an_unsubmitted_draft_restores_the_form_with_its_values() = runTest(dispatcher) {
        val vm = viewModel(
            savedDraft = EnquiryDraft(
                name = "Ada",
                email = "ada@example.com",
                message = "Does this ship with a VESA",
                consent = false,
                editing = true,
            ),
        )
        testScheduler.advanceUntilIdle()

        with(vm.form()) {
            assertEquals("Does this ship with a VESA", values.message)
            assertEquals("Ada", values.name)
            // Consent and the expanded state survive too — both are the customer's
            // choices about this form, not derived state.
            assertFalse(consent)
            assertTrue(editing)
        }
    }

    /**
     * AC-21's second half: a fresh enquiry does not inherit the message.
     *
     * The draft is scoped to its route instance, which is what a second `SavedStateHandle`
     * stands in for here — a different product is a different entry.
     */
    @Test
    fun a_fresh_enquiry_starts_with_an_empty_message() = runTest(dispatcher) {
        val contacts = FakeSavedContactRepository(contact())
        viewModel(id = 1, contacts = contacts, savedDraft = validDraft("about product one"))
        testScheduler.advanceUntilIdle()

        val second = viewModel(id = 2, contacts = contacts)
        testScheduler.advanceUntilIdle()

        with(second.form()) {
            assertEquals("", values.message)
            // The contact details *do* carry over — that is the whole point of the store.
            assertEquals("Ada Lovelace", values.name)
        }
    }

    /** Success clears the values and keeps the completion state (§7.6). */
    @Test
    fun success_clears_the_field_values_but_not_the_confirmation() = runTest(dispatcher) {
        val vm = viewModel(savedDraft = validDraft())
        testScheduler.advanceUntilIdle()

        vm.submit()
        testScheduler.advanceUntilIdle()

        // Nothing re-enters the form, so the values are observed through the only door
        // left: the state is `Sent`, and it still knows what was sent and when.
        val sent = assertIs<EnquiryUiState.Sent>(vm.state.value)
        assertEquals(AcceptedAtFixture, sent.acceptedAt)
    }

    // ── seenPrice (AC-22's client half, §3) ──

    @Test
    fun the_seen_price_reaches_the_request_exactly_as_the_route_carried_it() =
        runTest(dispatcher) {
            val enquiries = FakeEnquiryRepository()
            val vm = viewModel(
                seenPrice = 109.95,
                enquiries = enquiries,
                savedDraft = validDraft(),
            )
            testScheduler.advanceUntilIdle()

            vm.submit()
            testScheduler.advanceUntilIdle()

            // Not rounded, not re-read from the resolved product: §3 makes this the price
            // the customer *saw*, which is the only thing that makes a drift note mean
            // anything.
            assertEquals(109.95, enquiries.enquiries.single().seenPrice)
        }

    @Test
    fun an_absent_seen_price_stays_absent() = runTest(dispatcher) {
        val enquiries = FakeEnquiryRepository()
        val vm = viewModel(seenPrice = null, enquiries = enquiries, savedDraft = validDraft())
        testScheduler.advanceUntilIdle()

        vm.submit()
        testScheduler.advanceUntilIdle()

        assertNull(enquiries.enquiries.single().seenPrice)
    }

    @Test
    fun the_product_id_on_the_request_is_the_route_s() = runTest(dispatcher) {
        val enquiries = FakeEnquiryRepository()
        val vm = viewModel(id = 20, enquiries = enquiries, savedDraft = validDraft())
        testScheduler.advanceUntilIdle()

        vm.submit()
        testScheduler.advanceUntilIdle()

        assertEquals(20, enquiries.enquiries.single().productId)
    }

    /** The optional fields are absent rather than empty by the time they are sent. */
    @Test
    fun blank_optionals_are_absent_on_the_request() = runTest(dispatcher) {
        val enquiries = FakeEnquiryRepository()
        val vm = viewModel(
            enquiries = enquiries,
            savedDraft = EnquiryDraft(name = "Ada", email = "ada@example.com"),
        )
        testScheduler.advanceUntilIdle()

        vm.submit()
        testScheduler.advanceUntilIdle()

        with(enquiries.enquiries.single()) {
            assertNull(contact.phone)
            assertNull(message)
        }
    }

    @Test
    fun a_message_too_long_is_caught_before_the_request() = runTest(dispatcher) {
        val enquiries = FakeEnquiryRepository()
        val vm = viewModel(
            enquiries = enquiries,
            savedDraft = validDraft(message = "a".repeat(1001)),
        )
        testScheduler.advanceUntilIdle()

        vm.submit()
        testScheduler.advanceUntilIdle()

        assertEquals(0, enquiries.calls)
        assertTrue(vm.form().fieldErrors.containsKey(FIELD_MESSAGE))
    }

    @Test
    fun a_malformed_phone_is_caught_before_the_request() = runTest(dispatcher) {
        val enquiries = FakeEnquiryRepository()
        val vm = viewModel(
            enquiries = enquiries,
            savedDraft = EnquiryDraft(
                name = "Ada",
                email = "ada@example.com",
                phone = "555-CALL",
            ),
        )
        testScheduler.advanceUntilIdle()

        vm.submit()
        testScheduler.advanceUntilIdle()

        assertEquals(0, enquiries.calls)
        assertTrue(vm.form().fieldErrors.containsKey(FIELD_PHONE))
    }
}
