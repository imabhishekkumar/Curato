package com.curato.presentation.product

import com.curato.domain.DomainError
import com.curato.domain.model.Product

/**
 * One product screen's state (SPEC.md §7.5).
 *
 * Three screen-level states, like Category and for the same reason: one call fills the
 * screen, so there is nothing for a per-section state to express.
 */
sealed interface ProductUiState {

    data object Loading : ProductUiState

    data class Content(val product: Product) : ProductUiState

    data class Failed(val error: DomainError) : ProductUiState

    /**
     * Whether the action bar is drawn at all.
     *
     * Present while loading — **disabled, but present** — because the kit is explicit
     * that composing it late "would shift the layout under a thumb already moving toward
     * it". Absent on failure, where an Enquire button would be an action with no subject.
     */
    val showActionBar: Boolean get() = this !is Failed

    /** Only a resolved product can be enquired about. */
    val canEnquire: Boolean get() = this is Content

    /**
     * The price on screen, and the only thing `seenPrice` may ever be (SPEC.md §7.5).
     *
     * Read from the displayed content rather than re-fetched, which is the entire point:
     * re-reading it when the enquiry resolves the product would compare the backend
     * against itself, always agree, and make the drift note in the email worthless.
     */
    val displayedPrice: Double? get() = (this as? Content)?.product?.price
}
