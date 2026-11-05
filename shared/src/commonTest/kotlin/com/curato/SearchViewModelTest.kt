package com.curato

import androidx.lifecycle.SavedStateHandle
import com.curato.domain.ApiResult
import com.curato.domain.DomainError
import com.curato.domain.ErrorCode
import com.curato.domain.usecase.MIN_QUERY_LENGTH
import com.curato.domain.usecase.MIN_QUERY_MESSAGE
import com.curato.domain.usecase.SearchProducts
import com.curato.fakes.FakeCatalogueRepository
import com.curato.fakes.error
import com.curato.fakes.searchResults
import com.curato.fakes.summary
import com.curato.presentation.search.SearchDebounceMillis
import com.curato.presentation.search.SearchUiState
import com.curato.presentation.search.SearchViewModel
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
import kotlin.test.assertTrue

/**
 * Search's ViewModel (SPEC.md §7.4 — AC-7, AC-8, AC-8a, AC-8b, AC-8c).
 *
 * The debounce assertions are the ones that need a virtual clock, and they are the reason
 * this file exists rather than being left to a device check: "typing `backpack` fires one
 * request, not eight" is a claim about time, and nothing on a device can measure it
 * except by reading a server log.
 *
 * Matching itself — case, trimming, title-versus-description — is the **backend's**
 * contract and is tested there against the real catalogue. What is tested here is that
 * the client sends the right term, at the right moment, and renders an empty answer as a
 * success.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        catalogue: FakeCatalogueRepository = FakeCatalogueRepository(),
        savedQuery: String? = null,
    ) = SearchViewModel(
        handle = SavedStateHandle(savedQuery?.let { mapOf("q" to it) } ?: emptyMap()),
        searchProducts = SearchProducts(catalogue),
    )

    // ---- arriving ----------------------------------------------------------------

    @Test
    fun an_empty_field_starts_below_the_minimum_and_asks_for_nothing() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository()
        val vm = viewModel(catalogue = catalogue)

        assertIs<SearchUiState.BelowMinimum>(vm.state.value)
        assertEquals("", vm.query.value)

        testScheduler.advanceUntilIdle()

        // The pristine screen is not a search with no results — it is no search at all.
        assertEquals(0, catalogue.searchCalls)
    }

    /**
     * §7.4's `SavedStateHandle`, and the reason this screen takes one at all.
     *
     * A term restored after process death is already settled, so it is not debounced —
     * showing "Keep typing" for 300ms in front of a search certain to run would be a
     * flicker with nothing behind it.
     */
    @Test
    fun a_restored_query_is_searched_at_once_without_waiting_for_the_debounce() =
        runTest(dispatcher) {
            val catalogue = FakeCatalogueRepository()
            val vm = viewModel(catalogue = catalogue, savedQuery = "bag")

            assertEquals("bag", vm.query.value)

            // One virtual millisecond — far inside the debounce window, so a debounced
            // path would still be waiting here.
            testScheduler.advanceTimeBy(1)

            assertEquals(listOf("bag"), catalogue.searchQueries)
            assertIs<SearchUiState.Results>(vm.state.value)
        }

    @Test
    fun a_restored_query_below_the_minimum_is_not_searched() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository()
        val vm = viewModel(catalogue = catalogue, savedQuery = "b")

        testScheduler.advanceUntilIdle()

        assertEquals(0, catalogue.searchCalls)
        assertIs<SearchUiState.BelowMinimum>(vm.state.value)
    }

    // ---- the minimum ------------------------------------------------------------

    /** §7.4's disabled submission, expressed as "no request is made". */
    @Test
    fun a_term_below_the_minimum_is_never_sent() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository()
        val vm = viewModel(catalogue = catalogue)

        // Whitespace counts for nothing, which is AC-8c's trim seen from the client side.
        for (typed in listOf("b", " ", "  ", "\n", " a ")) {
            vm.onQueryChange(typed)
            testScheduler.advanceUntilIdle()
            assertIs<SearchUiState.BelowMinimum>(vm.state.value)
        }

        assertEquals(0, catalogue.searchCalls)
    }

    @Test
    fun the_minimum_is_two_characters_and_the_boundary_is_exact() = runTest(dispatcher) {
        assertEquals(2, MIN_QUERY_LENGTH)
        val catalogue = FakeCatalogueRepository()
        val vm = viewModel(catalogue = catalogue)

        vm.onQueryChange("b")
        testScheduler.advanceUntilIdle()
        assertEquals(0, catalogue.searchCalls)

        vm.onQueryChange("ba")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("ba"), catalogue.searchQueries)
    }

    @Test
    fun the_below_minimum_message_is_the_one_the_server_would_send() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onQueryChange("b")
        testScheduler.advanceUntilIdle()

        assertEquals(MIN_QUERY_MESSAGE, assertIs<SearchUiState.BelowMinimum>(vm.state.value).message)
    }

    // ---- the debounce -----------------------------------------------------------

    /** The whole point of the chosen design: a burst of typing is one search. */
    @Test
    fun typing_a_word_fires_one_request_for_the_final_term() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository()
        val vm = viewModel(catalogue = catalogue)

        for (prefix in listOf("b", "ba", "bac", "back", "backp", "backpa", "backpac", "backpack")) {
            vm.onQueryChange(prefix)
            // Faster than the debounce, which is what typing is.
            testScheduler.advanceTimeBy(40)
        }
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("backpack"), catalogue.searchQueries)
    }

    @Test
    fun nothing_is_sent_until_the_debounce_has_elapsed() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository()
        val vm = viewModel(catalogue = catalogue)

        vm.onQueryChange("bag")

        testScheduler.advanceTimeBy(SearchDebounceMillis)
        assertEquals(0, catalogue.searchCalls, "fired at the boundary, before it elapsed")

        testScheduler.advanceTimeBy(1)
        assertEquals(1, catalogue.searchCalls)
    }

    @Test
    fun a_pause_mid_word_searches_twice_because_the_customer_stopped() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository()
        val vm = viewModel(catalogue = catalogue)

        vm.onQueryChange("bag")
        testScheduler.advanceUntilIdle()
        vm.onQueryChange("bags")
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("bag", "bags"), catalogue.searchQueries)
    }

    // ---- what reaches the repository --------------------------------------------

    /** AC-8c, client side: the server trims, and so the client does not send slack. */
    @Test
    fun the_term_is_trimmed_before_it_is_sent() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository()
        val vm = viewModel(catalogue = catalogue)

        vm.onQueryChange("  bag  ")
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("bag"), catalogue.searchQueries)
        // The field keeps exactly what was typed — trimming is about the request.
        assertEquals("  bag  ", vm.query.value)
    }

    /** `"bag"` → `"bag "` is the same search; the server trims, so re-sending it is waste. */
    @Test
    fun a_whitespace_only_edit_does_not_search_again() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository()
        val vm = viewModel(catalogue = catalogue)

        vm.onQueryChange("bag")
        testScheduler.advanceUntilIdle()
        assertEquals(1, catalogue.searchCalls)

        vm.onQueryChange("bag ")
        vm.onQueryChange("bag  ")
        testScheduler.advanceUntilIdle()

        assertEquals(1, catalogue.searchCalls)
        assertEquals("bag  ", vm.query.value)
    }

    @Test
    fun every_keystroke_is_written_through_to_the_saved_state_handle() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val vm = SearchViewModel(handle, SearchProducts(FakeCatalogueRepository()))

        vm.onQueryChange("ba")
        assertEquals("ba", handle.get<String>("q"))

        vm.onQueryChange("bag")
        assertEquals("bag", handle.get<String>("q"))
    }

    // ---- results ----------------------------------------------------------------

    /** AC-8: no matches is a successful search, not an error. */
    @Test
    fun a_search_that_matches_nothing_is_a_success_with_an_empty_state() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            search = ApiResult.Ok(searchResults(query = "kayak", products = emptyList()))
        }
        val vm = viewModel(catalogue = catalogue)

        vm.onQueryChange("kayak")
        testScheduler.advanceUntilIdle()

        val results = assertIs<SearchUiState.Results>(vm.state.value)
        assertTrue(results.showEmptyState)
        assertTrue(results.results.products.isEmpty())
    }

    @Test
    fun matches_are_results_rather_than_an_empty_state() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            search = ApiResult.Ok(searchResults(products = listOf(summary(1), summary(2))))
        }
        val vm = viewModel(catalogue = catalogue)

        vm.onQueryChange("bag")
        testScheduler.advanceUntilIdle()

        assertFalse(assertIs<SearchUiState.Results>(vm.state.value).showEmptyState)
    }

    /**
     * The screen quotes the **server's** term, not the field's.
     *
     * SPEC.md §4.4 matches against `description`, which no card shows, so a result can
     * appear to match nothing visible. Naming the term the server matched on is the only
     * thing that makes such a result legible — and it is the server's trimmed term, which
     * is why the state carries it rather than the ViewModel re-deriving it from the field.
     */
    @Test
    fun the_term_carried_into_state_is_the_one_the_server_echoed() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            search = ApiResult.Ok(searchResults(query = "sailcloth", products = listOf(summary(9))))
        }
        val vm = viewModel(catalogue = catalogue)

        vm.onQueryChange("  SAILCLOTH  ")
        testScheduler.advanceUntilIdle()

        assertEquals("sailcloth", assertIs<SearchUiState.Results>(vm.state.value).results.query)
    }

    /** The backend ranks; the client must not reorder, or §4.4's ranking rule is moot. */
    @Test
    fun results_keep_the_order_the_server_sent() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            search = ApiResult.Ok(
                searchResults(
                    products = listOf(summary(7, rate = 4.9), summary(3, rate = 2.1), summary(5, rate = 4.4)),
                ),
            )
        }
        val vm = viewModel(catalogue = catalogue)

        vm.onQueryChange("bag")
        testScheduler.advanceUntilIdle()

        val ids = assertIs<SearchUiState.Results>(vm.state.value).results.products.map { it.id }
        assertEquals(listOf(7, 3, 5), ids)
    }

    @Test
    fun a_search_shows_loading_while_it_is_in_flight() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply { searchLatencyMillis = 100 }
        val vm = viewModel(catalogue = catalogue)

        vm.onQueryChange("bag")
        testScheduler.advanceTimeBy(SearchDebounceMillis + 1)

        // Past the debounce, with the call still outstanding.
        assertIs<SearchUiState.Loading>(vm.state.value)

        testScheduler.advanceUntilIdle()
        assertIs<SearchUiState.Results>(vm.state.value)
    }

    // ---- failures ---------------------------------------------------------------

    @Test
    fun a_transport_failure_is_retryable() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            search = ApiResult.Fail(error(ErrorCode.NETWORK_UNAVAILABLE))
        }
        val vm = viewModel(catalogue = catalogue)

        vm.onQueryChange("bag")
        testScheduler.advanceUntilIdle()

        assertTrue(assertIs<SearchUiState.Failed>(vm.state.value).error.isRetryable)
    }

    @Test
    fun retry_runs_the_current_term_again_at_once() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            search = ApiResult.Fail(error(ErrorCode.UPSTREAM_UNAVAILABLE))
        }
        val vm = viewModel(catalogue = catalogue)

        vm.onQueryChange("bag")
        testScheduler.advanceUntilIdle()
        assertEquals(1, catalogue.searchCalls)

        catalogue.search = ApiResult.Ok(searchResults())
        vm.retry()
        // One virtual millisecond: a tap is not typing, so no debounce is served.
        testScheduler.advanceTimeBy(1)

        assertEquals(listOf("bag", "bag"), catalogue.searchQueries)
        assertIs<SearchUiState.Results>(vm.state.value)
    }

    @Test
    fun retry_below_the_minimum_sends_nothing() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository()
        val vm = viewModel(catalogue = catalogue)

        vm.onQueryChange("b")
        vm.retry()
        testScheduler.advanceUntilIdle()

        assertEquals(0, catalogue.searchCalls)
        assertIs<SearchUiState.BelowMinimum>(vm.state.value)
    }

    /**
     * SPEC.md §5.1: a field error is shown against its input, all input preserved, no
     * navigation.
     *
     * The client's own guard should make this unreachable — reaching it means the two
     * disagree about the rule, and the server's words are the ones worth showing.
     */
    @Test
    fun a_rejected_term_is_shown_in_the_servers_own_words_with_the_query_intact() =
        runTest(dispatcher) {
            val catalogue = FakeCatalogueRepository().apply {
                search = ApiResult.Fail(
                    DomainError(
                        code = ErrorCode.VALIDATION_FAILED,
                        userMessage = "Please check the highlighted fields.",
                        fieldErrors = mapOf("q" to "Enter at least 2 characters"),
                    ),
                )
            }
            val vm = viewModel(catalogue = catalogue)

            vm.onQueryChange("bag")
            testScheduler.advanceUntilIdle()

            val below = assertIs<SearchUiState.BelowMinimum>(vm.state.value)
            assertEquals("Enter at least 2 characters", below.message)
            assertEquals("bag", vm.query.value)
        }

    /** A `VALIDATION_FAILED` without a `q` error is not a field problem; it is a failure. */
    @Test
    fun a_validation_failure_naming_no_field_is_still_an_error_state() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            search = ApiResult.Fail(error(ErrorCode.VALIDATION_FAILED))
        }
        val vm = viewModel(catalogue = catalogue)

        vm.onQueryChange("bag")
        testScheduler.advanceUntilIdle()

        assertIs<SearchUiState.Failed>(vm.state.value)
    }

    // ---- abandoning -------------------------------------------------------------

    /**
     * Deleting back below the minimum has to abandon the search already in flight.
     *
     * Without the cancel, its results would land on a screen that stopped asking for
     * them — a grid appearing under a field holding one character.
     */
    @Test
    fun shortening_a_term_abandons_the_search_in_flight() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply { searchLatencyMillis = 100 }
        val vm = viewModel(catalogue = catalogue)

        vm.onQueryChange("bag")
        testScheduler.advanceTimeBy(SearchDebounceMillis + 1)
        assertIs<SearchUiState.Loading>(vm.state.value)

        vm.onQueryChange("b")
        testScheduler.advanceUntilIdle()

        assertIs<SearchUiState.BelowMinimum>(vm.state.value)
    }

    @Test
    fun retyping_within_the_debounce_abandons_the_earlier_term() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository()
        val vm = viewModel(catalogue = catalogue)

        vm.onQueryChange("bag")
        testScheduler.advanceTimeBy(SearchDebounceMillis - 50)
        vm.onQueryChange("jacket")
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("jacket"), catalogue.searchQueries)
    }

    // ---- returning to the term on screen ------------------------------------------

    /**
     * "bag" on screen, "jacket" typed, back to "bag" inside the debounce.
     *
     * The field ends where it started, so nothing new is needed — but the "jacket" search
     * queued on the way has to be abandoned. Left alone it fired 300ms later and drew
     * jacket results under a field reading "bag".
     */
    @Test
    fun returning_to_the_displayed_term_cancels_the_search_queued_in_between() =
        runTest(dispatcher) {
            val catalogue = FakeCatalogueRepository().apply {
                searchResponder = { term ->
                    val id = if (term == "jacket") 99 else 1
                    ApiResult.Ok(searchResults(query = term, products = listOf(summary(id))))
                }
            }
            val vm = viewModel(catalogue = catalogue)

            vm.onQueryChange("bag")
            testScheduler.advanceUntilIdle()
            assertEquals("bag", assertIs<SearchUiState.Results>(vm.state.value).results.query)

            vm.onQueryChange("jacket")
            testScheduler.advanceTimeBy(SearchDebounceMillis - 100)
            vm.onQueryChange("bag")
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("bag"), catalogue.searchQueries)
            val results = assertIs<SearchUiState.Results>(vm.state.value).results
            assertEquals("bag", results.query)
            assertEquals(listOf(1), results.products.map { it.id })
        }

    /** The same race once "jacket" is already in flight: its answer must never land. */
    @Test
    fun an_abandoned_search_in_flight_never_replaces_the_current_one() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            searchLatencyMillis = 100
            searchResponder = { term ->
                val id = if (term == "jacket") 99 else 1
                ApiResult.Ok(searchResults(query = term, products = listOf(summary(id))))
            }
        }
        val vm = viewModel(catalogue = catalogue)

        vm.onQueryChange("bag")
        testScheduler.advanceUntilIdle()

        vm.onQueryChange("jacket")
        testScheduler.advanceTimeBy(SearchDebounceMillis + 1)
        assertIs<SearchUiState.Loading>(vm.state.value)

        vm.onQueryChange("bag")
        testScheduler.advanceUntilIdle()

        val results = assertIs<SearchUiState.Results>(vm.state.value).results
        assertEquals("bag", results.query)
        assertEquals(listOf(1), results.products.map { it.id })
    }

    // ---- the Search button and keyboard action ------------------------------------

    @Test
    fun submit_searches_at_once_without_waiting_for_the_debounce() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository()
        val vm = viewModel(catalogue = catalogue)

        vm.onQueryChange("bag")
        vm.submit()
        testScheduler.advanceTimeBy(1)

        assertEquals(listOf("bag"), catalogue.searchQueries)
        assertIs<SearchUiState.Results>(vm.state.value)
    }

    /** The press replaces the queued search; it does not run beside it. */
    @Test
    fun submit_cancels_the_debounced_search_so_only_one_request_is_made() =
        runTest(dispatcher) {
            val catalogue = FakeCatalogueRepository()
            val vm = viewModel(catalogue = catalogue)

            vm.onQueryChange("bag")
            testScheduler.advanceTimeBy(SearchDebounceMillis - 100)
            vm.submit()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("bag"), catalogue.searchQueries)
        }

    @Test
    fun submit_below_the_minimum_sends_nothing() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository()
        val vm = viewModel(catalogue = catalogue)

        vm.onQueryChange(" b ")
        vm.submit()
        testScheduler.advanceUntilIdle()

        assertEquals(0, catalogue.searchCalls)
        assertIs<SearchUiState.BelowMinimum>(vm.state.value)
    }

    @Test
    fun submit_while_the_same_term_is_in_flight_does_not_send_it_again() =
        runTest(dispatcher) {
            val catalogue = FakeCatalogueRepository().apply { searchLatencyMillis = 100 }
            val vm = viewModel(catalogue = catalogue)

            vm.onQueryChange("bag")
            testScheduler.advanceTimeBy(SearchDebounceMillis + 1)
            vm.submit()
            vm.submit()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("bag"), catalogue.searchQueries)
            assertIs<SearchUiState.Results>(vm.state.value)
        }

    @Test
    fun submit_with_results_already_showing_for_the_term_sends_nothing() =
        runTest(dispatcher) {
            val catalogue = FakeCatalogueRepository()
            val vm = viewModel(catalogue = catalogue)

            vm.onQueryChange("bag")
            testScheduler.advanceUntilIdle()
            vm.onQueryChange("bag ")
            vm.submit()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("bag"), catalogue.searchQueries)
        }

    /** A press after a failure is a fresh attempt, the same as Retry. */
    @Test
    fun submit_after_a_failure_searches_again() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            search = ApiResult.Fail(error(ErrorCode.NETWORK_UNAVAILABLE))
        }
        val vm = viewModel(catalogue = catalogue)

        vm.onQueryChange("bag")
        testScheduler.advanceUntilIdle()
        catalogue.search = ApiResult.Ok(searchResults())
        vm.submit()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("bag", "bag"), catalogue.searchQueries)
        assertIs<SearchUiState.Results>(vm.state.value)
    }
}
