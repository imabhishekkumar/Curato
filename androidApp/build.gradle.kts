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
            // Inside the emulator, `localhost` is the emulator; 10.0.2.2 is the host.
            // That is the documented default and works on a standard setup.
            //
            // It does not work everywhere. On at least one machine here the emulator's
            // NAT answers ICMP but never completes a TCP connection to the host, so the
            // app times out with the backend plainly running. `adb reverse` tunnels over
            // the adb connection instead and sidesteps the NAT entirely — which also
            // makes it the route that works for a physical device:
            //
            //   adb reverse tcp:8080 tcp:8080
            //   ./gradlew :androidApp:installDebug -Pcurato.apiBaseUrl=http://localhost:8080
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
