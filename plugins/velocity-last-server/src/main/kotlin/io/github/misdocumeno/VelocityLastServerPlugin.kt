package io.github.misdocumeno

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.domain.LastServerService
import io.github.misdocumeno.infra.database.DatabaseFactory
import io.github.misdocumeno.infra.database.ExposedLastServerRepository
import io.github.misdocumeno.listener.LastServerListener
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Plugin(
    id = "last-server",
    name = "LastServer",
    version = "0.1.0",
    description = "Restore the last server the player was on after a reconnect",
    authors = ["Mis"],
)
class VelocityLastServerPlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @param:DataDirectory private val dataDir: Path,
) {

    @Subscribe
    fun onProxyInitialize(event: ProxyInitializeEvent) {
        Files.createDirectories(dataDir)

        DatabaseFactory.init(dataDir)

        val repository = ExposedLastServerRepository()
        val service = LastServerService(repository)

        proxy.eventManager.register(this, LastServerListener(proxy, service, logger))
    }
}
