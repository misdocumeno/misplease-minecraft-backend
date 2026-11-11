package io.github.misdocumeno

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.domain.LastSessionService
import io.github.misdocumeno.infra.database.DatabaseFactory
import io.github.misdocumeno.infra.database.ExposedLastSessionRepository
import io.github.misdocumeno.listener.LastSessionListener
import org.slf4j.Logger
import java.nio.file.Path

@Plugin(
    id = "last-session",
    name = "LastSession",
    version = "0.2.0",
    description = "Restore the last backend from player_last_session after reconnect (Postgres)",
    authors = ["Mis"],
)
class VelocityLastSessionPlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @param:DataDirectory private val dataDir: Path,
) {

    @Subscribe
    fun onProxyInitialize(event: ProxyInitializeEvent) {
        DatabaseFactory.initFromEnv()
        logger.info("last-session restore: postgres (DATABASE_URL), writes owned by backends")

        val repository = ExposedLastSessionRepository()
        val service = LastSessionService(repository)

        proxy.eventManager.register(this, LastSessionListener(proxy, service, logger))
    }
}
