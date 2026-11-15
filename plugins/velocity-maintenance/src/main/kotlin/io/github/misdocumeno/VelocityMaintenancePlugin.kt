package io.github.misdocumeno

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyPingEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.slf4j.Logger
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Join gate for CD: Redis key [REDIS_KEY] (`1` / `true` / JSON with enabled).
 * Deploy enables it only after the empty grace, so the 60s wait can still abort.
 */
@Plugin(
    id = "maintenance",
    name = "Maintenance",
    version = "0.1.0",
    description = "Deny joins while Redis maintenance flag is set (CD / ops)",
    authors = ["Mis"],
)
class VelocityMaintenancePlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
) {
    private var redis: RedisClient? = null
    private var connection: StatefulRedisConnection<String, String>? = null

    private val enabled = AtomicBoolean(false)
    private val denyMessage = AtomicReference(DEFAULT_MESSAGE)
    private val miniMessage = MiniMessage.miniMessage()

    @Subscribe
    fun onInit(event: ProxyInitializeEvent) {
        System.getenv("REDIS_URL")?.takeIf { it.isNotBlank() }?.let { url ->
            try {
                val client = RedisClient.create(url)
                redis = client
                connection = client.connect()
            } catch (e: Exception) {
                logger.warn("redis unavailable for maintenance: ${e.message}")
            }
        }
        refresh()
        proxy.scheduler
            .buildTask(this, Runnable { refresh() })
            .repeat(2, TimeUnit.SECONDS)
            .schedule()
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
        connection?.close()
        redis?.shutdown()
    }

    @Subscribe
    fun onPreLogin(event: PreLoginEvent) {
        if (!enabled.get()) return
        event.result = PreLoginEvent.PreLoginComponentResult.denied(
            LegacyComponentSerializer.legacyAmpersand().deserialize(denyMessage.get()),
        )
    }

    /** Only swaps the first-line center title; keeps the rest of the MOTD. */
    @Subscribe
    fun onPing(event: ProxyPingEvent) {
        if (!enabled.get()) return
        val builder = event.ping.asBuilder()
        builder.description(miniMessage.deserialize(MOTD_WITH_MAINTENANCE))
        event.ping = builder.build()
    }

    private fun refresh() {
        val raw = try {
            connection?.sync()?.get(REDIS_KEY)
        } catch (e: Exception) {
            logger.warn("maintenance redis get failed: ${e.message}")
            return
        }
        val next = parseEnabled(raw)
        val was = enabled.getAndSet(next)
        if (raw != null) {
            parseMessage(raw)?.let { denyMessage.set(it) }
        } else {
            denyMessage.set(DEFAULT_MESSAGE)
        }
        if (next && !was) {
            logger.info("maintenance ON — denying joins")
            kickOnline()
        } else if (!next && was) {
            logger.info("maintenance OFF")
        } else if (next) {
            kickOnline()
        }
    }

    private fun kickOnline() {
        val msg = LegacyComponentSerializer.legacyAmpersand().deserialize(denyMessage.get())
        for (player in proxy.allPlayers) {
            player.disconnect(msg)
        }
    }

    companion object {
        const val REDIS_KEY = "misplease:maintenance"
        private const val DEFAULT_MESSAGE =
            "&cServidor en mantenimiento. Probá de nuevo en unos minutos."

        // Same decorative width: 10+9+10 spaces/chars around MisPlease vs 5+19+5 around maintenance.
        private const val MOTD_LINE2 =
            "<dark_purple><bold>|</bold></dark_purple>  <blue>Survival</blue>  <dark_purple><bold>|</bold></dark_purple>        <blue>misplease.com</blue>         <dark_purple><bold>|</bold></dark_purple>    <blue>1.20+</blue>   <dark_purple><bold>|</bold></dark_purple>"
        private const val MOTD_PREFIX =
            "<blue>-</blue><dark_purple><bold>x</bold></dark_purple><blue>-</blue><dark_purple><bold>x</bold></dark_purple><blue>-</blue><dark_purple><bold>x</bold></dark_purple><blue>-</blue><dark_purple><bold>x</bold></dark_purple><blue>-</blue>"
        private const val MOTD_SUFFIX =
            "<blue>-</blue><dark_purple><bold>x</bold></dark_purple><blue>-</blue><dark_purple><bold>x</bold></dark_purple><blue>-</blue><dark_purple><bold>x</bold></dark_purple><blue>-</blue><dark_purple><bold>x</bold></dark_purple><blue>-</blue>"
        private const val MOTD_WITH_NAME =
            "$MOTD_PREFIX          <dark_purple><bold>MisPlease</bold></dark_purple>          $MOTD_SUFFIX<newline>$MOTD_LINE2"
        private const val MOTD_WITH_MAINTENANCE =
            "$MOTD_PREFIX     <dark_purple><bold>(en mantenimiento)</bold></dark_purple>     $MOTD_SUFFIX<newline>$MOTD_LINE2"

        fun parseEnabled(raw: String?): Boolean {
            if (raw.isNullOrBlank()) return false
            val t = raw.trim()
            if (t == "1" || t.equals("true", ignoreCase = true) || t.equals("on", ignoreCase = true)) {
                return true
            }
            if (t == "0" || t.equals("false", ignoreCase = true) || t.equals("off", ignoreCase = true)) {
                return false
            }
            // Minimal JSON: {"enabled":true} or "enabled": true anywhere.
            val enabledMatch = Regex(""""enabled"\s*:\s*(true|false)""", RegexOption.IGNORE_CASE)
                .find(t)
            if (enabledMatch != null) {
                return enabledMatch.groupValues[1].equals("true", ignoreCase = true)
            }
            return false
        }

        fun parseMessage(raw: String): String? {
            val msgMatch = Regex(""""message"\s*:\s*"((?:\\.|[^"\\])*)"""")
                .find(raw.trim())
                ?: return null
            return msgMatch.groupValues[1]
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .ifBlank { null }
        }
    }
}
