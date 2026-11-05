package com.curato.presentation.product

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.curato.domain.ApiResult
import com.curato.domain.model.Product
import com.curato.domain.model.RecentProduct
import com.curato.domain.usecase.GetProduct
import com.curato.domain.usecase.RecordProductViewed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Product (SPEC.md §7.5).
 *
 * Resolves its own data from [id] and nothing else — never a product object handed over
 * by whatever list opened it. That is what makes Home, Category, Search and a future
 * deeplink the same entry point.
 *
 * [now] is injected so a test can assert the recorded `viewedAt` rather than tolerate
 * it, the same reason the backend's cache takes a clock.
 */
class ProductViewModel(
    private val id: Int,
    private val getProduct: GetProduct,
    private val recordProductViewed: RecordProductViewed,
    private val now: () -> Long = ::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow<ProductUiState>(ProductUiState.Loading)
    val state: StateFlow<ProductUiState> = _state.asStateFlow()

    init {
        load()
    }

    /** Also the Retry action — one call, so retrying it is loading it. */
    fun load() {
        viewModelScope.launch {
            _state.value = ProductUiState.Loading
            when (val result = getProduct(id)) {
                is ApiResult.Ok -> {
                    _state.value = ProductUiState.Content(result.value)
                    // Recorded on a *successful load*, never on navigation: a product
                    // that could not be fetched has not been viewed, and writing a row
                    // for it would put a title on Home that the customer never saw.
                    recordProductViewed(result.value.toRecent(now()))
                }

                is ApiResult.Fail -> _state.value = ProductUiState.Failed(result.error)
            }
        }
    }
}

/**
 * The device-local snapshot (SPEC.md §9.1).
 *
 * Deliberately drops `rating` and `description`: the store is never revalidated, so it
 * holds only what a card draws and nothing that would go quietly stale behind a star.
 */
private fun Product.toRecent(viewedAt: Long) = RecentProduct(
    id = id,
    title = title,
    imageUrl = imageUrl,
    price = price,
    currency = currency,
    viewedAt = viewedAt,
)

/** Wall-clock milliseconds, from the Kotlin standard library rather than a platform API. */
@OptIn(ExperimentalTime::class)
internal fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
