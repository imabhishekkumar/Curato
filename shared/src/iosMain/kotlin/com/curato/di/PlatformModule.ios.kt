package com.curato.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.curato.data.local.DATASTORE_FILE_NAME
import com.curato.data.local.createDataStore
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * The iOS half of the same contract.
 *
 * Nothing here is wired to a running app — there is no Xcode project — but it compiles
 * and links, which is what makes the claim that iOS is additive rather than a rewrite
 * something a reviewer can check instead of take on trust.
 */
fun iosPlatformModule(baseUrl: String, logHttp: Boolean = false): Module = module {
    single(named(QUALIFIER_BASE_URL)) { baseUrl }
    single(named(QUALIFIER_LOG_HTTP)) { logHttp }
    single<HttpClientEngine> { Darwin.create() }
    single<DataStore<Preferences>> {
        createDataStore { documentsDirectory() + "/" + DATASTORE_FILE_NAME }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun documentsDirectory(): String {
    val url: NSURL? = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    return requireNotNull(url?.path) { "documents directory unavailable" }
}
