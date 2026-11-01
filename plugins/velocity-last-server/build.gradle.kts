plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("kapt") version "1.9.22"
    id("com.gradleup.shadow") version "8.3.5"
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

    implementation(kotlin("stdlib"))

    implementation("org.jetbrains.exposed:exposed-core:0.50.1")
    implementation("org.jetbrains.exposed:exposed-dao:0.50.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.50.1")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
}

tasks {
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