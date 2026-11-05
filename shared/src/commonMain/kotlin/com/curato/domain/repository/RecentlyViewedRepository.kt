package com.curato.domain.repository

import com.curato.domain.model.RecentProduct
import kotlinx.coroutines.flow.Flow

/**
 * The device-local recently-viewed list (SPEC.md §9.1).
 *
 * A `Flow` so the UI observes it like any other source. No backend involvement: this is
 * the customer's own history, and sending it anywhere would be telemetry rather than
 * this feature.
 */
interface RecentlyViewedRepository {
    fun observe(): Flow<List<RecentProduct>>
    suspend fun record(product: RecentProduct)
    suspend fun clear()
}
