import io.github.misdocumeno.gradle.relocateServiceFileContents

plugins {
    id("misplease.velocity")
}

dependencies {
    implementation(libs.lettuce.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.postgresql)
    compileOnly(libs.luckperms.api)
    compileOnly(libs.libertybans.api)
}

tasks.shadowJar {
    val libPackage = "${project.group}.shaded"
    relocate("io.lettuce.", "$libPackage.io.lettuce.")
    relocate("io.netty.", "$libPackage.io.netty.")
    relocate("org.jetbrains.exposed.", "$libPackage.org.jetbrains.exposed.")
    relocate("org.postgresql.", "$libPackage.org.postgresql.")
    doLast {
        relocateServiceFileContents(archiveFile.get().asFile, libPackage)
    }
}