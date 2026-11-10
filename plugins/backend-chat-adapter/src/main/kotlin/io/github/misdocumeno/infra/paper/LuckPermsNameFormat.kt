package io.github.misdocumeno.infra.paper

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.luckperms.api.LuckPermsProvider
import org.bukkit.entity.Player

/**
 * Builds a MiniMessage name using LuckPerms prefix/suffix (same source Essentials
 * uses for `{DISPLAYNAME}` when `add-prefix-suffix` is on). Prod `default` is `&3`.
 */
object LuckPermsNameFormat {
    private val mm = MiniMessage.miniMessage()
    private val legacy = LegacyComponentSerializer.legacyAmpersand()

    fun miniMessageName(player: Player): String {
        val prefix = prefixOf(player).orEmpty()
        val suffix = suffixOf(player).orEmpty()
        return mm.serialize(legacy.deserialize("$prefix${player.name}$suffix"))
    }

    private fun prefixOf(player: Player): String? =
        runCatching {
            LuckPermsProvider.get()
                .getPlayerAdapter(Player::class.java)
                .getMetaData(player)
                .prefix
        }.getOrNull()

    private fun suffixOf(player: Player): String? =
        runCatching {
            LuckPermsProvider.get()
                .getPlayerAdapter(Player::class.java)
                .getMetaData(player)
                .suffix
        }.getOrNull()
}
