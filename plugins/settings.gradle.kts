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
