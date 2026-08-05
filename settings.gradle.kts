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
        // JitPack hosts the Termux terminal widget only; scope it to that group
        // so nothing else is resolved from a third-party build service.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.termux.termux-app") }
        }
    }
}

rootProject.name = "warden-android-app"
include(":app")
