package com.curato.fakes

import com.curato.domain.ApiResult
import com.curato.domain.DomainError
import com.curato.domain.ErrorCode
import com.curato.domain.model.CategoryRef
import com.curato.domain.model.CategoryShelf
import com.curato.domain.model.FeaturedShelf
import com.curato.domain.model.FeaturedSource
import com.curato.domain.model.ProductSummary
import com.curato.domain.model.Rating
import com.curato.domain.model.RecentProduct
import com.curato.domain.repository.CatalogueRepository
import com.curato.domain.repository.RecentlyViewedRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

fun summary(id: Int, rate: Double = 4.0, count: Int = 100) = ProductSummary(
    id = id,
    title = "Product $id",
    price = 9.99,
    currency = "USD",
    category = CategoryRef("electronics", "Electronics"),
    imageUrl = "https://example.test/$id.png",
    rating = Rating(rate, count),
)

fun recent(id: Int, viewedAt: Long = id.toLong()) = RecentProduct(
    id = id,
    title = "Product $id",
    imageUrl = "https://example.test/$id.png",
    price = 9.99,
    currency = "USD",
    viewedAt = viewedAt,
)

fun error(code: ErrorCode) = DomainError(code, "something went wrong")

/**
 * A catalogue whose two calls can be made to succeed or fail independently — which is
 * the whole point of the Home design, so the fake has to be able to express it.
 */
class FakeCatalogueRepository : CatalogueRepository {
    var featured: ApiResult<FeaturedShelf> =
        ApiResult.Ok(FeaturedShelf(FeaturedSource.COMPUTED, listOf(summary(1))))
    var categories: ApiResult<List<CategoryShelf>> =
        ApiResult.Ok(listOf(CategoryShelf(CategoryRef("electronics", "Electronics"), listOf(summary(1)))))

    var featuredCalls = 0
    var categoryCalls = 0

    override suspend fun getFeatured(): ApiResult<FeaturedShelf> {
        featuredCalls++
        return featured
    }

    override suspend fun getCategoryShelves(): ApiResult<List<CategoryShelf>> {
        categoryCalls++
        return categories
    }
}

class FakeRecentlyViewedRepository(
    initial: List<RecentProduct> = emptyList(),
) : RecentlyViewedRepository {
    private val state = MutableStateFlow(initial)
    override fun observe(): Flow<List<RecentProduct>> = state
    override suspend fun record(product: RecentProduct) {
        state.value = listOf(product) + state.value.filterNot { it.id == product.id }
    }
    override suspend fun clear() { state.value = emptyList() }
}
