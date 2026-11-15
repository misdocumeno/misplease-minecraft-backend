plugins {
    id("misplease.velocity")
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.lettuce.core)
    compileOnly(MispleaseDeps.FLOODGATE_API)
}

tasks.shadowJar {
    val libPackage = "${project.group}.shaded"
    relocate("io.lettuce.", "$libPackage.io.lettuce.")
    relocate("io.netty.", "$libPackage.io.netty.")
}
