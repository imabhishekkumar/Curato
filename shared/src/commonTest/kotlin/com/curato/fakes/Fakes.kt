package com.curato.fakes

import com.curato.domain.ApiResult
import com.curato.domain.DomainError
import com.curato.domain.ErrorCode
import com.curato.domain.model.CategoryDetail
import com.curato.domain.model.CategoryRef
import com.curato.domain.model.CategoryShelf
import com.curato.domain.model.Contact
import com.curato.domain.model.Enquiry
import com.curato.domain.model.EnquiryAccepted
import com.curato.domain.model.FeaturedShelf
import com.curato.domain.model.FeaturedSource
import com.curato.domain.model.Product
import com.curato.domain.model.ProductSummary
import com.curato.domain.model.Rating
import com.curato.domain.model.RecentProduct
import com.curato.domain.model.SearchResults
import com.curato.domain.repository.CatalogueRepository
import com.curato.domain.repository.EnquiryRepository
import com.curato.domain.repository.RecentlyViewedRepository
import com.curato.domain.repository.SavedContactRepository
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

fun contact(
    name: String = "Ada Lovelace",
    email: String = "ada@example.com",
    phone: String? = "+91 9876543210",
) = Contact(name = name, email = email, phone = phone)

/** A `DomainError` carrying field errors, for the 422 path. */
fun fieldError(vararg entries: Pair<String, String>) = DomainError(
    code = ErrorCode.VALIDATION_FAILED,
    userMessage = "Please check the highlighted fields.",
    fieldErrors = entries.toMap(),
)

/**
 * The enquiry endpoint, recording what it was asked to send.
 *
 * [enquiries] is a list rather than a `lastEnquiry` for the reason the search fake keeps
 * one: AC-18's claim is about **how many** submissions a double tap produces, and a
 * single-value field cannot tell one from two.
 */
class FakeEnquiryRepository : EnquiryRepository {
    var result: ApiResult<EnquiryAccepted> = ApiResult.Ok(EnquiryAccepted(AcceptedAtFixture))

    val enquiries = mutableListOf<Enquiry>()
    val calls: Int get() = enquiries.size

    /**
     * Virtual milliseconds a submission takes.
     *
     * Zero by default. Set it to put the call genuinely in flight: with an instant fake
     * there is no moment at which `submitting` is the state, so the disabled button and
     * the non-re-entrancy guard could not be observed at all.
     */
    var latencyMillis: Long = 0

    override suspend fun submit(enquiry: Enquiry): ApiResult<EnquiryAccepted> {
        enquiries += enquiry
        if (latencyMillis > 0) delay(latencyMillis)
        return result
    }
}

/** A fixed instant, so `acceptedAt` is an assertion rather than something tolerated. */
const val AcceptedAtFixture = "2026-11-05T10:15:30.000Z"

/**
 * The saved-contact store, in memory.
 *
 * A `MutableStateFlow` rather than a plain field, because the real store is observed and
 * the screen's collapse-on-clear behaviour depends on the emission, not on the write.
 */
class FakeSavedContactRepository(initial: Contact? = null) : SavedContactRepository {
    private val state = MutableStateFlow(initial)

    var saves = 0
        private set
    var clears = 0
        private set

    val saved: Contact? get() = state.value

    /** Set to make [save] throw, standing in for a full disk or a corrupt store. */
    var saveFailure: Exception? = null

    override fun observe(): Flow<Contact?> = state

    override suspend fun save(contact: Contact) {
        saves++
        saveFailure?.let { throw it }
        state.value = contact
    }

    override suspend fun clear() {
        clears++
        state.value = null
    }
}
