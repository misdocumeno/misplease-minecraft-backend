plugins {
    id("misplease.velocity")
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.lettuce.core)
    compileOnly(libs.luckperms.api)
    compileOnly("net.skinsrestorer:skinsrestorer-api:15.12.6")
}

tasks.shadowJar {
    val libPackage = "${project.group}.shaded"
    relocate("io.lettuce.", "$libPackage.io.lettuce.")
    relocate("io.netty.", "$libPackage.io.netty.")
}
