package io.github.misdocumeno

import com.google.gson.JsonParser
import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.Logger
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile

/**
 * Proxy counterpart of `backend-smoke-check`: every jar in `plugins/` that
 * declares a `velocity-plugin.json` must be registered with the proxy.
 *
 * Velocity has no "disabled" state — a plugin either registered or it did not —
 * so a single presence check is the whole assertion. This is what catches a jar
 * that fails at class-load time (`Can't create plugin X`) while the proxy itself
 * boots fine and still prints `Done (`.
 *
 * Emits one grep-friendly line for `docker/smoke-check.sh`. No-op outside smoke
 * boots. SMOKE_ALLOW_DISABLED (comma-separated plugin ids) is honoured for parity
 * with the Paper side.
 */
@Plugin(
    id = "smoke-check",
    name = "SmokeCheck",
    version = "0.1.0",
    description = "Proxy smoke boot: assert every bundled plugin registered",
    authors = ["Mis"],
)
class VelocitySmokeCheckPlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @param:DataDirectory private val dataDir: Path,
) {

    @Subscribe
    fun onProxyInitialize(event: ProxyInitializeEvent) {
        if (!isSmokeBoot()) {
            logger.info("smoke-check idle (not a smoke boot)")
            return
        }
        // Give late initialisers a moment; a plugin that throws during its own
        // ProxyInitializeEvent must not be counted as loaded.
        proxy.scheduler
            .buildTask(this, Runnable { runCheck() })
            .delay(5, TimeUnit.SECONDS)
            .schedule()
    }

    private fun runCheck() {
        try {
            checkPlugins()
        } catch (e: Exception) {
            // Never leave the smoke without a line: silence would look like a pass.
            logger.error("[SmokeCheck] FAIL: check crashed: {}", e.toString())
        }
    }

    private fun checkPlugins() {
        val allowDisabled = System.getenv("SMOKE_ALLOW_DISABLED")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()

        val pluginsDir = dataDir.parent
        val jars = pluginsDir
            ?.toFile()
            ?.listFiles { file -> file.isFile && file.name.endsWith(".jar", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

        val failures = mutableListOf<String>()
        var expected = 0
        var allowed = 0

        for (jar in jars) {
            val id = readPluginId(jar) ?: continue
            expected++
            val loaded = proxy.pluginManager.getPlugin(id).isPresent
            when {
                loaded -> Unit
                id in allowDisabled -> {
                    allowed++
                    logger.warn("[SmokeCheck] allow-disabled: {} ({})", id, jar.name)
                }

                else -> failures += "$id (${jar.name}: not loaded)"
            }
        }

        if (failures.isEmpty()) {
            logger.info(
                "[SmokeCheck] OK: {} plugins{}",
                expected,
                if (allowed > 0) " (allow-disabled=$allowed)" else "",
            )
        } else {
            logger.error("[SmokeCheck] FAIL: {}", failures.joinToString("; "))
        }
    }

    private fun readPluginId(jar: java.io.File): String? {
        return try {
            JarFile(jar).use { jf ->
                val entry = jf.getEntry("velocity-plugin.json") ?: return null
                jf.getInputStream(entry).bufferedReader().use { reader ->
                    JsonParser.parseString(reader.readText())
                        .asJsonObject
                        .get("id")
                        ?.asString
                        ?.takeIf { it.isNotBlank() }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isSmokeBoot(): Boolean {
        if (System.getenv("SMOKE_PLAIN") == "1") return true
        val serverId = System.getenv("SERVER_ID")?.trim().orEmpty()
        return serverId.equals("smoke", ignoreCase = true)
    }
}
