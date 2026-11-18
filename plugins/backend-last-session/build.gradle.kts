import io.github.misdocumeno.gradle.relocateServiceFileContents

plugins {
    id("misplease.paper")
}

dependencies {
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.postgresql)
}

tasks.shadowJar {
    val libPackage = "${project.group}.shaded"
    relocate("org.jetbrains.exposed.", "$libPackage.org.jetbrains.exposed.")
    relocate("org.postgresql.", "$libPackage.org.postgresql.")
    doLast {
        relocateServiceFileContents(archiveFile.get().asFile, libPackage)
    }
}