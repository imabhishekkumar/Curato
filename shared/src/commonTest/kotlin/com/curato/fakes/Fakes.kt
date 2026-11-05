package com.curato.fakes

import com.curato.domain.ApiResult
import com.curato.domain.DomainError
import com.curato.domain.ErrorCode
import com.curato.domain.model.CategoryDetail
import com.curato.domain.model.CategoryRef
import com.curato.domain.model.CategoryShelf
import com.curato.domain.model.FeaturedShelf
import com.curato.domain.model.FeaturedSource
import com.curato.domain.model.Product
import com.curato.domain.model.ProductSummary
import com.curato.domain.model.Rating
import com.curato.domain.model.RecentProduct
import com.curato.domain.model.SearchResults
import com.curato.domain.repository.CatalogueRepository
import com.curato.domain.repository.RecentlyViewedRepository
import kotlinx.coroutines.delay
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

/** The full product, the only shape carrying a description. */
fun product(
    id: Int,
    rate: Double = 4.0,
    count: Int = 100,
    price: Double = 9.99,
    category: CategoryRef = Electronics,
) = Product(
    id = id,
    title = "Product $id",
    price = price,
    currency = "USD",
    description = "A description of product $id.",
    category = category,
    imageUrl = "https://example.test/$id.png",
    rating = Rating(rate, count),
)

/** One search's answer. The echoed `query` is what the results screen quotes. */
fun searchResults(
    query: String = "bag",
    products: List<ProductSummary> = listOf(summary(1)),
) = SearchResults(query = query, products = products)

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
    var product: ApiResult<Product> = ApiResult.Ok(product(1))
    var search: ApiResult<SearchResults> = ApiResult.Ok(searchResults())

    var featuredCalls = 0
    var categoryCalls = 0
    var categoryDetailCalls = 0
    var productCalls = 0
    var searchCalls = 0

    /** Virtual milliseconds a search takes. See [searchProducts]. */
    var searchLatencyMillis: Long = 0

    /** Recorded so a test can prove the route's slug arrives unmangled. */
    var lastSlug: String? = null
    var lastProductId: Int? = null

    /**
     * Every term the repository was asked for, in order.
     *
     * A list rather than a single value, because the debounce's whole claim is about how
     * *many* searches a burst of typing produces — a `lastQuery` could not tell the
     * difference between one request and eight.
     */
    val searchQueries = mutableListOf<String>()

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

    override suspend fun getProduct(id: Int): ApiResult<Product> {
        productCalls++
        lastProductId = id
        return product
    }

    override suspend fun searchProducts(query: String): ApiResult<SearchResults> {
        searchCalls++
        searchQueries += query
        // Zero by default, so most tests are unaffected. Set it to put the call genuinely
        // in flight: with an instant fake there is no moment at which `Loading` is the
        // state, and the one thing the loading branch exists for could not be asserted.
        if (searchLatencyMillis > 0) delay(searchLatencyMillis)
        return search
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
