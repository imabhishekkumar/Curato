package com.curato

import com.curato.data.remote.CuratoApi
import com.curato.domain.ApiResult
import com.curato.domain.ErrorCode
import com.curato.domain.usecase.MIN_QUERY_MESSAGE
import com.curato.domain.model.Contact
import com.curato.domain.model.Enquiry
import com.curato.data.mapper.toDto
import com.curato.domain.validation.EMAIL_MESSAGE
import com.curato.domain.validation.FIELD_EMAIL
import com.curato.domain.validation.FIELD_PHONE
import com.curato.domain.validation.PHONE_MESSAGE
import com.curato.domain.validation.VALIDATION_BANNER_MESSAGE
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.HttpMethod
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

    /** The same, but handing back the request body the API actually sent. */
    private fun apiRecordingBody(body: String, recorded: MutableList<String>): CuratoApi {
        val engine = MockEngine { request ->
            recorded += (request.body as TextContent).text
            respond(
                content = body,
                status = HttpStatusCode.OK,
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

    // ── POST /enquiries (SPEC.md §4.6) ──

    private val accepted =
        """{"success":true,"error":null,"data":{"acceptedAt":"2026-11-05T10:15:30.000Z"}}"""

    @Test
    fun a_2xx_decodes_the_accepted_timestamp() = runTest {
        val api = apiReturning(accepted)
        val result = api.submitEnquiry(
            Enquiry(1, 55.99, Contact("Ada", "ada@example.com")).toDto(),
        )
        assertTrue(result is ApiResult.Ok)
        assertEquals("2026-11-05T10:15:30.000Z", result.value.acceptedAt)
    }

    @Test
    fun the_enquiry_goes_to_enquiries_as_a_post() = runTest {
        val methods = mutableListOf<HttpMethod>()
        val engine = MockEngine { request ->
            methods += request.method
            respond(
                content = accepted,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = CuratoApi(createHttpClient(engine), "http://test.local")
        api.submitEnquiry(Enquiry(1, null, Contact("Ada", "ada@example.com")).toDto())
        assertEquals(HttpMethod.Post, methods.single())
    }

    /**
     * §3's body, exactly — and the shape of the *absences* as much as the values.
     *
     * The backend treats a missing key and an empty string differently in one place that
     * matters: `seenPrice` is "ignored when absent or implausible, never rejected". Sending
     * `"seenPrice": null` would be asserting an absence in a shape the schema never
     * promised to read, so the client's `explicitNulls = false` has to actually be doing
     * its job here.
     */
    @Test
    fun absent_optionals_are_omitted_from_the_body_rather_than_sent_as_null() = runTest {
        val bodies = mutableListOf<String>()
        val api = apiRecordingBody(accepted, bodies)

        api.submitEnquiry(
            Enquiry(
                productId = 1,
                seenPrice = null,
                contact = Contact("Ada Lovelace", "ada@example.com", phone = null),
                message = null,
            ).toDto(),
        )

        val body = bodies.single()
        assertFalse(body.contains("seenPrice"), body)
        assertFalse(body.contains("phone"), body)
        assertFalse(body.contains("message"), body)
        assertFalse(body.contains("null"), body)
        // And the required half is present and unmangled.
        assertTrue(body.contains(""""productId":1"""), body)
        assertTrue(body.contains(""""email":"ada@example.com""""), body)
    }

    @Test
    fun a_full_body_carries_every_field_at_its_specified_name() = runTest {
        val bodies = mutableListOf<String>()
        val api = apiRecordingBody(accepted, bodies)

        api.submitEnquiry(
            Enquiry(
                productId = 1,
                seenPrice = 109.95,
                contact = Contact("Ada Lovelace", "ada@example.com", "+91 9876543210"),
                message = "Is this available in black?",
            ).toDto(),
        )

        val body = bodies.single()
        // No client-supplied title or price: the backend resolves the product itself, and
        // a body carrying either would be the bug §3 describes.
        assertFalse(body.contains("title"), body)
        assertFalse(body.contains(""""price""""), body)
        assertTrue(body.contains(""""seenPrice":109.95"""), body)
        assertTrue(body.contains(""""phone":"+91 9876543210""""), body)
        assertTrue(body.contains(""""message":"Is this available in black?""""), body)
    }

    /**
     * The client's own validation strings are the server's, not near-copies.
     *
     * The mirror exists so a customer is told immediately; the two telling them different
     * things about the same field would be worse than only the server telling them. If the
     * backend reworded a message, this fixture and the constant would stop matching.
     */
    @Test
    fun a_422_carries_the_exact_messages_the_client_mirrors() = runTest {
        val api = apiReturning(
            """{"success":false,"data":null,"error":{"code":"VALIDATION_FAILED","userMessage":"Please check the highlighted fields.","developerMessage":"enquiry failed validation: contact.email, contact.phone","traceId":"01JBQ7F3K2","fieldErrors":{"contact.email":"Enter a valid email address","contact.phone":"Enter a valid phone number"}}}""",
            HttpStatusCode.UnprocessableEntity,
        )
        val result = api.submitEnquiry(Enquiry(1, null, Contact("Ada", "bad")).toDto())

        assertTrue(result is ApiResult.Fail)
        assertEquals(ErrorCode.VALIDATION_FAILED, result.error.code)
        assertEquals(VALIDATION_BANNER_MESSAGE, result.error.userMessage)
        assertEquals(EMAIL_MESSAGE, result.error.fieldErrors[FIELD_EMAIL])
        assertEquals(PHONE_MESSAGE, result.error.fieldErrors[FIELD_PHONE])
        // Never retryable, and never carrying a trace: §5 restricts that to 500s.
        assertFalse(result.error.isRetryable)
        assertEquals(null, result.error.traceId)
    }

    /**
     * §6's "validation is not resolution".
     *
     * A well-formed id that does not resolve is a 404 with **no** `fieldErrors` — there is
     * no input to annotate, which is what forces the client onto §7.6's non-retryable path
     * rather than offering "fix this field" for a product that has been removed.
     */
    @Test
    fun an_unresolvable_product_is_NOT_FOUND_with_no_field_errors() = runTest {
        val api = apiReturning(
            """{"success":false,"data":null,"error":{"code":"NOT_FOUND","userMessage":"We could not find what you were looking for.","developerMessage":"enquiry references a product that does not resolve","traceId":"01JBQ7F3K3"}}""",
            HttpStatusCode.NotFound,
        )
        val result = api.submitEnquiry(Enquiry(9999, null, Contact("Ada", "a@b.co")).toDto())

        assertTrue(result is ApiResult.Fail)
        assertEquals(ErrorCode.NOT_FOUND, result.error.code)
        assertTrue(result.error.fieldErrors.isEmpty())
        assertFalse(result.error.isRetryable)
    }

    @Test
    fun a_send_failure_is_retryable_and_carries_no_trace() = runTest {
        val api = apiReturning(
            """{"success":false,"data":null,"error":{"code":"EMAIL_SEND_FAILED","userMessage":"We could not send your enquiry. Please try again.","developerMessage":"smtp rejected the message","traceId":"01JBQ7F3K4"}}""",
            HttpStatusCode.BadGateway,
        )
        val result = api.submitEnquiry(Enquiry(1, null, Contact("Ada", "a@b.co")).toDto())

        assertTrue(result is ApiResult.Fail)
        assertEquals(ErrorCode.EMAIL_SEND_FAILED, result.error.code)
        assertTrue(result.error.isRetryable)
        assertEquals(null, result.error.traceId)
    }

    /** The one code that keeps its trace, because it is the one the client shows. */
    @Test
    fun an_internal_error_keeps_its_trace_id() = runTest {
        val api = apiReturning(
            """{"success":false,"data":null,"error":{"code":"INTERNAL_ERROR","userMessage":"Something went wrong on our end.","developerMessage":"unhandled","traceId":"01JBQ7F3K2"}}""",
            HttpStatusCode.InternalServerError,
        )
        val result = api.submitEnquiry(Enquiry(1, null, Contact("Ada", "a@b.co")).toDto())

        assertTrue(result is ApiResult.Fail)
        assertEquals("01JBQ7F3K2", result.error.traceId)
    }

    @Test
    fun rate_limiting_is_retryable_and_keeps_no_trace() = runTest {
        val api = apiReturning(
            """{"success":false,"data":null,"error":{"code":"RATE_LIMITED","userMessage":"Too many enquiries from this device. Please try again shortly.","developerMessage":"per-IP enquiry rate limit exceeded","traceId":"01JBQ7F3K5"}}""",
            HttpStatusCode.TooManyRequests,
        )
        val result = api.submitEnquiry(Enquiry(1, null, Contact("Ada", "a@b.co")).toDto())

        assertTrue(result is ApiResult.Fail)
        assertEquals(ErrorCode.RATE_LIMITED, result.error.code)
        assertTrue(result.error.isRetryable)
        assertEquals(null, result.error.traceId)
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
