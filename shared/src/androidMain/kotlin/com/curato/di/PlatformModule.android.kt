package com.curato.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.curato.data.local.DATASTORE_FILE_NAME
import com.curato.data.local.createDataStore
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * What only Android knows: the HTTP engine, where the preferences file lives, and the
 * backend address for this build type.
 */
fun androidPlatformModule(
    context: Context,
    baseUrl: String,
    logHttp: Boolean,
): Module = module {
    single(named(QUALIFIER_BASE_URL)) { baseUrl }
    single(named(QUALIFIER_LOG_HTTP)) { logHttp }
    single<HttpClientEngine> { OkHttp.create() }
    single<DataStore<Preferences>> {
        createDataStore { context.filesDir.resolve(DATASTORE_FILE_NAME).absolutePath }
    }
}
