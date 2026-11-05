package com.curato.fakes

import com.curato.domain.ApiResult
import com.curato.domain.DomainError
import com.curato.domain.ErrorCode
import com.curato.domain.model.CategoryDetail
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

val Electronics = CategoryRef("electronics", "Electronics")
val MensClothing = CategoryRef("mens-clothing", "Men's Clothing")

fun summary(
    id: Int,
    rate: Double = 4.0,
    count: Int = 100,
    category: CategoryRef = Electronics,
) = ProductSummary(
    id = id,
    title = "Product $id",
    price = 9.99,
    currency = "USD",
    category = category,
    imageUrl = "https://example.test/$id.png",
    rating = Rating(rate, count),
)

/** One category in full. `additional` empty is the ≤5 case the second section omits. */
fun detail(
    category: CategoryRef = Electronics,
    topRated: List<ProductSummary> = listOf(summary(1, category = category)),
    additional: List<ProductSummary> = emptyList(),
) = CategoryDetail(category = category, topRated = topRated, additional = additional)

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

    var categoryDetail: ApiResult<CategoryDetail> = ApiResult.Ok(detail())

    var featuredCalls = 0
    var categoryCalls = 0
    var categoryDetailCalls = 0

    /** Recorded so a test can prove the route's slug arrives unmangled. */
    var lastSlug: String? = null

    override suspend fun getFeatured(): ApiResult<FeaturedShelf> {
        featuredCalls++
        return featured
    }

    override suspend fun getCategoryShelves(): ApiResult<List<CategoryShelf>> {
        categoryCalls++
        return categories
    }

    override suspend fun getCategory(slug: String): ApiResult<CategoryDetail> {
        categoryDetailCalls++
        lastSlug = slug
        return categoryDetail
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
