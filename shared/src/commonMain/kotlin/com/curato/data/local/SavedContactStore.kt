package com.curato.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.curato.domain.model.Contact
import com.curato.domain.repository.SavedContactRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The stored shape (SPEC.md §9.2).
 *
 * Three fields, and the list of what is *not* here is the point: no message, nothing
 * product-specific, no timestamp. A message is specific to the product it was written
 * about, so restoring a previous one onto a different enquiry would mislead — it lives in
 * the ephemeral draft instead, which dies with its route.
 */
@Serializable
internal data class StoredContact(
    val name: String,
    val email: String,
    val phone: String? = null,
)

internal fun StoredContact.toDomain() = Contact(name, email, phone)
internal fun Contact.toStored() = StoredContact(name, email, phone)

/**
 * The customer's contact details, on the device only (SPEC.md §9.2).
 *
 * Shares the [DataStore] with [RecentlyViewedStore] under its own key rather than opening
 * a second file: one preferences file, two independent records, and DataStore's edits are
 * transactional so a write to either cannot leave the other half-written.
 *
 * This is the **persistent** store. It is not the enquiry draft, and §7.6 sets the two
 * out side by side because conflating them is the easiest way to get this screen wrong.
 */
class SavedContactStore(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SavedContactRepository {

    private val key = stringPreferencesKey("saved_contact")

    override fun observe(): Flow<Contact?> = dataStore.data
        // A corrupt store makes the next enquiry a first enquiry; it never takes the
        // screen down.
        .catch { emit(emptyPreferences()) }
        .map { preferences -> decodeContact(preferences[key], json) }

    override suspend fun save(contact: Contact) {
        dataStore.edit { preferences ->
            preferences[key] = json.encodeToString(contact.toStored())
        }
    }

    /**
     * Removes the key rather than writing a blank record.
     *
     * "No saved details" and "saved details that happen to be empty" must not be two
     * states: the first drives the full form with consent on, and a blank record would
     * instead prefill nothing into a collapsed summary with an Edit button.
     */
    override suspend fun clear() {
        dataStore.edit { it.remove(key) }
    }
}

/**
 * Absent or unparseable data reads as nothing saved.
 *
 * Written by an older build, or half-written when the process died, it must not throw
 * into a Flow the UI is collecting — and `null` is already a state this screen handles,
 * so degrading to it costs nothing.
 */
internal fun decodeContact(raw: String?, json: Json): Contact? {
    if (raw.isNullOrBlank()) return null
    return runCatching { json.decodeFromString<StoredContact>(raw).toDomain() }.getOrNull()
}
