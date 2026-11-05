package com.curato.domain.repository

import com.curato.domain.ApiResult
import com.curato.domain.model.CategoryDetail
import com.curato.domain.model.CategoryShelf
import com.curato.domain.model.FeaturedShelf

/**
 * The catalogue, behind an interface.
 *
 * Home's two calls are separate methods because they are separate requests fired in
 * parallel, each owning its own sections and each able to fail without the other.
 * Collapsing them into one `getHomeScreen()` would re-couple exactly what SPEC.md §7.2
 * went to trouble to separate.
 */
interface CatalogueRepository {
    suspend fun getFeatured(): ApiResult<FeaturedShelf>
    suspend fun getCategoryShelves(): ApiResult<List<CategoryShelf>>

    /**
     * One category in full, by slug (SPEC.md §4.3).
     *
     * An unknown slug is a `NOT_FOUND` failure rather than an empty success — resolution
     * is the backend's job, and a client that treated "no such category" as "a category
     * with nothing in it" would render a convincing blank page instead of an error.
     */
    suspend fun getCategory(slug: String): ApiResult<CategoryDetail>
}
