package com.curato.domain.model

/**
 * The domain model (SPEC.md §3).
 *
 * These mirror the API contract exactly. They are deliberately separate from the DTOs
 * in `data/remote/dto`: the wire format is allowed to change shape without the screens
 * learning about it.
 */

data class CategoryRef(
    /** URL-safe. The only form that ever appears in a route. */
    val slug: String,
    /** Display form. The client renders this and never derives it from the slug. */
    val name: String,
)

data class Rating(val rate: Double, val count: Int)

/** Everything a card renders. Returned by every endpoint that produces a list. */
data class ProductSummary(
    val id: Int,
    val title: String,
    val price: Double,
    val currency: String,
    val category: CategoryRef,
    val imageUrl: String,
    val rating: Rating,
)

/** `ProductSummary` plus `description`. Only `GET /products/{id}` returns one. */
data class Product(
    val id: Int,
    val title: String,
    val price: Double,
    val currency: String,
    val description: String,
    val category: CategoryRef,
    val imageUrl: String,
    val rating: Rating,
)

/**
 * Which rule produced the home shelf.
 *
 * The client renders "Top rated" for [COMPUTED] and "Featured" for [CURATED], branching
 * on this alone and never inspecting the products. A curated list is not a ranking —
 * `FEATURED_PRODUCT_IDS=8,4` resolves to the two lowest-rated products in the catalogue
 * — so a shelf headed "Top rated" containing one would be a lie on the first screen
 * anyone sees.
 */
enum class FeaturedSource { COMPUTED, CURATED }

data class FeaturedShelf(
    val source: FeaturedSource,
    val products: List<ProductSummary>,
)

data class CategoryShelf(
    val category: CategoryRef,
    val topRated: List<ProductSummary>,
)

/**
 * One category's complete contents (SPEC.md §4.3) — the Category screen's payload.
 *
 * Deliberately not [CategoryShelf]. A shelf is Home's shape: a category and the head of
 * its ranking, with the remainder left unfetched. This is the whole category split in
 * two, and [additional] being empty is the signal that the second section is omitted
 * rather than rendered bare.
 */
data class CategoryDetail(
    val category: CategoryRef,
    val topRated: List<ProductSummary>,
    val additional: List<ProductSummary>,
)
