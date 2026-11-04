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
