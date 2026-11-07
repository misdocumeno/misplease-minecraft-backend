plugins {
    id("misplease.shadow-base")
    kotlin("plugin.serialization")
}

dependencies {
    compileOnly(MispleaseDeps.PAPER_API)
}

kotlin {
    sourceSets.configureEach {
        languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
    }
}
