package com.curato.data.repository

import com.curato.data.mapper.toDomain
import com.curato.data.remote.CuratoApi
import com.curato.domain.ApiResult
import com.curato.domain.map
import com.curato.domain.model.CategoryDetail
import com.curato.domain.model.CategoryShelf
import com.curato.domain.model.FeaturedShelf
import com.curato.domain.model.Product
import com.curato.domain.repository.CatalogueRepository

class CatalogueRepositoryImpl(
    private val api: CuratoApi,
) : CatalogueRepository {

    override suspend fun getFeatured(): ApiResult<FeaturedShelf> =
        api.getHome().map { it.toDomain() }

    override suspend fun getCategoryShelves(): ApiResult<List<CategoryShelf>> =
        api.getCategories().map { it.toDomain() }

    override suspend fun getCategory(slug: String): ApiResult<CategoryDetail> =
        api.getCategoryProducts(slug).map { it.toDomain() }

    override suspend fun getProduct(id: Int): ApiResult<Product> =
        api.getProduct(id).map { it.toDomain() }
}
