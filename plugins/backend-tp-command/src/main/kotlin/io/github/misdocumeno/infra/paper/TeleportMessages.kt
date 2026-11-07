package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.domain.model.TeleportFeedback
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * MiniMessage templates under lang/<locale>.yml.
 * Player client language wins when that file exists; otherwise [defaultLocale].
 */
class TeleportMessages(private val plugin: Plugin) {

    private val mm = MiniMessage.miniMessage()
    private val defaultLocale: String =
        plugin.config.getString("default-locale")?.lowercase(Locale.ROOT) ?: "es"
    private val cache = ConcurrentHashMap<String, YamlConfiguration>()

    init {
        plugin.saveDefaultConfig()
        listOf("es", "en").forEach { code ->
            val path = "lang/$code.yml"
            if (!File(plugin.dataFolder, path).exists()) {
                plugin.saveResource(path, false)
            }
        }
    }

    fun playerOnly(sender: CommandSender) = send(sender, "player-only")
    fun playerOnlyWithTarget(sender: CommandSender) = send(sender, "player-only-with-target")
    fun playerNotFound(sender: CommandSender, player: String) = send(sender, "player-not-found", "player" to player)
    fun cannotSelf(sender: CommandSender) = send(sender, "cannot-self")
    fun cannotSelfOther(sender: CommandSender) = send(sender, "cannot-self-other")
    fun busy(sender: CommandSender) = send(sender, "busy")

    fun teleportingPlayer(sender: CommandSender, player: String) =
        send(sender, "teleporting-player", "player" to player)

    fun teleportingToYou(sender: CommandSender, player: String) =
        send(sender, "teleporting-to-you", "player" to player)

    fun teleportingEveryoneYou(sender: CommandSender) = send(sender, "teleporting-everyone-you")
    fun teleportingEveryoneTo(sender: CommandSender, player: String) =
        send(sender, "teleporting-everyone-to", "player" to player)

    fun tpaSent(sender: CommandSender, player: String) = send(sender, "tpa-sent", "player" to player)
    fun tpaAlready(sender: CommandSender, player: String) = send(sender, "tpa-already", "player" to player)

    fun tpaIncoming(to: Player, from: String, here: Boolean) {
        send(to, if (here) "tpahere-incoming" else "tpa-incoming", "player" to from)
        send(to, "tpa-hint", "player" to from)
    }

    fun noRequestsAccept(sender: CommandSender) = send(sender, "no-requests-accept")
    fun noRequestsDeny(sender: CommandSender) = send(sender, "no-requests-deny")
    fun noRequestsCancel(sender: CommandSender) = send(sender, "no-requests-cancel")
    fun specifyAccept(sender: CommandSender) = send(sender, "specify-accept")
    fun specifyDeny(sender: CommandSender) = send(sender, "specify-deny")
    fun specifyCancel(sender: CommandSender) = send(sender, "specify-cancel")
    fun noRequestFrom(sender: CommandSender, player: String) = send(sender, "no-request-from", "player" to player)
    fun noRequestTo(sender: CommandSender, player: String) = send(sender, "no-request-to", "player" to player)

    fun accepted(sender: CommandSender, player: String) = send(sender, "accepted", "player" to player)
    fun acceptedAll(sender: CommandSender) = send(sender, "accepted-all")
    fun denied(sender: CommandSender, player: String) = send(sender, "denied", "player" to player)
    fun deniedAll(sender: CommandSender) = send(sender, "denied-all")
    fun cancelled(sender: CommandSender, player: String) = send(sender, "cancelled", "player" to player)
    fun cancelledAll(sender: CommandSender) = send(sender, "cancelled-all")

    /** Arrival chat for the teleported player (resolved MiniMessage for the global teleporter). */
    fun arrivalFeedback(viewer: CommandSender? = null): TeleportFeedback {
        val locale = localeOf(viewer)
        val template = string(locale, "feedback-arrived")
        val prefix = string(locale, "prefix")
        val resolved = template.replace("<prefix>", prefix)
        return TeleportFeedback(chat = resolved)
    }

    private fun send(sender: CommandSender, key: String, vararg placeholders: Pair<String, String>) {
        sender.sendMessage(component(localeOf(sender), key, *placeholders))
    }

    private fun component(locale: String, key: String, vararg placeholders: Pair<String, String>): Component {
        val prefix = string(locale, "prefix")
        val template = string(locale, key).replace("<prefix>", prefix)
        val resolvers = placeholders.map { (name, value) ->
            Placeholder.unparsed(name, value)
        }.toTypedArray()
        return mm.deserialize(template, TagResolver.resolver(*resolvers))
    }

    private fun string(locale: String, key: String): String {
        val primary = lang(locale).getString(key)
        if (primary != null) return primary
        if (locale != defaultLocale) {
            lang(defaultLocale).getString(key)?.let { return it }
        }
        return "<red>missing:$key</red>"
    }

    private fun lang(locale: String): YamlConfiguration {
        val code = locale.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
        return cache.computeIfAbsent(code) { loadLang(it) }
    }

    private fun loadLang(code: String): YamlConfiguration {
        val file = File(plugin.dataFolder, "lang/$code.yml")
        if (file.exists()) {
            return YamlConfiguration.loadConfiguration(file)
        }
        plugin.getResource("lang/$code.yml")?.use { stream ->
            return YamlConfiguration.loadConfiguration(stream.reader())
        }
        if (code != defaultLocale) {
            return loadLang(defaultLocale)
        }
        return YamlConfiguration()
    }

    private fun localeOf(sender: CommandSender?): String {
        if (sender is Player) {
            return sender.locale().language.ifBlank { defaultLocale }
        }
        return defaultLocale
    }
}
