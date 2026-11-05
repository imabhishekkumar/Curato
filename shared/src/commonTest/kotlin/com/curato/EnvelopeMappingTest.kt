package com.curato

import com.curato.data.remote.CuratoApi
import com.curato.domain.ApiResult
import com.curato.domain.ErrorCode
import com.curato.domain.usecase.MIN_QUERY_MESSAGE
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import com.curato.data.remote.createHttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Envelope → result (SPEC.md §5).
 *
 * One parser serves every endpoint, so these cases are the whole client's error
 * behaviour rather than one screen's.
 */
class EnvelopeMappingTest {

    private fun apiReturning(body: String, status: HttpStatusCode = HttpStatusCode.OK): CuratoApi {
        val engine = MockEngine {
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return CuratoApi(createHttpClient(engine), "http://test.local")
    }

    /** The same, but handing back the URL the API actually requested. */
    private fun apiRecordingUrl(body: String, recorded: MutableList<String>): CuratoApi {
        val engine = MockEngine { request ->
            recorded += request.url.toString()
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return CuratoApi(createHttpClient(engine), "http://test.local")
    }

    @Test
    fun success_envelope_yields_ok() = runTest {
        val api = apiReturning(
            """{"success":true,"error":null,"data":{"featured":{"source":"computed","products":[]}}}""",
        )
        val result = api.getHome()
        assertTrue(result is ApiResult.Ok)
        assertEquals("computed", result.value.featured.source)
    }

    /**
     * §4.3's payload, including an `additional` that came back empty.
     *
     * Empty and absent are both legitimate here, and both have to arrive as an empty
     * list rather than as null — the screen branches on `isNotEmpty()` to decide whether
     * the second section exists at all, so a null would be a crash on the ≤5 case, which
     * is three of the four real categories.
     */
    @Test
    fun a_category_payload_decodes_with_an_empty_additional() = runTest {
        val api = apiReturning(
            """{"success":true,"error":null,"data":{"category":{"slug":"jewelery","name":"Jewelery"},"topRated":[{"id":5,"title":"Naga Bracelet","price":695.0,"currency":"USD","category":{"slug":"jewelery","name":"Jewelery"},"imageUrl":"https://example.test/5.png","rating":{"rate":4.6,"count":400}}],"additional":[]}}""",
        )
        val result = api.getCategoryProducts("jewelery")
        assertTrue(result is ApiResult.Ok)
        assertEquals("Jewelery", result.value.category.name)
        assertEquals(listOf(5), result.value.topRated.map { it.id })
        assertTrue(result.value.additional.isEmpty())
    }

    /** The key missing entirely behaves the same way, rather than failing the parse. */
    @Test
    fun a_category_payload_tolerates_additional_being_absent() = runTest {
        val api = apiReturning(
            """{"success":true,"error":null,"data":{"category":{"slug":"jewelery","name":"Jewelery"},"topRated":[]}}""",
        )
        val result = api.getCategoryProducts("jewelery")
        assertTrue(result is ApiResult.Ok)
        assertTrue(result.value.additional.isEmpty())
    }

    /**
     * §4.5's payload, the only one carrying `description`.
     *
     * No list endpoint sends that field, so this decode is the single place a missing
     * key could go unnoticed — every other screen would look identical without it.
     */
    @Test
    fun a_product_payload_decodes_with_its_description() = runTest {
        val api = apiReturning(
            """{"success":true,"error":null,"data":{"id":1,"title":"Fjallraven Backpack","price":109.95,"currency":"USD","description":"Your perfect pack for everyday use.","category":{"slug":"mens-clothing","name":"Men's Clothing"},"imageUrl":"https://example.test/1.png","rating":{"rate":3.9,"count":120}}}""",
        )
        val result = api.getProduct(1)
        assertTrue(result is ApiResult.Ok)
        assertEquals("Your perfect pack for everyday use.", result.value.description)
        assertEquals(109.95, result.value.price)
        assertEquals("Men's Clothing", result.value.category.name)
    }

    /**
     * A non-numeric id is `NOT_FOUND`, not a validation failure.
     *
     * That is the backend's deliberate choice — resolution is not validation, and there
     * is no field for the customer to retype — so the client must render it as a
     * terminal "gone" rather than offering a Retry.
     */
    @Test
    fun a_non_numeric_product_id_maps_to_a_terminal_not_found() = runTest {
        val api = apiReturning(
            """{"success":false,"data":null,"error":{"code":"NOT_FOUND","userMessage":"We could not find what you were looking for.","developerMessage":"product id is not a positive integer"}}""",
            HttpStatusCode.NotFound,
        )
        val result = api.getProduct(0)
        assertTrue(result is ApiResult.Fail)
        assertEquals(ErrorCode.NOT_FOUND, result.error.code)
        assertFalse(result.error.isRetryable)
    }

    /** AC-6c: an unknown slug is an error envelope, and a non-retryable one. */
    @Test
    fun an_unknown_slug_maps_to_a_terminal_not_found() = runTest {
        val api = apiReturning(
            """{"success":false,"data":null,"error":{"code":"NOT_FOUND","userMessage":"We could not find what you were looking for.","developerMessage":"unknown category slug"}}""",
            HttpStatusCode.NotFound,
        )
        val result = api.getCategoryProducts("groceries")
        assertTrue(result is ApiResult.Fail)
        assertEquals(ErrorCode.NOT_FOUND, result.error.code)
        assertFalse(result.error.isRetryable)
    }

    @Test
    fun error_envelope_yields_the_code_it_names() = runTest {
        val api = apiReturning(
            """{"success":false,"data":null,"error":{"code":"UPSTREAM_UNAVAILABLE","userMessage":"Catalogue unavailable"}}""",
            HttpStatusCode.ServiceUnavailable,
        )
        val result = api.getHome()
        assertTrue(result is ApiResult.Fail)
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, result.error.code)
        assertEquals("Catalogue unavailable", result.error.userMessage)
    }

    @Test
    fun field_errors_survive_as_a_map() = runTest {
        val api = apiReturning(
            """{"success":false,"data":null,"error":{"code":"VALIDATION_FAILED","userMessage":"Check the fields","fieldErrors":{"contact.email":"Enter a valid email address"}}}""",
            HttpStatusCode.UnprocessableEntity,
        )
        val result = api.getHome()
        assertTrue(result is ApiResult.Fail)
        assertEquals("Enter a valid email address", result.error.fieldErrors["contact.email"])
    }

    @Test
    fun a_code_this_build_does_not_know_degrades_rather_than_crashing() = runTest {
        // The backend may add codes before the client is rebuilt. That must be a generic
        // error, not a parse failure.
        val api = apiReturning(
            """{"success":false,"data":null,"error":{"code":"TEAPOT_ON_FIRE","userMessage":"Something went wrong"}}""",
            HttpStatusCode.BadRequest,
        )
        val result = api.getHome()
        assertTrue(result is ApiResult.Fail)
        assertEquals(ErrorCode.UNKNOWN, result.error.code)
        assertEquals("Something went wrong", result.error.userMessage)
    }

    @Test
    fun developerMessage_is_dropped_at_the_boundary() = runTest {
        val api = apiReturning(
            """{"success":false,"data":null,"error":{"code":"INTERNAL_ERROR","userMessage":"Oops","developerMessage":"unhandled error","traceId":"01JBQ7F3K2"}}""",
            HttpStatusCode.InternalServerError,
        )
        val result = api.getHome()
        assertTrue(result is ApiResult.Fail)
        // There is nowhere for it to go: the domain error has no such field, so no layer
        // above can render it by accident.
        assertEquals("01JBQ7F3K2", result.error.traceId)
        assertEquals(ErrorCode.INTERNAL_ERROR, result.error.code)
    }

    @Test
    fun traceId_is_kept_only_for_internal_errors() = runTest {
        val api = apiReturning(
            """{"success":false,"data":null,"error":{"code":"NOT_FOUND","userMessage":"Gone","traceId":"01JBQ7F3K2"}}""",
            HttpStatusCode.NotFound,
        )
        val result = api.getHome()
        assertTrue(result is ApiResult.Fail)
        assertEquals(null, result.error.traceId)
    }

    @Test
    fun a_body_that_is_not_an_envelope_is_UNEXPECTED_RESPONSE() = runTest {
        val api = apiReturning("<html><body>502 Bad Gateway</body></html>")
        val result = api.getHome()
        assertTrue(result is ApiResult.Fail)
        assertEquals(ErrorCode.UNEXPECTED_RESPONSE, result.error.code)
    }

    @Test
    fun a_success_envelope_with_no_data_is_UNEXPECTED_RESPONSE() = runTest {
        val api = apiReturning("""{"success":true,"data":null,"error":null}""")
        val result = api.getHome()
        assertTrue(result is ApiResult.Fail)
        assertEquals(ErrorCode.UNEXPECTED_RESPONSE, result.error.code)
    }

    /**
     * §4.4's payload. The echoed `query` is the field the screen quotes.
     *
     * It is not decoration: §4.4 matches against `description`, which `ProductSummary`
     * does not carry, so a result can come back for a term that appears nowhere on its
     * card. Dropping the echo here would leave the empty state and the count row with
     * nothing true to say.
     */
    @Test
    fun a_search_payload_decodes_with_the_term_the_server_matched() = runTest {
        val api = apiReturning(
            """{"success":true,"error":null,"data":{"query":"bag","results":[{"id":1,"title":"Fjallraven Foldsack No. 1 Backpack","price":109.95,"currency":"USD","category":{"slug":"mens-clothing","name":"Men's Clothing"},"imageUrl":"https://example.test/1.png","rating":{"rate":3.9,"count":120}}]}}""",
        )
        val result = api.searchProducts("  bag  ")
        assertTrue(result is ApiResult.Ok)
        assertEquals("bag", result.value.query)
        assertEquals(listOf(1), result.value.results.map { it.id })
    }

    /** AC-8 at the wire: no matches is a populated 200 with nothing in it. */
    @Test
    fun a_search_that_matched_nothing_is_a_success_with_an_empty_list() = runTest {
        val api = apiReturning("""{"success":true,"error":null,"data":{"query":"kayak","results":[]}}""")
        val result = api.searchProducts("kayak")
        assertTrue(result is ApiResult.Ok)
        assertEquals("kayak", result.value.query)
        assertTrue(result.value.results.isEmpty())
    }

    /**
     * The client's below-minimum copy is the server's `fieldErrors.q`, not a near-copy.
     *
     * The Screen Kit claims the two "never disagree". This is what makes that mechanically
     * true: if the backend ever reworded the message, this fixture and
     * [MIN_QUERY_MESSAGE] would stop matching and the test would say so.
     */
    @Test
    fun a_rejected_term_carries_the_exact_message_the_client_mirrors() = runTest {
        val api = apiReturning(
            """{"success":false,"data":null,"error":{"code":"VALIDATION_FAILED","userMessage":"Please check the highlighted fields.","developerMessage":"search query failed validation: q","traceId":"01JBQ7F3K2","fieldErrors":{"q":"Enter at least 2 characters"}}}""",
            HttpStatusCode.UnprocessableEntity,
        )
        val result = api.searchProducts("a")
        assertTrue(result is ApiResult.Fail)
        assertEquals(ErrorCode.VALIDATION_FAILED, result.error.code)
        assertEquals(MIN_QUERY_MESSAGE, result.error.fieldErrors["q"])
        assertFalse(result.error.isRetryable)
    }

    /**
     * The path and the encoding, pinned together.
     *
     * `/products/search` and `/products/{id}` overlap, and the backend warns that the
     * literal `search` is parsed as a product id unless the routes are ordered. The client
     * side of that is sending the segments separately rather than assembling a path by
     * hand — and sending the term as a parameter, so a space does not split the URL.
     */
    @Test
    fun the_request_goes_to_products_search_with_the_term_encoded() = runTest {
        val urls = mutableListOf<String>()
        val api = apiRecordingUrl(
            """{"success":true,"error":null,"data":{"query":"winter jacket","results":[]}}""",
            urls,
        )
        api.searchProducts("winter & jacket")

        val url = urls.single()
        assertTrue(url.startsWith("http://test.local/products/search?"), url)
        assertTrue(url.contains("q=winter"), url)
        // The `&` is escaped rather than starting a second parameter.
        assertFalse(url.substringAfter("?").contains("&"), url)
    }

    @Test
    fun retryability_follows_the_code_not_the_message() {
        assertEquals(false, com.curato.fakes.error(ErrorCode.NOT_FOUND).isRetryable)
        assertEquals(false, com.curato.fakes.error(ErrorCode.VALIDATION_FAILED).isRetryable)
        assertEquals(true, com.curato.fakes.error(ErrorCode.UPSTREAM_UNAVAILABLE).isRetryable)
        assertEquals(true, com.curato.fakes.error(ErrorCode.NETWORK_UNAVAILABLE).isRetryable)
        assertEquals(true, com.curato.fakes.error(ErrorCode.INTERNAL_ERROR).isRetryable)
    }
}
