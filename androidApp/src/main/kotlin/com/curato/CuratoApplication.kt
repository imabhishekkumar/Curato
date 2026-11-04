package com.curato

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import com.curato.di.androidPlatformModule
import com.curato.di.sharedModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class CuratoApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@CuratoApplication)
            modules(
                androidPlatformModule(
                    context = this@CuratoApplication,
                    // A build-config field, so pointing the app at a different backend
                    // needs no code edit.
                    baseUrl = BuildConfig.API_BASE_URL,
                    logHttp = BuildConfig.DEBUG,
                ),
                sharedModule,
            )
        }
    }

    /**
     * Images are the payload on Home: 18 distinct ones, about 4.3 MB against 10.6 KB of
     * JSON, and upstream serves them as PNGs at roughly eight times a comparable JPEG.
     * A disk cache is what makes a second visit cost nothing — the image host already
     * sends `cache-control: max-age=14400`, so this honours a freshness window that
     * exists rather than inventing one.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            // Coil does not pick up a network backend on its own; without this every
            // request fails silently and the panel just stays empty.
            .components { add(KtorNetworkFetcherFactory()) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.20).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}
