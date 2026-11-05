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
