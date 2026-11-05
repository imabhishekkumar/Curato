package com.curato.data.remote

import com.curato.data.remote.dto.ApiErrorDto
import com.curato.data.remote.dto.CategoriesDto
import com.curato.data.remote.dto.CategoryProductsDto
import com.curato.data.remote.dto.EnvelopeDto
import com.curato.data.remote.dto.HomeDto
import com.curato.data.remote.dto.ProductDto
import com.curato.domain.ApiResult
import com.curato.domain.DomainError
import com.curato.CuratoLog
import com.curato.domain.ErrorCode
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.appendPathSegments
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

/**
 * Every call to our backend.
 *
 * There is exactly one place a response becomes an [ApiResult], and every endpoint goes
 * through it. The alternative — per-endpoint error handling — is how a client ends up
 * with five slightly different ideas of what a failure is.
 */
class CuratoApi(
    private val client: HttpClient,
    private val baseUrl: String,
) {
    suspend fun getHome(): ApiResult<HomeDto> =
        request { client.get(baseUrl) { url { appendPathSegments("home") } } }

    suspend fun getCategories(): ApiResult<CategoriesDto> =
        request { client.get(baseUrl) { url { appendPathSegments("categories") } } }

    /**
     * The slug goes in as its own path segment, so `appendPathSegments` encodes it.
     *
     * That matters more than it looks: slugs are backend-owned and today none of them
     * needs escaping, but building this path by string concatenation would make a future
     * slug containing a `/` or a space a silent routing bug rather than a request.
     */
    suspend fun getCategoryProducts(slug: String): ApiResult<CategoryProductsDto> =
        request {
            client.get(baseUrl) { url { appendPathSegments("categories", slug, "products") } }
        }

    /**
     * The one endpoint that returns a full product, and the only source of
     * `description` (SPEC.md §4.5).
     *
     * A non-numeric or unknown id is answered `NOT_FOUND` by the backend rather than a
     * validation failure — resolution is not validation, and there is no field for the
     * customer to retype.
     */
    suspend fun getProduct(id: Int): ApiResult<ProductDto> =
        request {
            client.get(baseUrl) { url { appendPathSegments("products", id.toString()) } }
        }

    /**
     * Run a call and turn whatever happens into a result.
     *
     * Three failure shapes are collapsed here: an envelope reporting an error, a
     * transport problem with no envelope at all, and a body that did not parse as the
     * envelope. Each gets a code the UI can branch on.
     */
    private suspend inline fun <reified T> request(
        call: () -> HttpResponse,
    ): ApiResult<T> = try {
        val envelope: EnvelopeDto<T> = call().body()
        val data = envelope.data
        when {
            envelope.success && data != null -> ApiResult.Ok(data)
            envelope.error != null -> ApiResult.Fail(envelope.error.toDomain())
            else -> ApiResult.Fail(unexpectedResponse())
        }
    } catch (cancellation: CancellationException) {
        // Never swallowed: a cancelled call is the caller leaving, not a failure to show.
        throw cancellation
    } catch (timeout: HttpRequestTimeoutException) {
        CuratoLog.warn("request timed out", timeout)
        ApiResult.Fail(
            DomainError(
                code = ErrorCode.TIMEOUT,
                userMessage = "That took too long. Please try again.",
            ),
        )
    } catch (io: IOException) {
        CuratoLog.warn("transport failure", io)
        ApiResult.Fail(
            DomainError(
                code = ErrorCode.NETWORK_UNAVAILABLE,
                userMessage = "No connection. Check your network and try again.",
            ),
        )
    } catch (other: Exception) {
        // Anything else means the body was not the envelope we require — a deserialization
        // failure, an HTML error page from a proxy, a truncated response.
        CuratoLog.warn("response was not a readable envelope", other)
        ApiResult.Fail(unexpectedResponse())
    }
}

private fun unexpectedResponse() = DomainError(
    code = ErrorCode.UNEXPECTED_RESPONSE,
    userMessage = "Something went wrong. Please try again.",
)

/**
 * `developerMessage` is read and dropped here.
 *
 * It ships in the response body and is never shown, so discarding it at the boundary
 * means no layer above can render it by accident.
 */
fun ApiErrorDto.toDomain(): DomainError {
    val parsed = ErrorCode.fromWire(code)
    return DomainError(
        code = parsed,
        userMessage = userMessage,
        fieldErrors = fieldErrors ?: emptyMap(),
        // Only ever surfaced on an internal error, so it is not carried otherwise.
        traceId = traceId.takeIf { parsed == ErrorCode.INTERNAL_ERROR },
    )
}
