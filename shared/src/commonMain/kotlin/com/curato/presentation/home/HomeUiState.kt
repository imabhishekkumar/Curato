package com.curato.presentation.home

import com.curato.domain.DomainError
import com.curato.domain.model.CategoryShelf
import com.curato.domain.model.FeaturedShelf
import com.curato.domain.model.RecentProduct

/**
 * The state of one section of Home.
 *
 * Per section rather than per screen, because `/home` and `/categories` are fired in
 * parallel and either can fail while the rest of the screen stays usable (SPEC.md
 * §7.2, AC-26).
 */
sealed interface SectionState<out T> {
    data object Loading : SectionState<Nothing>
    data class Content<T>(val value: T) : SectionState<T>
    data class Failed(val error: DomainError) : SectionState<Nothing>
}

data class HomeUiState(
    /**
     * Device-local, so it is never Loading: it renders instantly with no network and is
     * visible while the rest of the screen shimmers.
     */
    val recentlyViewed: List<RecentProduct> = emptyList(),
    val featured: SectionState<FeaturedShelf> = SectionState.Loading,
    val categories: SectionState<List<CategoryShelf>> = SectionState.Loading,
) {
    /**
     * Both calls failed, so the screen shows one error and one Retry rather than two
     * panels saying the same thing.
     *
     * Derived, deliberately. Modelling it as a third state would re-couple the two
     * sections that the whole design went to trouble to keep independent.
     */
    val bothFailed: Boolean
        get() = featured is SectionState.Failed && categories is SectionState.Failed

    /** The error to show when the whole screen has failed. Either will do; they agree. */
    val screenError: DomainError?
        get() = (featured as? SectionState.Failed)?.error.takeIf { bothFailed }

    /** First run has no history, so the section is hidden entirely rather than empty. */
    val showRecentlyViewed: Boolean get() = recentlyViewed.isNotEmpty()
}
