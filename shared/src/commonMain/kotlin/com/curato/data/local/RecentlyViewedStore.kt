package com.curato.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.curato.domain.model.RecentProduct
import com.curato.domain.repository.RecentlyViewedRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How many products the row remembers before the oldest falls off. */
internal const val RECENTLY_VIEWED_CAP = 10

@Serializable
internal data class StoredRecent(
    val id: Int,
    val title: String,
    val imageUrl: String,
    val price: Double,
    val currency: String,
    val viewedAt: Long,
)

internal fun StoredRecent.toDomain() = RecentProduct(id, title, imageUrl, price, currency, viewedAt)
internal fun RecentProduct.toStored() = StoredRecent(id, title, imageUrl, price, currency, viewedAt)

/**
 * The list rule, as a pure function.
 *
 * Separated from the store so it can be tested without a filesystem: newest first,
 * de-duplicated by id, capped. Re-viewing a product moves its existing entry to the
 * front rather than adding a second one — a row showing the same product three times
 * would be a bug the customer can see.
 */
internal fun applyRecent(
    existing: List<RecentProduct>,
    product: RecentProduct,
    cap: Int = RECENTLY_VIEWED_CAP,
): List<RecentProduct> =
    (listOf(product) + existing.filterNot { it.id == product.id }).take(cap)

/**
 * Recently viewed, on the device only (SPEC.md §9.1).
 *
 * Newest first, capped, de-duplicated. Re-viewing a product moves its existing entry to
 * the front rather than adding a second one — a row showing the same product three
 * times would be a bug the customer can see.
 */
class RecentlyViewedStore(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : RecentlyViewedRepository {

    private val key = stringPreferencesKey("recently_viewed")

    override fun observe(): Flow<List<RecentProduct>> = dataStore.data
        // A corrupt store degrades one row; it never takes a screen down.
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { preferences -> decode(preferences[key]) }

    override suspend fun record(product: RecentProduct) {
        dataStore.edit { preferences ->
            val updated = applyRecent(decode(preferences[key]), product)
            preferences[key] = json.encodeToString(updated.map { it.toStored() })
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(key) }
    }

    /**
     * Absent or unparseable data reads as empty.
     *
     * A store written by an older build, or half-written when the process died, must
     * not throw into a Flow the UI is collecting.
     */
    private fun decode(raw: String?): List<RecentProduct> = decodeRecent(raw, json)
}

/**
 * Absent or unparseable data reads as empty.
 *
 * A store written by an older build, or half-written when the process died, must not
 * throw into a Flow the UI is collecting.
 */
internal fun decodeRecent(raw: String?, json: Json): List<RecentProduct> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        json.decodeFromString<List<StoredRecent>>(raw).map { it.toDomain() }
    }.getOrElse { emptyList() }
}
