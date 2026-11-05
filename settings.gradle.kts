rootProject.name = "Curato"

pluginManagement {
    repositories {
        google {
            content {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

// Two modules, and the split is a toolchain requirement rather than an architectural
// decomposition: since AGP 9, `com.android.application` cannot be applied alongside the
// Kotlin Multiplatform plugin, so the Android entry point has to be its own module.
//
//   :shared     every layer — domain, data, presentation, ui. The architecture lives here.
//   :androidApp an Activity, an Application and a manifest. No logic.
//
// The "single module" decision in DECISIONS.md was about not splitting the architecture
// into modules, and that still holds: data/domain/presentation/ui remain one unit.
include(":shared")
include(":androidApp")
