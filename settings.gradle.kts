pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Droplet"

include(":app")
include(":core:model")
include(":core:domain")
include(":core:database")
include(":core:security")
include(":core:network")
include(":core:ai")
include(":core:sync")
include(":core:ui")
include(":feature:ledger")
include(":feature:assets")
include(":feature:ai_capture")
include(":feature:stats")
include(":feature:settings")
