package io.github.misdocumeno

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.Dependency
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.Logger

/**
 * Login lives in the proxy.
 *
 * Every player who connects lands in a virtual limbo (LimboAPI) instead of a
 * Paper server, authenticates with `/login`, and only then is connected to a
 * backend. Until that point there is no Paper player, no tablist entry and no
 * world state to corrupt — the whole class of "backend player exists but is not
 * authenticated" bugs disappears by construction.
 *
 * The auth service owns the session: a successful `POST /login` writes the same
 * Redis session the backends read, so there is no second implementation of auth.
 *
 * This class must not mention a LimboAPI type, not even in a field type: the
 * proxy has to load the plugin (and no-op) before LimboAPI is installed, and a
 * field of a LimboAPI type is resolved while the class is loaded. That is why
 * the handle is `Any` and everything limbo-related lives in `limbo.LimboBootstrap`,
 * which is only touched when the limboapi plugin is present.
 */
@Plugin(
    id = "limbo",
    name = "Limbo",
    version = "0.1.0",
    description = "Proxy-side login limbo",
    authors = ["Mis"],
    dependencies = [Dependency(id = "limboapi", optional = true)],
)
class VelocityLimboPlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
) {

    /** [io.github.misdocumeno.limbo.LimboBootstrap]; `Any` on purpose. */
    private var bootstrap: Any? = null

    @Subscribe
    fun onProxyInitialize(event: ProxyInitializeEvent) {
        val container = proxy.pluginManager.getPlugin("limboapi").orElse(null)
        if (container == null) {
            logger.warn("limboapi is not installed — login limbo disabled, players go straight to a backend")
            return
        }
        bootstrap = io.github.misdocumeno.limbo.LimboBootstrap(proxy, container, logger).also { it.start() }
    }
}
