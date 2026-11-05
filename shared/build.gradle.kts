plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    // 21, not the JDK on PATH. This machine defaults to 26, which AGP does not support,
    // so the toolchain is pinned rather than inherited.
    jvmToolchain(21)

    androidLibrary {
        namespace = "com.curato.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        // Opt in to host-side unit tests. Without this, commonTest compiles and runs
        // for iOS only — and Android is the target that actually ships, so the suite
        // would be green on the platform nobody installs.
        withHostTestBuilder {}.configure {}
    }

    // Declared so the shared code genuinely compiles for iOS. No Xcode project — iOS is
    // additive later, and committing an app target nothing here can build would repeat a
    // mistake this repo has already corrected once.
    //
    // iosX64 (the Intel simulator) is deliberately absent: Compose Multiplatform 1.12
    // stopped publishing artifacts for it, so declaring the target would fail to resolve.
    // Device and Apple Silicon simulator are the two that exist.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "CuratoShared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)

            implementation(libs.navigation.compose)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.viewmodel.savedstate)

            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.logging)

            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)

            implementation(libs.datastore.preferences)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.turbine)
            implementation(libs.koin.test)
        }
    }
}

// Pinned rather than derived from the module name, so the generated accessor package is
// stable and the import in Type.kt cannot drift if the module is ever renamed.
compose.resources {
    publicResClass = true
    packageOfResClass = "com.curato.resources"
    generateResClass = always
}
