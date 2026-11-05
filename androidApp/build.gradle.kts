// The Android entry point, and nothing else: an Activity, an Application and a manifest.
// Every layer of the app lives in :shared, so this module has no logic to test.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

/** Override with `-Pcurato.apiBaseUrl=…`; see the debug build type below. */
val debugApiBaseUrl: String =
    providers.gradleProperty("curato.apiBaseUrl").getOrElse("http://10.0.2.2:8080")

android {
    namespace = "com.curato"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.curato"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            // The effective value comes from `curato.apiBaseUrl` in gradle.properties,
            // which sets `http://localhost:8080` and explains why. The literal below is
            // only the fallback if that line is removed.
            //
            // `localhost` needs `adb reverse tcp:8080 tcp:8080` — which is the point:
            // it is the one route that works on an emulator and on a physical device
            // alike. The 10.0.2.2 host alias is emulator-only, and on some images it is
            // silently unreachable from an app while the system browser still reaches it,
            // so the app times out with the backend plainly running.
            //
            // A property rather than an edit, so neither path needs a code change.
            buildConfigField("String", "API_BASE_URL", "\"$debugApiBaseUrl\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "API_BASE_URL", "\"https://api.curato.invalid\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.koin.android)
    implementation(libs.compose.ui.android)
    // Declared here because this module configures the singleton image loader; :shared
    // uses Coil too, but as an implementation detail that is not part of its API.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor)
}
