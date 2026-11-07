plugins {
    id("misplease.shadow-base")
    kotlin("plugin.serialization")
    kotlin("kapt")
}

dependencies {
    compileOnly(MispleaseDeps.VELOCITY_API)
    kapt(MispleaseDeps.VELOCITY_API)
}

kotlin {
    sourceSets.configureEach {
        languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
    }
}
