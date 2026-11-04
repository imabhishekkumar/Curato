package com.curato.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.curato.domain.ApiResult
import com.curato.domain.usecase.GetCategoryShelves
import com.curato.domain.usecase.GetFeaturedShelf
import com.curato.domain.usecase.ObserveRecentlyViewed
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch

/**
 * Home (SPEC.md §7.2).
 *
 * Two calls, fired in parallel, each owning its own sections. Neither waits on the
 * other, so one failing leaves the rest of the screen usable, and a Retry re-runs only
 * the call that failed.
 */
class HomeViewModel(
    private val getFeaturedShelf: GetFeaturedShelf,
    private val getCategoryShelves: GetCategoryShelves,
    observeRecentlyViewed: ObserveRecentlyViewed,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        // Device-local and instant: it paints before either network call lands.
        observeRecentlyViewed()
            .onEach { recent -> _state.update { it.copy(recentlyViewed = recent) } }
            .launchIn(viewModelScope)

        load()
    }

    /** Both sections, in parallel. */
    fun load() {
        viewModelScope.launch {
            _state.update {
                it.copy(featured = SectionState.Loading, categories = SectionState.Loading)
            }
            // `async` twice then await both: issued together rather than one after the
            // other, which is what makes Home one round trip deep instead of two.
            awaitAll(
                async { loadFeatured() },
                async { loadCategories() },
            )
        }
    }

    /** Re-runs only the featured call. The category shelves keep whatever they have. */
    fun retryFeatured() {
        viewModelScope.launch {
            _state.update { it.copy(featured = SectionState.Loading) }
            loadFeatured()
        }
    }

    /** Re-runs only the category call. */
    fun retryCategories() {
        viewModelScope.launch {
            _state.update { it.copy(categories = SectionState.Loading) }
            loadCategories()
        }
    }

    private suspend fun loadFeatured() {
        val next = when (val result = getFeaturedShelf()) {
            is ApiResult.Ok -> SectionState.Content(result.value)
            is ApiResult.Fail -> SectionState.Failed(result.error)
        }
        _state.update { it.copy(featured = next) }
    }

    private suspend fun loadCategories() {
        val next = when (val result = getCategoryShelves()) {
            is ApiResult.Ok -> SectionState.Content(result.value)
            is ApiResult.Fail -> SectionState.Failed(result.error)
        }
        _state.update { it.copy(categories = next) }
    }
}

/** `MutableStateFlow.update` without pulling in the whole `kotlinx.coroutines.flow` surface. */
private inline fun MutableStateFlow<HomeUiState>.update(block: (HomeUiState) -> HomeUiState) {
    value = block(value)
}
