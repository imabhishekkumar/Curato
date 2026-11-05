package com.curato.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The wire format (SPEC.md §3–§5).
 *
 * Kept separate from the domain model so the API can change shape without the change
 * reaching a screen. Every field here mirrors the backend contract exactly.
 */

/**
 * The fixed response wrapper every endpoint returns, success and failure alike.
 *
 * One generic type for the whole API, so there is a single place where a response
 * becomes a result — never per-endpoint error handling.
 */
@Serializable
data class EnvelopeDto<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ApiErrorDto? = null,
)

@Serializable
data class ApiErrorDto(
    val code: String,
    val userMessage: String,
    val developerMessage: String? = null,
    val traceId: String? = null,
    val fieldErrors: Map<String, String>? = null,
)

@Serializable
data class CategoryRefDto(val slug: String, val name: String)

@Serializable
data class RatingDto(val rate: Double, val count: Int)

@Serializable
data class ProductSummaryDto(
    val id: Int,
    val title: String,
    val price: Double,
    val currency: String,
    val category: CategoryRefDto,
    val imageUrl: String,
    val rating: RatingDto,
)

@Serializable
data class ProductDto(
    val id: Int,
    val title: String,
    val price: Double,
    val currency: String,
    val description: String,
    val category: CategoryRefDto,
    val imageUrl: String,
    val rating: RatingDto,
)

@Serializable
data class HomeDto(val featured: FeaturedDto)

@Serializable
data class FeaturedDto(
    /** `"computed"` or `"curated"`. Parsed leniently — see `FeaturedSource` mapping. */
    val source: String,
    val products: List<ProductSummaryDto>,
)

@Serializable
data class CategoriesDto(val categories: List<CategoryShelfDto>)

@Serializable
data class CategoryShelfDto(
    val category: CategoryRefDto,
    @SerialName("topRated") val topRated: List<ProductSummaryDto>,
)

/**
 * `GET /categories/{slug}/products` (SPEC.md §4.3) — one category's complete contents.
 *
 * `additional` defaults to empty because empty is a real, expected answer: a category of
 * five or fewer products has no remainder, and SPEC §7.3 omits the second section
 * entirely when that happens. `topRated` carries no default on purpose — a response
 * missing it is malformed rather than empty, and should fail the parse and surface as
 * `UNEXPECTED_RESPONSE` instead of rendering a category that looks legitimately bare.
 */
@Serializable
data class CategoryProductsDto(
    val category: CategoryRefDto,
    val topRated: List<ProductSummaryDto>,
    val additional: List<ProductSummaryDto> = emptyList(),
)

/**
 * `GET /products/search?q={query}` (SPEC.md §4.4).
 *
 * [query] is the term the **server** matched on, echoed back trimmed. It is not the same
 * string the customer typed, and the results screen quotes this one: §4.4 matches against
 * `description`, which `ProductSummary` does not carry, so a product can be returned for a
 * term that appears nowhere on its card. Naming the matched term is what makes that read
 * as a search rather than as a bug.
 *
 * [results] carries no default, for the reason [CategoryProductsDto.topRated] does not: an
 * empty list is a real and expected answer, but a *missing* key is a malformed response,
 * and it should fail the parse into `UNEXPECTED_RESPONSE` rather than render a convincing
 * "no products match".
 */
@Serializable
data class SearchResultsDto(
    val query: String,
    val results: List<ProductSummaryDto>,
)

/**
 * `POST /enquiries` request (SPEC.md §3) — the one body this client ever sends.
 *
 * [seenPrice] and [ContactDto.phone] are nullable with a `null` default, and the client's
 * `Json` is configured `explicitNulls = false`, so an absent optional is **omitted from
 * the body** rather than sent as `null`. That distinction is the contract: §6 says an
 * absent `seenPrice` is ignored rather than rejected, and the backend's own
 * `blankToUndefined` treats a missing key and an empty string alike — sending explicit
 * nulls would make the client assert an absence in a shape the schema never promised to
 * read.
 *
 * There is no `title` or `price` here on purpose. The backend resolves the product from
 * [productId] alone, so a caller cannot submit a real product with an invented price and
 * have it emailed to sales as fact.
 */
@Serializable
data class EnquiryRequestDto(
    val productId: Int,
    val seenPrice: Double? = null,
    val contact: ContactDto,
    val message: String? = null,
)

@Serializable
data class ContactDto(
    val name: String,
    val email: String,
    val phone: String? = null,
)

/**
 * The 2xx body (SPEC.md §4.6).
 *
 * One field, and no reference id: nothing is persisted server-side, so an id the customer
 * could not look up would be theatre. [acceptedAt] carries no default — a success with no
 * timestamp is a malformed response, not an unstamped one.
 */
@Serializable
data class EnquiryAcceptedDto(val acceptedAt: String)
