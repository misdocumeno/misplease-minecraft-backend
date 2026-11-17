plugins {
    id("misplease.velocity")
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.lettuce.core)
    compileOnly(libs.libertybans.api)

    // Adventure/Velocity API is compileOnly for the plugin jar, but tests need it at
    // runtime to parse the MiniMessage the formatter produces.
    testImplementation(kotlin("test"))
    testImplementation(MispleaseDeps.VELOCITY_API)
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    val libPackage = "${project.group}.shaded"
    relocate("io.lettuce.", "$libPackage.io.lettuce.")
    relocate("io.netty.", "$libPackage.io.netty.")
}
