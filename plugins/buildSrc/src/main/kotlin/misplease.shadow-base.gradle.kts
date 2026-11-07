plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

group = "io.github.misdocumeno"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(kotlin("stdlib"))
}

tasks.named<Jar>("jar") {
    enabled = false
}

tasks.shadowJar {
    archiveClassifier.set("")
    // Shadow 9: EXCLUDE drops duplicates before mergeServiceFiles / Kotlin module transformers.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    exclude("META-INF/io.netty.versions.properties")

    val libPackage = "${project.group}.shaded"
    relocate("kotlin.", "$libPackage.kotlin.")
    relocate("kotlinx.", "$libPackage.kotlinx.")
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}
