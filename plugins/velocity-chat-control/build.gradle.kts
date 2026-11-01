plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    kotlin("kapt") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
}

group = "io.github.misdocumeno"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    kapt("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("io.lettuce:lettuce-core:7.0.0.RELEASE")

    implementation(kotlin("stdlib"))
}

tasks {
    jar {
        enabled = false
    }

    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()

        val libPackage = "${project.group}.shaded"
        relocate("kotlin.", "$libPackage.kotlin.")
        relocate("kotlinx.", "$libPackage.kotlinx.")
        relocate("io.lettuce.", "$libPackage.io.lettuce.")
        relocate("io.netty.", "$libPackage.io.netty.")
    }

    build {
        dependsOn(shadowJar)
    }
}

kotlin {
    jvmToolchain(21)

    sourceSets.all {
        languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
    }
}