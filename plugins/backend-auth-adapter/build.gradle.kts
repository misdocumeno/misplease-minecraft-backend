import io.github.misdocumeno.gradle.relocateServiceFileContents

plugins {
    id("misplease.paper")
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.lettuce.core)
    // Provided by the Paper server at runtime; used to redact /login from console logs.
    compileOnly("org.apache.logging.log4j:log4j-core:2.24.3")

    // The filter test feeds real Log4j events (ParameterizedMessage) through filter().
    testImplementation(kotlin("test"))
    testImplementation("org.apache.logging.log4j:log4j-core:2.24.3")
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    val libPackage = "${project.group}.shaded"
    relocate("io.lettuce.", "$libPackage.io.lettuce.")
    relocate("io.netty.", "$libPackage.io.netty.")
    relocate("io.ktor.", "$libPackage.io.ktor.")
    doLast {
        relocateServiceFileContents(archiveFile.get().asFile, libPackage)
    }
}