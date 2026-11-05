package com.curato.domain.repository

import com.curato.domain.model.Contact
import kotlinx.coroutines.flow.Flow

/**
 * The customer's contact details, on the device only (SPEC.md §9.2).
 *
 * **Not the enquiry draft.** §7.6 sets the two out side by side because conflating them
 * is the easiest way to implement the enquiry screen wrongly: this one survives app
 * restarts indefinitely and never holds a message; the draft survives process death
 * only, dies with the route, and does hold one.
 *
 * [observe] is a `Flow` rather than a one-shot read so unchecking consent propagates to
 * an open screen without a manual refresh — which matters because the clear is immediate
 * (§9.2) rather than deferred to the next submission.
 */
interface SavedContactRepository {
    /** Emits `null` when nothing is saved, which is the first-enquiry case. */
    fun observe(): Flow<Contact?>

    /** Written on a successful submission, and **only** with consent checked. */
    suspend fun save(contact: Contact)

    /** Called the moment consent is unchecked, not at the next submit. */
    suspend fun clear()
}
