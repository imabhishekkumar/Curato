package com.curato

import com.curato.domain.ApiResult
import com.curato.domain.ErrorCode
import com.curato.domain.usecase.GetCategory
import com.curato.fakes.Electronics
import com.curato.fakes.FakeCatalogueRepository
import com.curato.fakes.MensClothing
import com.curato.fakes.detail
import com.curato.fakes.error
import com.curato.fakes.summary
import com.curato.presentation.category.CategoryUiState
import com.curato.presentation.category.CategoryViewModel
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Category's ViewModel (SPEC.md §7.3 — AC-6, AC-6a, AC-6b, AC-6c).
 *
 * One call, so one screen-level state. What is worth pinning is the behaviour that fails
 * silently: the second section appearing when it should be omitted, a `NOT_FOUND` offering
 * a Retry that cannot work, and the slug being mangled on its way out of the route.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        slug: String = "electronics",
        catalogue: FakeCatalogueRepository = FakeCatalogueRepository(),
    ) = CategoryViewModel(slug = slug, getCategory = GetCategory(catalogue))

    @Test
    fun starts_loading_then_shows_content() = runTest(dispatcher) {
        val vm = viewModel()

        assertIs<CategoryUiState.Loading>(vm.state.value)

        testScheduler.advanceUntilIdle()
        assertIs<CategoryUiState.Content>(vm.state.value)
    }

    @Test
    fun the_slug_reaches_the_repository_unchanged() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository()
        viewModel(slug = "mens-clothing", catalogue = catalogue)

        testScheduler.advanceUntilIdle()

        assertEquals("mens-clothing", catalogue.lastSlug)
    }

    /** AC-6: a category of five or fewer has no remainder, so no second section. */
    @Test
    fun an_empty_additional_omits_the_second_section() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            categoryDetail = ApiResult.Ok(
                detail(topRated = (1..4).map { summary(it) }, additional = emptyList()),
            )
        }
        val vm = viewModel(catalogue = catalogue)

        testScheduler.advanceUntilIdle()

        val content = assertIs<CategoryUiState.Content>(vm.state.value)
        assertFalse(content.showAdditional)
        assertEquals(4, content.detail.topRated.size)
    }

    /** AC-6a: above five, the remainder is its own section and the two stay disjoint. */
    @Test
    fun a_remainder_becomes_the_second_section_and_the_two_are_disjoint() =
        runTest(dispatcher) {
            val catalogue = FakeCatalogueRepository().apply {
                categoryDetail = ApiResult.Ok(
                    detail(
                        topRated = (1..5).map { summary(it) },
                        additional = listOf(summary(6)),
                    ),
                )
            }
            val vm = viewModel(catalogue = catalogue)

            testScheduler.advanceUntilIdle()

            val content = assertIs<CategoryUiState.Content>(vm.state.value)
            assertTrue(content.showAdditional)

            val top = content.detail.topRated.map { it.id }
            val more = content.detail.additional.map { it.id }
            assertEquals(5, top.size)
            assertEquals(listOf(6), more)
            assertTrue(top.intersect(more.toSet()).isEmpty())
            assertEquals(6, (top + more).distinct().size)
        }

    /** AC-6b: the screen renders what the response carried, all of it one category. */
    @Test
    fun every_product_belongs_to_the_requested_category() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            categoryDetail = ApiResult.Ok(
                detail(
                    category = MensClothing,
                    topRated = (1..5).map { summary(it, category = MensClothing) },
                    additional = listOf(summary(6, category = MensClothing)),
                ),
            )
        }
        val vm = viewModel(slug = "mens-clothing", catalogue = catalogue)

        testScheduler.advanceUntilIdle()

        val content = assertIs<CategoryUiState.Content>(vm.state.value)
        val shown = content.detail.topRated + content.detail.additional
        assertTrue(shown.all { it.category.slug == "mens-clothing" })
    }

    @Test
    fun a_failure_becomes_a_failed_state_carrying_the_code() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            categoryDetail = ApiResult.Fail(error(ErrorCode.UPSTREAM_UNAVAILABLE))
        }
        val vm = viewModel(catalogue = catalogue)

        testScheduler.advanceUntilIdle()

        val failed = assertIs<CategoryUiState.Failed>(vm.state.value)
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, failed.error.code)
        // Retryable, so the screen offers a Retry alongside Back.
        assertTrue(failed.error.isRetryable)
    }

    /**
     * AC-6c: an unknown slug is `NOT_FOUND`, and it is terminal.
     *
     * The second assertion is the one that matters on screen — the Retry button's own
     * condition. A retryable `NOT_FOUND` would offer a button that cannot ever succeed.
     */
    @Test
    fun an_unknown_slug_is_not_found_and_offers_no_retry() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            categoryDetail = ApiResult.Fail(error(ErrorCode.NOT_FOUND))
        }
        val vm = viewModel(slug = "groceries", catalogue = catalogue)

        testScheduler.advanceUntilIdle()

        val failed = assertIs<CategoryUiState.Failed>(vm.state.value)
        assertEquals(ErrorCode.NOT_FOUND, failed.error.code)
        assertFalse(failed.error.isRetryable)
    }

    @Test
    fun retry_runs_the_call_again() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            categoryDetail = ApiResult.Fail(error(ErrorCode.UPSTREAM_UNAVAILABLE))
        }
        val vm = viewModel(catalogue = catalogue)
        testScheduler.advanceUntilIdle()
        assertEquals(1, catalogue.categoryDetailCalls)

        catalogue.categoryDetail = ApiResult.Ok(detail())
        vm.load()
        testScheduler.advanceUntilIdle()

        assertEquals(2, catalogue.categoryDetailCalls)
        assertIs<CategoryUiState.Content>(vm.state.value)
    }

    /** A route that cannot resolve does not get a round trip to prove it. */
    @Test
    fun a_blank_slug_fails_without_a_request() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository()
        val vm = viewModel(slug = "", catalogue = catalogue)

        testScheduler.advanceUntilIdle()

        val failed = assertIs<CategoryUiState.Failed>(vm.state.value)
        assertEquals(ErrorCode.NOT_FOUND, failed.error.code)
        assertEquals(0, catalogue.categoryDetailCalls)
    }

    /**
     * The title is the response's, or nothing.
     *
     * SPEC §7.3 forbids deriving it from the slug. Deriving it is only *possible* if the
     * state has somewhere to put a guess, so the test asserts the absence: null until
     * content lands, and null again on failure.
     */
    @Test
    fun the_title_is_null_until_content_names_the_category() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            categoryDetail = ApiResult.Ok(detail(category = MensClothing))
        }
        val vm = viewModel(slug = "mens-clothing", catalogue = catalogue)

        assertNull(vm.state.value.title)

        testScheduler.advanceUntilIdle()

        // From the response, apostrophe and all — not title-cased from "mens-clothing".
        assertEquals("Men's Clothing", vm.state.value.title)
    }

    @Test
    fun a_failed_state_has_no_title() = runTest(dispatcher) {
        val catalogue = FakeCatalogueRepository().apply {
            categoryDetail = ApiResult.Fail(error(ErrorCode.NOT_FOUND))
        }
        val vm = viewModel(slug = "groceries", catalogue = catalogue)

        testScheduler.advanceUntilIdle()

        assertIs<CategoryUiState.Failed>(vm.state.value)
        assertNull(vm.state.value.title)
    }

    @Test
    fun content_carries_the_category_the_backend_named() = runTest(dispatcher) {
        val vm = viewModel(catalogue = FakeCatalogueRepository())

        testScheduler.advanceUntilIdle()

        val content = assertIs<CategoryUiState.Content>(vm.state.value)
        assertEquals(Electronics, content.detail.category)
    }
}
