package com.curato

import com.curato.domain.ApiResult
import com.curato.domain.ErrorCode
import com.curato.domain.model.FeaturedShelf
import com.curato.domain.model.FeaturedSource
import com.curato.domain.usecase.GetCategoryShelves
import com.curato.domain.usecase.GetFeaturedShelf
import com.curato.domain.usecase.ObserveRecentlyViewed
import com.curato.fakes.FakeCatalogueRepository
import com.curato.fakes.FakeRecentlyViewedRepository
import com.curato.fakes.error
import com.curato.fakes.recent
import com.curato.fakes.summary
import com.curato.presentation.home.HomeViewModel
import com.curato.presentation.home.SectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Home's two calls (SPEC.md §7.2, AC-26).
 *
 * The behaviour worth protecting is independence: either call can fail without taking
 * the other's sections down, and a Retry re-runs only what failed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        catalogue: FakeCatalogueRepository = FakeCatalogueRepository(),
        recentlyViewed: FakeRecentlyViewedRepository = FakeRecentlyViewedRepository(),
    ) = HomeViewModel(
        getFeaturedShelf = GetFeaturedShelf(catalogue),
        getCategoryShelves = GetCategoryShelves(catalogue),
        observeRecentlyViewed = ObserveRecentlyViewed(recentlyViewed),
    )

    @Test
    fun starts_with_both_sections_loading() = runTest(dispatcher) {
        val state = viewModel().state.value
        assertIs<SectionState.Loading>(state.featured)
        assertIs<SectionState.Loading>(state.categories)
    }

    @Test
    fun both_succeeding_fills_both_sections() = runTest(dispatcher) {
        val vm = viewModel()
        testScheduler.advanceUntilIdle()
        val state = vm.state.value
        assertIs<SectionState.Content<*>>(state.featured)
        assertIs<SectionState.Content<*>>(state.categories)
        assertTrue(!state.bothFailed)
    }

    @Test
    fun featured_failing_leaves_the_category_shelves_usable() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            featured = ApiResult.Fail(error(ErrorCode.UPSTREAM_UNAVAILABLE))
        }
        val vm = viewModel(catalogue)
        testScheduler.advanceUntilIdle()

        val state = vm.state.value
        assertIs<SectionState.Failed>(state.featured)
        // The other half of the screen is unaffected — that is the point.
        assertIs<SectionState.Content<*>>(state.categories)
        assertTrue(!state.bothFailed)
    }

    @Test
    fun categories_failing_leaves_the_featured_shelf_usable() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            categories = ApiResult.Fail(error(ErrorCode.UPSTREAM_UNAVAILABLE))
        }
        val vm = viewModel(catalogue)
        testScheduler.advanceUntilIdle()

        val state = vm.state.value
        assertIs<SectionState.Content<*>>(state.featured)
        assertIs<SectionState.Failed>(state.categories)
        assertTrue(!state.bothFailed)
    }

    @Test
    fun both_failing_collapses_to_one_screen_error() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            featured = ApiResult.Fail(error(ErrorCode.UPSTREAM_UNAVAILABLE))
            categories = ApiResult.Fail(error(ErrorCode.UPSTREAM_UNAVAILABLE))
        }
        val vm = viewModel(catalogue)
        testScheduler.advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.bothFailed)
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, state.screenError?.code)
    }

    @Test
    fun retrying_one_section_does_not_re_run_the_other() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            featured = ApiResult.Fail(error(ErrorCode.UPSTREAM_UNAVAILABLE))
        }
        val vm = viewModel(catalogue)
        testScheduler.advanceUntilIdle()
        assertEquals(1, catalogue.featuredCalls)
        assertEquals(1, catalogue.categoryCalls)

        catalogue.featured = ApiResult.Ok(FeaturedShelf(FeaturedSource.COMPUTED, listOf(summary(2))))
        vm.retryFeatured()
        testScheduler.advanceUntilIdle()

        assertEquals(2, catalogue.featuredCalls)
        // Untouched: re-running a call that already succeeded would re-shimmer a
        // section the customer is looking at.
        assertEquals(1, catalogue.categoryCalls)
        assertIs<SectionState.Content<*>>(vm.state.value.featured)
    }

    @Test
    fun retrying_everything_re_runs_both() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository()
        val vm = viewModel(catalogue)
        testScheduler.advanceUntilIdle()

        vm.load()
        testScheduler.advanceUntilIdle()

        assertEquals(2, catalogue.featuredCalls)
        assertEquals(2, catalogue.categoryCalls)
    }

    @Test
    fun recently_viewed_is_hidden_on_first_run() = runTest(dispatcher) {
        val vm = viewModel()
        testScheduler.advanceUntilIdle()
        assertTrue(!vm.state.value.showRecentlyViewed)
        assertEquals(emptyList(), vm.state.value.recentlyViewed)
    }

    @Test
    fun recently_viewed_appears_once_a_product_has_been_opened() = runTest(dispatcher) {
        val store = FakeRecentlyViewedRepository(listOf(recent(3)))
        val vm = viewModel(recentlyViewed = store)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.showRecentlyViewed)
        assertEquals(listOf(3), vm.state.value.recentlyViewed.map { it.id })
    }

    @Test
    fun recently_viewed_does_not_wait_on_the_network() = runTest(dispatcher) {
        // It is device-local, so it is populated in the same state object that still
        // reports both network sections as Loading.
        val store = FakeRecentlyViewedRepository(listOf(recent(3)))
        val vm = viewModel(recentlyViewed = store)
        testScheduler.runCurrent()
        val state = vm.state.value
        assertEquals(listOf(3), state.recentlyViewed.map { it.id })
    }
}
