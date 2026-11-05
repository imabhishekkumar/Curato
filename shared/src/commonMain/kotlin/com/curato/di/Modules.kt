package com.curato.di

import com.curato.data.local.RecentlyViewedStore
import com.curato.data.remote.CuratoApi
import com.curato.data.remote.createHttpClient
import com.curato.data.repository.CatalogueRepositoryImpl
import com.curato.domain.repository.CatalogueRepository
import com.curato.domain.repository.RecentlyViewedRepository
import com.curato.domain.usecase.GetCategory
import com.curato.domain.usecase.GetCategoryShelves
import com.curato.domain.usecase.GetFeaturedShelf
import com.curato.domain.usecase.GetProduct
import com.curato.domain.usecase.ObserveRecentlyViewed
import com.curato.domain.usecase.RecordProductViewed
import com.curato.domain.usecase.SearchProducts
import com.curato.presentation.category.CategoryViewModel
import com.curato.presentation.home.HomeViewModel
import com.curato.presentation.product.ProductViewModel
import com.curato.presentation.search.SearchViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Named so a platform module can supply the two things only it knows: which HTTP engine
 * to use, and where on disk the preferences file lives.
 */
const val QUALIFIER_BASE_URL = "baseUrl"

/** Whether this build logs HTTP exchanges. Debug only. */
const val QUALIFIER_LOG_HTTP = "logHttp"

/**
 * Everything that is the same on every platform.
 *
 * The platform module supplies `HttpClient` (engine) and `DataStore`; this module turns
 * them into the API, the repositories, the use cases and the ViewModels.
 */
val sharedModule: Module = module {
    single { createHttpClient(engine = get(), logRequests = get(named = QUALIFIER_LOG_HTTP)) }
    single { CuratoApi(client = get(), baseUrl = get(named = QUALIFIER_BASE_URL)) }

    single<CatalogueRepository> { CatalogueRepositoryImpl(api = get()) }
    single<RecentlyViewedRepository> { RecentlyViewedStore(dataStore = get()) }

    factory { GetFeaturedShelf(repository = get()) }
    factory { GetCategoryShelves(repository = get()) }
    factory { GetCategory(repository = get()) }
    factory { GetProduct(repository = get()) }
    factory { SearchProducts(repository = get()) }
    factory { ObserveRecentlyViewed(repository = get()) }
    factory { RecordProductViewed(repository = get()) }

    viewModel {
        HomeViewModel(
            getFeaturedShelf = get(),
            getCategoryShelves = get(),
            observeRecentlyViewed = get(),
        )
    }

    // The slug comes from the route, so it is passed in rather than resolved. A
    // `SavedStateHandle` would buy nothing: the slug already lives in the back-stack
    // entry, and Navigation restores it across process death on its own.
    viewModel { params -> CategoryViewModel(slug = params.get(), getCategory = get()) }

    viewModel { params ->
        ProductViewModel(
            id = params.get(),
            getProduct = get(),
            recordProductViewed = get(),
        )
    }

    // The one ViewModel here that does take a `SavedStateHandle`. The two above hold route
    // parameters that cannot change while the screen is open, so the back-stack entry is
    // already their record; a search term changes with every keystroke, and the route it
    // arrived on stops describing it immediately. SPEC.md §7.4 names the mechanism.
    viewModel { params -> SearchViewModel(handle = params.get(), searchProducts = get()) }
}

private inline fun <reified T : Any> org.koin.core.scope.Scope.get(named: String): T =
    get(qualifier = org.koin.core.qualifier.named(named))
