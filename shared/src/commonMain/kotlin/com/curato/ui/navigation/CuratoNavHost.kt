package com.curato.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.ktor.http.encodeURLParameter
import androidx.savedstate.read
import com.curato.presentation.category.CategoryViewModel
import com.curato.presentation.enquiry.EnquiryViewModel
import com.curato.presentation.home.HomeViewModel
import com.curato.presentation.product.ProductViewModel
import com.curato.presentation.search.SearchViewModel
import com.curato.ui.category.CategoryScreen
import com.curato.ui.enquiry.EnquiryScreen
import com.curato.ui.home.HomeScreen
import com.curato.ui.product.ProductScreen
import com.curato.ui.search.SearchScreen
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Routes (SPEC.md §8).
 *
 * **Primitives only** — ids, slugs and query strings, never objects. Every screen is
 * therefore addressable by URL alone, which is what makes deeplinks a later addition
 * rather than a refactor.
 */
object Routes {
    const val HOME = "home"
    const val SEARCH = "search?q={q}"
    const val CATEGORY = "category/{slug}"
    const val PRODUCT = "product/{id}"
    const val ENQUIRE = "product/{id}/enquire?seenPrice={seenPrice}"

    /**
     * The one route parameter a **customer** supplies rather than the backend.
     *
     * Encoded, unlike the slug and the id: those are backend-minted and known safe,
     * whereas a search term containing a space, `&` or `#` concatenated into a query
     * string would silently become a *different* route rather than fail. Ktor's encoder
     * is used rather than a hand-rolled one because the client already depends on it.
     */
    fun search(query: String = "") = "search?q=${query.encodeURLParameter()}"
    fun category(slug: String) = "category/$slug"
    fun product(id: Int) = "product/$id"

    /**
     * The price the customer was looking at when they tapped Enquire (SPEC.md §7.5).
     *
     * It rides the route rather than a shared object, so `product/{id}/enquire` stays
     * addressable by URL alone — a `Double` is as much a primitive as an id. **Optional
     * by construction:** a deeplink, or any caller that has no price to offer, simply
     * omits the parameter, and §6 already tolerates an absent `seenPrice` because it is
     * a diagnostic rather than an input.
     */
    fun enquire(id: Int, seenPrice: Double? = null): String =
        "product/$id/enquire" + (seenPrice?.let { "?seenPrice=$it" } ?: "")
}

@Composable
fun CuratoNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
    ) {
        composable(Routes.HOME) {
            val viewModel: HomeViewModel = koinViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            HomeScreen(
                state = state,
                onSearchClick = { navController.navigate(Routes.search()) },
                onProductClick = { id -> navController.navigate(Routes.product(id)) },
                onViewAllClick = { slug -> navController.navigate(Routes.category(slug)) },
                onRetryFeatured = viewModel::retryFeatured,
                onRetryCategories = viewModel::retryCategories,
                onRetryAll = viewModel::load,
            )
        }

        composable(
            route = Routes.CATEGORY,
            arguments = listOf(navArgument("slug") { type = NavType.StringType }),
        ) { entry ->
            // The route's primitive, read once and handed to the ViewModel as a plain
            // String. Nothing below this line knows a navigation library exists.
            val slug = entry.arguments?.read { getStringOrNull("slug") }.orEmpty()
            val viewModel: CategoryViewModel = koinViewModel { parametersOf(slug) }
            val state by viewModel.state.collectAsStateWithLifecycle()
            CategoryScreen(
                state = state,
                onProductClick = { id -> navController.navigate(Routes.product(id)) },
                onRetry = viewModel::load,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.PRODUCT,
            arguments = listOf(navArgument("id") { type = NavType.IntType }),
        ) { entry ->
            val id = entry.arguments?.read { getIntOrNull("id") } ?: 0
            val viewModel: ProductViewModel = koinViewModel { parametersOf(id) }
            val state by viewModel.state.collectAsStateWithLifecycle()
            ProductScreen(
                state = state,
                // The price travels with the navigation, captured from what was on
                // screen at the moment of the tap.
                onEnquire = { price -> navController.navigate(Routes.enquire(id, price)) },
                onRetry = viewModel::load,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.SEARCH,
            arguments = listOf(
                navArgument("q") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            // The only screen whose parameter is read from the entry's `SavedStateHandle`
            // rather than its arguments. Navigation seeds the handle *from* those
            // arguments, so `handle["q"]` is the route's term on first creation and the
            // typed term afterwards — and unlike a slug or an id, this one changes while
            // the screen is open, so the route stops being a record of it.
            val viewModel: SearchViewModel = koinViewModel {
                parametersOf(entry.savedStateHandle)
            }
            val query by viewModel.query.collectAsStateWithLifecycle()
            val state by viewModel.state.collectAsStateWithLifecycle()
            SearchScreen(
                query = query,
                state = state,
                onQueryChange = viewModel::onQueryChange,
                onProductClick = { id -> navController.navigate(Routes.product(id)) },
                onRetry = viewModel::retry,
                onBack = { navController.popBackStack() },
                // The empty state's copy points at "a category from home", and Home is
                // where the category shelves are — so it returns there rather than
                // inventing a categories screen the app does not have.
                onBrowseCategories = {
                    navController.popBackStack(Routes.HOME, inclusive = false)
                },
            )
        }

        composable(
            route = Routes.ENQUIRE,
            arguments = listOf(
                navArgument("id") { type = NavType.IntType },
                // A String, not a Float: a price is a Double, and rounding 109.95 through
                // a float to save four bytes on a route is how it becomes 109.94999.
                // Absent, blank and unparseable all collapse to null below.
                navArgument("seenPrice") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val id = entry.arguments?.read { getIntOrNull("id") } ?: 0
            // Unparseable is treated as absent rather than as an error: §3 and §6 make
            // `seenPrice` a diagnostic the backend never trusts, so a route carrying
            // nonsense must still produce a working enquiry — the email simply has no
            // drift note.
            val seenPrice = entry.arguments
                ?.read { getStringOrNull("seenPrice") }
                ?.toDoubleOrNull()

            // The id is a parameter and the draft lives in the handle — the only screen
            // that needs both, because its route parameter is fixed while its *contents*
            // are being typed (SPEC.md §7.6).
            val viewModel: EnquiryViewModel = koinViewModel {
                parametersOf(id, seenPrice, entry.savedStateHandle)
            }
            val state by viewModel.state.collectAsStateWithLifecycle()

            // Both exits pop, and that is the whole of AC-15: the thank-you is a *state*
            // of this route rather than a destination, so there is no submitted form left
            // on the stack for back to land on. The two callbacks differ only in the copy
            // of the control that triggers them.
            val backToProduct = { navController.popBackStack(); Unit }

            EnquiryScreen(
                state = state,
                onNameChange = viewModel::onNameChange,
                onEmailChange = viewModel::onEmailChange,
                onPhoneChange = viewModel::onPhoneChange,
                onMessageChange = viewModel::onMessageChange,
                onFieldBlur = viewModel::onFieldBlur,
                onConsentChange = viewModel::onConsentChange,
                onEditContact = viewModel::onEditContact,
                onSubmit = viewModel::submit,
                onRetryLoad = viewModel::load,
                onBackToProduct = backToProduct,
                onBack = backToProduct,
            )
        }
    }
}
