package io.github.misdocumeno

import io.github.misdocumeno.infra.database.DatabaseFactory
import io.github.misdocumeno.infra.database.ExposedLastSessionRepository
import io.github.misdocumeno.infra.paper.LastSessionListener
import org.bukkit.plugin.java.JavaPlugin

class BackendLastSessionPlugin : JavaPlugin() {
    override fun onEnable() {
        val serverId =
            System.getenv("SERVER_ID")?.takeIf { it.isNotBlank() }
                ?: error("SERVER_ID is required for backend-last-session")
        DatabaseFactory.initFromEnv()
        val repository = ExposedLastSessionRepository()
        server.pluginManager.registerEvents(LastSessionListener(this, repository, serverId), this)
        logger.info("last-session writer ready (server=$serverId)")
    }
}
