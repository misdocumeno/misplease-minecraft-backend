package io.github.misdocumeno.infra.velocity

import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.domain.Nicknames
import io.github.misdocumeno.infra.redis.RedisAuthSet
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.node.NodeType
import net.luckperms.api.node.types.MetaNode
import org.slf4j.Logger
import java.util.UUID

/**
 * Network-wide `/nickname`.
 *
 * The nickname is a LuckPerms meta node (`nickname`), so it lives in the same
 * storage and travels over the same LuckPerms messaging service as prefixes and
 * suffixes: one write on the proxy, every backend updates within a moment. Chat
 * and the tab list both read it through their name formatters, which means a
 * nickname no longer depends on which backend (or Essentials userdata) the
 * player happens to be on.
 *
 * `/nickname off` clears it. Everyone may nick themselves; `misplease.nickname.others`
 * edits other players (online or offline, LuckPerms resolves the name), and
 * `misplease.nickname.color` allows `&` colour codes. The legacy
 * `essentials.nickname.*` nodes are accepted too so existing grants keep working
 * until the groups are migrated.
 */
class NicknameCommand(
    private val proxy: ProxyServer,
    private val auth: RedisAuthSet,
    private val logger: Logger,
) : SimpleCommand {

    private val legacy = LegacyComponentSerializer.legacyAmpersand()

    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        val args = invocation.arguments()

        if (source is Player && !auth.isAuthenticated(source.username)) {
            source.sendMessage(Component.text("Log in first.", NamedTextColor.GRAY))
            return
        }

        when {
            args.isEmpty() -> showOwn(source)
            args.size == 1 -> {
                val self = source as? Player
                if (self == null) {
                    source.sendMessage(Component.text("Console must name a player.", NamedTextColor.GRAY))
                    return
                }
                change(source, self.uniqueId, self.username, args[0])
            }
            args.size == 2 -> {
                if (!canEditOthers(source)) {
                    source.sendMessage(Component.text("You cannot change other players' nicknames.", NamedTextColor.GRAY))
                    return
                }
                resolve(args[0]) { uuid, name ->
                    if (uuid == null) {
                        notFound(source, args[0])
                    } else {
                        change(source, uuid, name, args[1])
                    }
                }
            }
            else -> usage(source)
        }
    }

    override fun suggest(invocation: SimpleCommand.Invocation): List<String> {
        val args = invocation.arguments()
        val source = invocation.source()
        return when {
            args.isEmpty() -> listOf("off")
            args.size == 1 && canEditOthers(source) ->
                (listOf("off") + proxy.allPlayers.map { it.username })
                    .filter { it.startsWith(args[0], ignoreCase = true) }
            args.size == 2 && canEditOthers(source) -> listOf("off")
            else -> emptyList()
        }
    }

    private fun showOwn(source: CommandSource) {
        val player = source as? Player
        if (player == null) {
            source.sendMessage(Component.text("Console has no nickname.", NamedTextColor.GRAY))
            return
        }
        val nickname = currentNickname(player.uniqueId)
        if (nickname.isNullOrBlank()) {
            source.sendMessage(Component.text("You have no nickname.", NamedTextColor.GRAY))
            return
        }
        source.sendMessage(
            Component.text("Your nickname: ", NamedTextColor.GRAY)
                .append(legacy.deserialize(Nicknames.display(nickname))),
        )
    }

    private fun change(source: CommandSource, uuid: UUID, name: String, raw: String) {
        if (Nicknames.isClear(raw)) {
            write(source, uuid, name, null)
            return
        }
        val nickname = Nicknames.normalize(raw, canUseColour(source)) ?: run {
            source.sendMessage(
                Component.text(
                    "Nickname must be 1-${Nicknames.MAX_VISIBLE} letters, numbers or underscores.",
                    NamedTextColor.RED,
                ),
            )
            return
        }
        write(source, uuid, name, nickname)
    }

    private fun write(source: CommandSource, uuid: UUID, name: String, nickname: String?) {
        val luckPerms = runCatching { LuckPermsProvider.get() }.getOrNull()
        if (luckPerms == null) {
            source.sendMessage(Component.text("LuckPerms is not available.", NamedTextColor.RED))
            return
        }
        luckPerms.userManager.modifyUser(uuid) { user ->
            user.data().clear(NodeType.META.predicate { it.metaKey == Nicknames.META_KEY })
            if (nickname != null) {
                user.data().add(MetaNode.builder(Nicknames.META_KEY, nickname).build())
            }
        }.whenComplete { _, error ->
            if (error != null) {
                logger.warn("nickname update failed for {}: {}", name, error.message)
                source.sendMessage(Component.text("Could not save the nickname.", NamedTextColor.RED))
            } else if (nickname == null) {
                source.sendMessage(
                    Component.text("Cleared the nickname of ", NamedTextColor.GRAY)
                        .append(Component.text(name, NamedTextColor.WHITE))
                        .append(Component.text(".", NamedTextColor.GRAY)),
                )
            } else {
                source.sendMessage(
                    Component.text("Nickname set to ", NamedTextColor.GRAY)
                        .append(legacy.deserialize(Nicknames.display(nickname))),
                )
            }
        }
    }

    private fun resolve(name: String, then: (UUID?, String) -> Unit) {
        val online = proxy.getPlayer(name).orElse(null)
        if (online != null) {
            then(online.uniqueId, online.username)
            return
        }
        val luckPerms = runCatching { LuckPermsProvider.get() }.getOrNull()
        if (luckPerms == null) {
            then(null, name)
            return
        }
        // LuckPerms also resolves players that are offline, which is how an admin
        // can fix a nickname without waiting for the player to connect.
        luckPerms.userManager.lookupUniqueId(name).whenComplete { uuid, error ->
            if (error != null) logger.warn("nickname lookup failed for {}: {}", name, error.message)
            then(uuid, name)
        }
    }

    private fun currentNickname(uuid: UUID): String? =
        runCatching {
            LuckPermsProvider.get().userManager.getUser(uuid)?.cachedData
                ?.getMetaData()
                ?.getMetaValue(Nicknames.META_KEY)
        }.getOrNull()

    private fun canUseColour(source: CommandSource): Boolean =
        source.hasPermission("misplease.nickname.color") ||
            source.hasPermission("essentials.nickname.color")

    private fun canEditOthers(source: CommandSource): Boolean =
        source.hasPermission("misplease.nickname.others") ||
            source.hasPermission("essentials.nickname.others")

    private fun notFound(source: CommandSource, name: String) {
        source.sendMessage(
            Component.text("Unknown player ", NamedTextColor.GRAY)
                .append(Component.text(name, NamedTextColor.WHITE))
                .append(Component.text(".", NamedTextColor.GRAY)),
        )
    }

    private fun usage(source: CommandSource) {
        source.sendMessage(
            Component.text("Usage: /nickname <name|off> or /nickname <player> <name|off>", NamedTextColor.GRAY),
        )
    }
}

