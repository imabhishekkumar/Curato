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
