package com.curato

import com.curato.ui.navigation.Routes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Route building (SPEC.md §8).
 *
 * These are strings assembled by hand, and the failure mode is silent: a malformed
 * query would not crash, it would simply drop `seenPrice`, and the drift note would
 * quietly stop appearing in enquiry emails with nothing to show for it.
 */
class RoutesTest {

    @Test
    fun a_captured_price_rides_the_enquire_route() {
        assertEquals("product/1/enquire?seenPrice=55.99", Routes.enquire(1, 55.99))
    }

    /** A deeplink, or any caller with no price, simply omits it — §6 tolerates absence. */
    @Test
    fun an_absent_price_omits_the_query_entirely() {
        assertEquals("product/1/enquire", Routes.enquire(1))
        assertEquals("product/1/enquire", Routes.enquire(1, seenPrice = null))
    }

    /**
     * The pattern and the builder have to agree.
     *
     * Navigation matches by pattern, so a builder that drifts from `ENQUIRE` produces a
     * route nothing is registered for — a runtime failure no compiler catches.
     */
    @Test
    fun the_builder_matches_the_declared_pattern() {
        assertEquals("product/{id}/enquire?seenPrice={seenPrice}", Routes.ENQUIRE)
        assertTrue(Routes.enquire(1, 2.0).startsWith("product/1/enquire?seenPrice="))
        assertTrue(Routes.product(1) == "product/1")
    }

    /** A price must not be rounded on its way through a route. */
    @Test
    fun a_price_keeps_its_precision() {
        assertTrue(Routes.enquire(1, 109.95).endsWith("109.95"))
        assertTrue(Routes.enquire(1, 999.99).endsWith("999.99"))
    }
}
