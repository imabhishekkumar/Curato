package com.curato.presentation.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.curato.domain.ApiResult
import com.curato.domain.ErrorCode
import com.curato.domain.usecase.MIN_QUERY_LENGTH
import com.curato.domain.usecase.SearchProducts
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * How long the field sits still before a term is sent.
 *
 * `internal` so the test asserts against this number rather than its own copy of 300. The
 * value is the kit's own `emphasized` duration — long enough that "backpack" is one
 * request instead of eight, short enough that it reads as the screen keeping up.
 */
internal const val SearchDebounceMillis = 300L

/** The key the query is stored under. Also the route's parameter name, which is the point. */
private const val KEY_QUERY = "q"

/**
 * Search (SPEC.md §7.4).
 *
 * Results arrive as the term is typed, [SearchDebounceMillis] after the field stops
 * changing. There is no submit: the screen's only input is the field, and the minimum
 * length is enforced by declining to send rather than by greying out a button nobody has
 * to press.
 *
 * **The query lives in [handle], and that is a departure from the other two screens.**
 * Category takes its slug and Product its id as plain constructor arguments, with a
 * comment in each explaining that a `SavedStateHandle` would buy nothing — the route
 * already holds those values and Navigation restores them. Both remain true, because
 * both are immutable once the screen opens. This query is not: it changes with every
 * keystroke, so the route goes stale immediately and is no longer a record of anything.
 * `SavedStateHandle` is what SPEC.md §7.4 asks for and the only thing here that survives
 * process death.
 *
 * Reading it through `getStateFlow` means the field's text is persisted *by construction*
 * — there is no second copy held alongside it and nothing to forget to write. Navigation
 * seeds the handle from the route's arguments, so this is also where `search?q=bag` is
 * read, with no separate seed parameter to disagree with it.
 */
class SearchViewModel(
    private val handle: SavedStateHandle,
    private val searchProducts: SearchProducts,
) : ViewModel() {

    val query: StateFlow<String> = handle.getStateFlow(KEY_QUERY, "")

    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.BelowMinimum())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        // A restored or route-seeded term is already settled, so it is not debounced:
        // nobody is mid-keystroke, and 300ms of "Keep typing" in front of a search that
        // is certain to run is a flicker for nothing.
        search(query.value.trim(), debounced = false)
    }

    /** Called per keystroke. */
    fun onQueryChange(value: String) {
        handle[KEY_QUERY] = value

        val term = value.trim()

        // "bag" → "bag " is the same search. The server trims, so re-firing would spend a
        // request redrawing an identical grid for a keystroke that changed nothing.
        val alreadyShowing = (_state.value as? SearchUiState.Results)?.results?.query
        if (term == alreadyShowing) return

        search(term, debounced = true)
    }

    /**
     * Retry the term currently in the field, immediately.
     *
     * Not debounced: a tap is not typing. Below the minimum it does nothing, which cannot
     * happen from the UI — the failure panel that carries Retry is only reachable from a
     * term long enough to have been sent.
     */
    fun retry() = search(query.value.trim(), debounced = false)

    private fun search(term: String, debounced: Boolean) {
        // Always cancelled first, including on the way to [SearchUiState.BelowMinimum]:
        // deleting back down to one character has to abandon the search already in flight,
        // or its results would land on top of a screen that has stopped asking for them.
        searchJob?.cancel()

        if (term.length < MIN_QUERY_LENGTH) {
            _state.value = SearchUiState.BelowMinimum()
            return
        }

        searchJob = viewModelScope.launch {
            if (debounced) delay(SearchDebounceMillis)

            // Replaces whatever was on screen rather than leaving the old grid up. The
            // count row names the term it searched, so a populated grid sitting under a
            // field that now says something else is a screen telling a small lie.
            _state.value = SearchUiState.Loading

            _state.value = when (val result = searchProducts(term)) {
                is ApiResult.Ok -> SearchUiState.Results(result.value)

                is ApiResult.Fail -> {
                    val fieldError = result.error.fieldErrors[KEY_QUERY]
                    if (result.error.code == ErrorCode.VALIDATION_FAILED && fieldError != null) {
                        // The client's own guard should have stopped this, so reaching it
                        // means the two disagree about the rule. The server's words win,
                        // and the query is preserved (SPEC.md §5.1).
                        SearchUiState.BelowMinimum(fieldError)
                    } else {
                        SearchUiState.Failed(result.error)
                    }
                }
            }
        }
    }
}
