plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    id("com.gradleup.shadow") version "8.3.5"
}

group = "io.github.misdocumeno"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")

    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")

    implementation("redis.clients:jedis:5.1.0")

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
    }

    build {
        dependsOn(shadowJar)
    }
}

kotlin {
    jvmToolchain(21)
}