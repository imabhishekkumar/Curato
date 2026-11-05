package com.curato

import com.curato.domain.ApiResult
import com.curato.domain.ErrorCode
import com.curato.domain.usecase.GetProduct
import com.curato.domain.usecase.RecordProductViewed
import com.curato.fakes.FakeCatalogueRepository
import com.curato.fakes.FakeRecentlyViewedRepository
import com.curato.fakes.error
import com.curato.fakes.product
import com.curato.presentation.product.ProductUiState
import com.curato.presentation.product.ProductViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Product's ViewModel (SPEC.md §7.5 — AC-9, AC-10, AC-11).
 *
 * The recording assertions matter more than they look: nothing in the app has ever
 * called `record()` before this screen existed, so Home's recently-viewed section has
 * never once appeared. These are the tests that hold that loop closed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    /** A fixed clock, so `viewedAt` is an assertion rather than something tolerated. */
    private val fixedNow = 1_700_000_000_000L

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        id: Int = 1,
        catalogue: FakeCatalogueRepository = FakeCatalogueRepository(),
        recentlyViewed: FakeRecentlyViewedRepository = FakeRecentlyViewedRepository(),
        now: Long = fixedNow,
    ) = ProductViewModel(
        id = id,
        getProduct = GetProduct(catalogue),
        recordProductViewed = RecordProductViewed(recentlyViewed),
        now = { now },
    )

    @Test
    fun starts_loading_then_shows_content() = runTest(dispatcher) {
        val vm = viewModel()

        assertIs<ProductUiState.Loading>(vm.state.value)

        testScheduler.advanceUntilIdle()
        val content = assertIs<ProductUiState.Content>(vm.state.value)
        assertEquals(1, content.product.id)
        // The one field no list endpoint carries.
        assertTrue(content.product.description.isNotBlank())
    }

    @Test
    fun the_id_reaches_the_repository_unchanged() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository()
        viewModel(id = 17, catalogue = catalogue)

        testScheduler.advanceUntilIdle()

        assertEquals(17, catalogue.lastProductId)
    }

    /** AC-11, and the loop that has never run before this screen. */
    @Test
    fun a_successful_load_records_the_product_as_viewed() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            product = ApiResult.Ok(product(7, price = 55.99))
        }
        val recentlyViewed = FakeRecentlyViewedRepository()
        viewModel(id = 7, catalogue = catalogue, recentlyViewed = recentlyViewed)

        testScheduler.advanceUntilIdle()

        val recorded = recentlyViewed.observe().first()
        assertEquals(listOf(7), recorded.map { it.id })
        assertEquals(55.99, recorded.single().price)
        assertEquals(fixedNow, recorded.single().viewedAt)
    }

    /** A product that could not be fetched has not been viewed. */
    @Test
    fun a_failed_load_records_nothing() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            product = ApiResult.Fail(error(ErrorCode.NOT_FOUND))
        }
        val recentlyViewed = FakeRecentlyViewedRepository()
        viewModel(catalogue = catalogue, recentlyViewed = recentlyViewed)

        testScheduler.advanceUntilIdle()

        assertTrue(recentlyViewed.observe().first().isEmpty())
    }

    /** AC-11's de-duplication: re-viewing moves the entry, it does not add one. */
    @Test
    fun re_viewing_a_product_keeps_one_entry_at_the_front() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository()
        val recentlyViewed = FakeRecentlyViewedRepository()

        catalogue.product = ApiResult.Ok(product(1))
        viewModel(id = 1, catalogue = catalogue, recentlyViewed = recentlyViewed)
        testScheduler.advanceUntilIdle()

        catalogue.product = ApiResult.Ok(product(2))
        viewModel(id = 2, catalogue = catalogue, recentlyViewed = recentlyViewed)
        testScheduler.advanceUntilIdle()

        catalogue.product = ApiResult.Ok(product(1))
        viewModel(id = 1, catalogue = catalogue, recentlyViewed = recentlyViewed)
        testScheduler.advanceUntilIdle()

        // Newest first, and 1 appears once rather than twice.
        assertEquals(listOf(1, 2), recentlyViewed.observe().first().map { it.id })
    }

    @Test
    fun an_unknown_id_is_not_found_and_offers_no_retry() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            product = ApiResult.Fail(error(ErrorCode.NOT_FOUND))
        }
        val vm = viewModel(id = 9999, catalogue = catalogue)

        testScheduler.advanceUntilIdle()

        val failed = assertIs<ProductUiState.Failed>(vm.state.value)
        assertEquals(ErrorCode.NOT_FOUND, failed.error.code)
        assertFalse(failed.error.isRetryable)
    }

    @Test
    fun a_transport_failure_is_retryable() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            product = ApiResult.Fail(error(ErrorCode.NETWORK_UNAVAILABLE))
        }
        val vm = viewModel(catalogue = catalogue)

        testScheduler.advanceUntilIdle()

        assertTrue(assertIs<ProductUiState.Failed>(vm.state.value).error.isRetryable)
    }

    @Test
    fun retry_runs_the_call_again() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            product = ApiResult.Fail(error(ErrorCode.UPSTREAM_UNAVAILABLE))
        }
        val vm = viewModel(catalogue = catalogue)
        testScheduler.advanceUntilIdle()
        assertEquals(1, catalogue.productCalls)

        catalogue.product = ApiResult.Ok(product(1))
        vm.load()
        testScheduler.advanceUntilIdle()

        assertEquals(2, catalogue.productCalls)
        assertIs<ProductUiState.Content>(vm.state.value)
    }

    /**
     * AC-10's precondition and the kit's disabled-bar rule, in one table.
     *
     * The bar is **present but disabled** while loading — composing it late would shift
     * the button under a thumb already moving toward it — and absent on failure, where
     * an Enquire has no subject.
     */
    @Test
    fun the_action_bar_is_present_unless_the_load_failed() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository()
        val vm = viewModel(catalogue = catalogue)

        with(vm.state.value) {
            assertTrue(showActionBar)
            assertFalse(canEnquire)
            assertNull(displayedPrice)
        }

        testScheduler.advanceUntilIdle()
        with(vm.state.value) {
            assertTrue(showActionBar)
            assertTrue(canEnquire)
        }

        val failing = FakeCatalogueRepository().apply {
            product = ApiResult.Fail(error(ErrorCode.NOT_FOUND))
        }
        val failed = viewModel(catalogue = failing)
        testScheduler.advanceUntilIdle()
        with(failed.state.value) {
            assertFalse(showActionBar)
            assertFalse(canEnquire)
            assertNull(displayedPrice)
        }
    }

    /** `seenPrice` is the price on screen — read from content, never re-fetched. */
    @Test
    fun the_displayed_price_comes_from_the_loaded_product() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            product = ApiResult.Ok(product(1, price = 109.95))
        }
        val vm = viewModel(catalogue = catalogue)

        testScheduler.advanceUntilIdle()

        assertEquals(109.95, vm.state.value.displayedPrice)
    }
}
