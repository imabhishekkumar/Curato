package com.curato

import com.curato.data.remote.CuratoApi
import com.curato.domain.ApiResult
import com.curato.domain.ErrorCode
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

    @Test
    fun retryability_follows_the_code_not_the_message() {
        assertEquals(false, com.curato.fakes.error(ErrorCode.NOT_FOUND).isRetryable)
        assertEquals(false, com.curato.fakes.error(ErrorCode.VALIDATION_FAILED).isRetryable)
        assertEquals(true, com.curato.fakes.error(ErrorCode.UPSTREAM_UNAVAILABLE).isRetryable)
        assertEquals(true, com.curato.fakes.error(ErrorCode.NETWORK_UNAVAILABLE).isRetryable)
        assertEquals(true, com.curato.fakes.error(ErrorCode.INTERNAL_ERROR).isRetryable)
    }
}
