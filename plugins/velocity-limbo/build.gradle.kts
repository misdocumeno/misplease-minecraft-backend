import java.io.File
import java.net.URI
import java.security.MessageDigest

plugins {
    id("misplease.velocity")
}

// LimboAPI is not published to Maven: 2.0.0-SNAPSHOT on repo.elytrium.net is
// unreleased master (no 2.x release exists yet) and dropped the chunk/world API
// used below. The 1.2.0 release jar is both the runtime plugin (plugins.yml +
// lock) and the compileOnly API, so versions must move together.
val limboApiVersion = "1.2.0"
val limboApiSha256 = "25582EBF5567FEC1C20A03AD2C6E01FA05911A76749C67E7B0192ACF508F7D31"
val limboApiJar = layout.buildDirectory.file("deps/limboapi-$limboApiVersion.jar")

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02X".format(it) }
}

// Velocity proxy internals (com.velocitypowered.proxy.*) are needed for the self
// skin-metadata packet: `MinecraftPacket` and `ProtocolUtils`. They are not
// published as an API artifact, so we compile against the exact jar the proxy
// runs. The sha256 is also the fill-data object id; keep it in sync with
// `server:` in infra/velocity/plugins-lock.yml.
val velocityVersion = "4.3.1"
val velocityBuild = "45"
val velocityProxySha256 = "0844BCABDCDB4F5B875906FD4C3365BC1BB76918E42BC595ED88AD195258DD75"
val velocityProxyJar = layout.buildDirectory.file("deps/velocity-proxy-$velocityVersion.jar")

val downloadVelocityProxy = tasks.register("downloadVelocityProxy") {
    description = "Download the pinned Velocity proxy jar (compileOnly internals)."
    val target = velocityProxyJar.get().asFile
    outputs.file(target)
    doLast {
        if (target.isFile && sha256(target) == velocityProxySha256) return@doLast
        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        val url = "https://fill-data.papermc.io/v1/objects/" +
            "${velocityProxySha256.lowercase()}/velocity-$velocityVersion-$velocityBuild.jar"
        URI.create(url).toURL().openStream().use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        val actual = sha256(tmp)
        check(actual == velocityProxySha256) {
            "velocity-$velocityVersion-$velocityBuild.jar sha256 mismatch: expected $velocityProxySha256, got $actual"
        }
        tmp.renameTo(target)
    }
}

val downloadLimboApi = tasks.register("downloadLimboApi") {
    description = "Download the pinned LimboAPI release jar (compileOnly API)."
    val target = limboApiJar.get().asFile
    outputs.file(target)
    doLast {
        if (target.isFile && sha256(target) == limboApiSha256) return@doLast
        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        val url = "https://github.com/Elytrium/LimboAPI/releases/download/$limboApiVersion/limboapi-$limboApiVersion.jar"
        URI.create(url).toURL().openStream().use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        val actual = sha256(tmp)
        check(actual == limboApiSha256) {
            "limboapi-$limboApiVersion.jar sha256 mismatch: expected $limboApiSha256, got $actual"
        }
        tmp.renameTo(target)
    }
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)
    compileOnly(files(limboApiJar).builtBy(downloadLimboApi))
    compileOnly(files(velocityProxyJar).builtBy(downloadVelocityProxy))
}

tasks.shadowJar {
    val libPackage = "${project.group}.shaded"
    relocate("io.ktor.", "$libPackage.io.ktor.")
    // Do NOT relocate io.netty: SelfSkinPacket implements a Velocity interface
    // whose `encode` takes an `io.netty.buffer.ByteBuf`, and a relocated signature
    // stops overriding it. Nothing in this jar bundles netty anyway.
}
