package io.github.misdocumeno

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.ServerLoadEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.jar.JarFile

/**
 * After startup, every plugin jar under plugins/ that declares plugin.yml or
 * paper-plugin.yml must be loaded and enabled. Emits a single grep-friendly line
 * for docker/smoke-check.sh. No-op outside smoke boots.
 */
class BackendSmokeCheckPlugin : JavaPlugin(), Listener {
    override fun onEnable() {
        if (!isSmokeBoot()) {
            logger.info("smoke-check idle (not a smoke boot)")
            return
        }
        server.pluginManager.registerEvents(this, this)
    }

    @EventHandler
    fun onServerLoad(event: ServerLoadEvent) {
        if (event.type != ServerLoadEvent.LoadType.STARTUP) return
        runCheck()
    }

    private fun runCheck() {
        val pluginsDir = dataFolder.parentFile
            ?: error("plugins dir missing")
        val jars = pluginsDir.listFiles { f -> f.isFile && f.name.endsWith(".jar", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

        val failures = mutableListOf<String>()
        var expected = 0

        for (jar in jars) {
            val name = readPluginName(jar) ?: continue
            expected++
            val plugin = server.pluginManager.getPlugin(name)
            when {
                plugin == null -> failures += "$name (${jar.name}: not loaded)"
                !plugin.isEnabled -> failures += "$name (${jar.name}: disabled)"
            }
        }

        if (failures.isEmpty()) {
            logger.info("[SmokeCheck] OK: $expected plugins")
        } else {
            logger.severe("[SmokeCheck] FAIL: ${failures.joinToString("; ")}")
        }
    }

    private fun readPluginName(jar: java.io.File): String? {
        return try {
            JarFile(jar).use { jf ->
                val entry = jf.getEntry("plugin.yml")
                    ?: jf.getEntry("paper-plugin.yml")
                    ?: return null
                jf.getInputStream(entry).bufferedReader().use { reader ->
                    reader.lineSequence()
                        .map { it.trim() }
                        .firstOrNull { it.startsWith("name:") }
                        ?.substringAfter("name:")
                        ?.trim()
                        ?.removeSurrounding("\"")
                        ?.removeSurrounding("'")
                        ?.takeIf { it.isNotEmpty() }
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
