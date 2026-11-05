package com.curato.domain.repository

import com.curato.domain.ApiResult
import com.curato.domain.model.Enquiry
import com.curato.domain.model.EnquiryAccepted

/**
 * Submitting an enquiry (SPEC.md §4.6).
 *
 * Its own interface rather than a method on [CatalogueRepository], which is named for
 * reading a catalogue. This is the app's only **write**, it is non-idempotent, and it
 * causes mail to be sent — putting it behind a type called "catalogue" would be the first
 * place this layer's names stopped describing what they do.
 *
 * A success means **SMTP accepted the message**, not that the request was received. That
 * is what makes the client's thank-you truthful, and it is why there is no "queued"
 * state anywhere above this line.
 */
interface EnquiryRepository {
    suspend fun submit(enquiry: Enquiry): ApiResult<EnquiryAccepted>
}
