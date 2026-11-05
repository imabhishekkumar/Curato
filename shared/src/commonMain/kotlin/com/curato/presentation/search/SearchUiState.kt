package com.curato.presentation.search

import com.curato.domain.DomainError
import com.curato.domain.model.SearchResults
import com.curato.domain.usecase.MIN_QUERY_MESSAGE

/**
 * One search screen's state (SPEC.md §7.4).
 *
 * **Four states, and [BelowMinimum] is the interesting one.** It is not an error and not
 * an empty result: it is the screen saying no request has been made, because the term is
 * too short to send. The kit draws it as its own frame and gives it no action, and it
 * doubles as the pristine screen — arriving from Home with an empty field lands here.
 *
 * The field's text is deliberately **not** in here. What is typed and what is on screen
 * are independent: the kit's zero-results frame shows a term in the bar while the panel
 * quotes the term the *server* matched, and the customer can keep typing without the panel
 * changing until the next search lands. Folding the two together would make every
 * keystroke a new screen state.
 */
sealed interface SearchUiState {

    /**
     * Below two characters after trimming — no request made, nothing to show.
     *
     * [message] carries a default rather than being fixed, so a `VALIDATION_FAILED` the
     * backend somehow returns anyway can be shown in the server's own words. SPEC.md §5.1
     * requires a field error be shown against its input with all input preserved and no
     * navigation, and this is that, without a fifth state for a path the client's own
     * guard makes unreachable.
     */
    data class BelowMinimum(val message: String = MIN_QUERY_MESSAGE) : SearchUiState

    data object Loading : SearchUiState

    data class Results(val results: SearchResults) : SearchUiState {
        /**
         * Whether to draw the empty panel instead of the grid (AC-8).
         *
         * A search that matched nothing still *succeeded*, so it lives here rather than in
         * [Failed]. Named so the rule is one property a test asserts, instead of an
         * `isEmpty()` buried in a composable.
         */
        val showEmptyState: Boolean get() = results.products.isEmpty()
    }

    data class Failed(val error: DomainError) : SearchUiState
}
