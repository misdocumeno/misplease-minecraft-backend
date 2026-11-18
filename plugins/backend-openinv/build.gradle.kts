import io.github.misdocumeno.gradle.relocateServiceFileContents

plugins {
    id("misplease.paper")
}

dependencies {
    // Last-session lookup (which backend owns an offline player). Plain JDBC, no ORM.
    implementation(libs.postgresql)

    // Runtime APIs, provided by the servers (softdepend in plugin.yml).
    compileOnly("maven.modrinth:multiverse-core:5.8.2")
    compileOnly("maven.modrinth:multiverse-inventories:5.3.6")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    val libPackage = "${project.group}.shaded"
    relocate("org.postgresql.", "$libPackage.org.postgresql.")
    doLast {
        relocateServiceFileContents(archiveFile.get().asFile, libPackage)
    }
}