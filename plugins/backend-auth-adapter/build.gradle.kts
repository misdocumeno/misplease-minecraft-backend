plugins {
    id("misplease.paper")
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.jedis)
    // Provided by the Paper server at runtime; used to redact /login from console logs.
    compileOnly("org.apache.logging.log4j:log4j-core:2.24.3")
}
