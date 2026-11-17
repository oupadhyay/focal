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

rootProject.name = "focal"

include(
    ":app",
    ":core:designsystem",
    ":core:engine-api",
    ":core:engine-webview",
    ":core:adblock-rust",
    ":core:ai-summarizer",
    ":core:sync-e2ee",
    ":core:database",
    ":feature:search",
    ":feature:browser",
    ":feature:spaces",
    ":feature:tabs",
    ":feature:reader",
    ":feature:media",
)
