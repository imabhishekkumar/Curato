package com.curato.presentation.category

import com.curato.domain.DomainError
import com.curato.domain.model.CategoryDetail

/**
 * One category screen's state (SPEC.md §7.3).
 *
 * **Three screen-level states, not Home's per-section ones.** Home holds a `SectionState`
 * for each of its sections because it fires two independent calls and either can fail
 * alone. Category fires one call, and both its sections come out of the same response:
 * there is no state in which "Top rated" loaded while "More in" failed. Borrowing
 * `SectionState` here would model a situation that cannot arise, and would import a type
 * from `presentation.home` to do it.
 */
sealed interface CategoryUiState {

    data object Loading : CategoryUiState

    data class Content(val detail: CategoryDetail) : CategoryUiState {
        /**
         * Whether the "More in" section exists at all (AC-6).
         *
         * An empty `additional` omits the section outright — header, gap and all — rather
         * than rendering it with nothing in it. Named here so the rule is one property a
         * test can assert, instead of a condition buried in the composable.
         */
        val showAdditional: Boolean get() = detail.additional.isNotEmpty()
    }

    data class Failed(val error: DomainError) : CategoryUiState

    /**
     * The name for the app bar, or null when there is not a verified one.
     *
     * Derived rather than stored, which is what makes the no-flicker rule structural:
     * there is no field to populate early from the slug, so the bar cannot show a guess.
     */
    val title: String?
        get() = (this as? Content)?.detail?.category?.name
}
