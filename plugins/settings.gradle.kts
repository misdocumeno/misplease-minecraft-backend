pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.opencollab.dev/main/")
        maven("https://repo.opencollab.dev/maven-snapshots/")
    }
}

rootProject.name = "misplease-plugins"

include(
    "backend-auth-adapter",
    "backend-chat-adapter",
    "backend-connection-announce",
    "backend-global-teleporter",
    "backend-location-resolver",
    "backend-restarter",
    "backend-smoke-check",
    "backend-tp-command",
    "velocity-auth-adapter",
    "velocity-chat-control",
    "velocity-global-teleporter",
    "velocity-last-session",
    "backend-last-session",
    "velocity-maintenance",
    "velocity-presence",
    "velocity-social",
    "velocity-tablist-broadcast",
)
