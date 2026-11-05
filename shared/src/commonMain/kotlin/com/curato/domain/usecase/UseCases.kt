package com.curato.domain.usecase

import com.curato.domain.ApiResult
import com.curato.domain.model.CategoryDetail
import com.curato.domain.model.CategoryShelf
import com.curato.domain.model.FeaturedShelf
import com.curato.domain.model.RecentProduct
import com.curato.domain.repository.CatalogueRepository
import com.curato.domain.repository.RecentlyViewedRepository
import kotlinx.coroutines.flow.Flow

/**
 * One call each, named for what the screen is asking for.
 *
 * Thin by design: they exist so a ViewModel depends on a verb rather than on a
 * repository surface, which is what keeps the ViewModel tests free of stubs for methods
 * the screen never calls.
 */

class GetFeaturedShelf(private val repository: CatalogueRepository) {
    suspend operator fun invoke(): ApiResult<FeaturedShelf> = repository.getFeatured()
}

class GetCategoryShelves(private val repository: CatalogueRepository) {
    suspend operator fun invoke(): ApiResult<List<CategoryShelf>> =
        repository.getCategoryShelves()
}

class GetCategory(private val repository: CatalogueRepository) {
    suspend operator fun invoke(slug: String): ApiResult<CategoryDetail> =
        repository.getCategory(slug)
}

class ObserveRecentlyViewed(private val repository: RecentlyViewedRepository) {
    operator fun invoke(): Flow<List<RecentProduct>> = repository.observe()
}

class RecordProductViewed(private val repository: RecentlyViewedRepository) {
    suspend operator fun invoke(product: RecentProduct) = repository.record(product)
}
