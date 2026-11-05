package com.curato.presentation.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.curato.domain.ApiResult
import com.curato.domain.DomainError
import com.curato.domain.ErrorCode
import com.curato.domain.usecase.GetCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Category (SPEC.md §7.3).
 *
 * The [slug] arrives as a plain constructor argument, taken from the route. Nothing is
 * read from a `SavedStateHandle`: the slug already lives in the back-stack entry, so
 * Navigation restores it across process death on its own, and keeping the constructor a
 * `String` is what lets the tests build this with no Koin and no Android at all.
 */
class CategoryViewModel(
    private val slug: String,
    private val getCategory: GetCategory,
) : ViewModel() {

    private val _state = MutableStateFlow<CategoryUiState>(CategoryUiState.Loading)
    val state: StateFlow<CategoryUiState> = _state.asStateFlow()

    init {
        load()
    }

    /** Also the Retry action — there is one call, so retrying it is loading it. */
    fun load() {
        if (slug.isBlank()) {
            // The route cannot match without a path segment, so this is a guard rather
            // than a path. It is here because the alternative is a request to
            // `/categories//products`, which the backend answers as an unknown *route* —
            // the same state, reached by a pointless round trip.
            _state.value = CategoryUiState.Failed(unknownCategory())
            return
        }

        viewModelScope.launch {
            _state.value = CategoryUiState.Loading
            _state.value = when (val result = getCategory(slug)) {
                is ApiResult.Ok -> CategoryUiState.Content(result.value)
                is ApiResult.Fail -> CategoryUiState.Failed(result.error)
            }
        }
    }
}

/**
 * The same shape the backend sends for an unresolvable slug.
 *
 * Synthesised locally rather than given its own state, so the screen has one error
 * branch. `NOT_FOUND` is non-retryable in `DomainError`, which is what suppresses the
 * Retry button without this code knowing the button exists.
 */
private fun unknownCategory() = DomainError(
    code = ErrorCode.NOT_FOUND,
    userMessage = "We could not find what you were looking for.",
)
