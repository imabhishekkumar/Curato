package com.curato.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import okio.Path.Companion.toPath

internal const val DATASTORE_FILE_NAME = "curato.preferences_pb"

/**
 * The store itself, built from a path the platform supplies.
 *
 * Keeping the path on the outside avoids an `expect`/`actual` for what is really one
 * string, and keeps this file free of platform imports.
 */
fun createDataStore(producePath: () -> String): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath { producePath().toPath() }
