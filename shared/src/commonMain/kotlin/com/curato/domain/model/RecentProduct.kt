package com.curato.domain.model

/**
 * A device-local snapshot of a product the customer has opened (SPEC.md §9.1).
 *
 * Denormalised rather than an id, so the row renders with zero network calls and works
 * offline. It deliberately carries **no rating**: storing one would put a value on the
 * device that nothing revalidates. A stored price can go stale, which is accepted —
 * tapping through fetches fresh data, so a stale value never reaches a decision point.
 */
data class RecentProduct(
    val id: Int,
    val title: String,
    val imageUrl: String,
    val price: Double,
    val currency: String,
    val viewedAt: Long,
)
