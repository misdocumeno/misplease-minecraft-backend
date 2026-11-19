package io.github.misdocumeno

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.Dependency
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.limbo.LimboBootstrap
import org.slf4j.Logger

/**
 * Login lives in the proxy.
 *
 * Every player who connects lands in a virtual limbo (LimboAPI) instead of a
 * Paper server, authenticates with `/login`, and only then is connected to a
 * backend. Until that point there is no Paper player, no tablist entry and no
 * world state to corrupt - the whole class of "backend player exists but is not
 * authenticated" bugs disappears by construction.
 *
 * The auth service owns the session: a successful `POST /login` writes the same
 * Redis session the backends read, so there is no second implementation of auth.
 *
 * LimboAPI is a **required** dependency: Velocity resolves it before this plugin,
 * adds its jar to this plugin's classloader and refuses to start the proxy plugin
 * set if it is missing. There is deliberately no "works without LimboAPI" mode -
 * silently falling back to backend login would hide a broken deploy, and the
 * smoke gate asserts every declared plugin actually registered.
 */
@Plugin(
    id = "limbo",
    name = "Limbo",
    version = "0.4.0",
    description = "Proxy-side login limbo",
    authors = ["Mis"],
    dependencies = [Dependency(id = "limboapi")],
)
class VelocityLimboPlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
) {

    private lateinit var bootstrap: LimboBootstrap

    @Subscribe
    fun onProxyInitialize(event: ProxyInitializeEvent) {
        val container = proxy.pluginManager.getPlugin("limboapi").orElseThrow {
            IllegalStateException("limboapi is a declared dependency but is not registered")
        }
        bootstrap = LimboBootstrap(proxy, this, container, logger)
        bootstrap.start()
    }
}
