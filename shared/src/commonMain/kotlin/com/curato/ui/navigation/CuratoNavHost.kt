package com.curato.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.savedstate.read
import com.curato.presentation.category.CategoryViewModel
import com.curato.presentation.home.HomeViewModel
import com.curato.ui.category.CategoryScreen
import com.curato.ui.designsystem.StatePanel
import com.curato.ui.home.HomeScreen
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
    const val ENQUIRE = "product/{id}/enquire"

    fun search(query: String = "") = "search?q=$query"
    fun category(slug: String) = "category/$slug"
    fun product(id: Int) = "product/$id"
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

        // The remaining three routes are declared with their parameter types so Home's
        // taps navigate for real rather than into nothing. Each is replaced by its
        // screen in a later pass.
        composable(
            route = Routes.SEARCH,
            arguments = listOf(
                navArgument("q") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            NotBuiltYet("Search", entry.arguments?.read { getStringOrNull("q") })
        }

        composable(
            route = Routes.PRODUCT,
            arguments = listOf(navArgument("id") { type = NavType.IntType }),
        ) { entry ->
            NotBuiltYet("Product", entry.arguments?.read { getIntOrNull("id") }?.toString())
        }

        composable(
            route = Routes.ENQUIRE,
            arguments = listOf(navArgument("id") { type = NavType.IntType }),
        ) { entry ->
            NotBuiltYet("Enquiry", entry.arguments?.read { getIntOrNull("id") }?.toString())
        }
    }
}

/**
 * A placeholder that is honest about being one.
 *
 * It shows the route's parameter, which makes it a working check that routes really do
 * carry primitives and that Home is navigating with the right ones.
 */
@Composable
private fun NotBuiltYet(screen: String, argument: String?) {
    Box(modifier = Modifier.fillMaxSize()) {
        StatePanel(
            glyph = "🚧",
            title = "$screen is not built yet",
            body = argument?.let { "This route resolved with: $it" }
                ?: "This route carries no parameter.",
        )
    }
}
