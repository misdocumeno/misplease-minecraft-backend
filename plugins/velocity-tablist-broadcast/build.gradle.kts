plugins {
    id("misplease.velocity")
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.lettuce.core)
    compileOnly(libs.luckperms.api)
}

tasks.shadowJar {
    val libPackage = "${project.group}.shaded"
    relocate("io.lettuce.", "$libPackage.io.lettuce.")
    relocate("io.netty.", "$libPackage.io.netty.")
}
