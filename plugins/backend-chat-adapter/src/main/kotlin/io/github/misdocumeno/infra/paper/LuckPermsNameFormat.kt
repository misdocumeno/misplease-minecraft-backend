package io.github.misdocumeno.infra.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.cacheddata.CachedMetaData
import org.bukkit.entity.Player

/**
 * Builds a MiniMessage name out of the player's nickname plus LuckPerms
 * prefix/suffix.
 *
 * The nickname is the `nickname` LuckPerms meta node set by the proxy
 * `velocity-social` `/nickname` command. Because it is LuckPerms meta, it is the
 * same on every backend and survives a restart; no per-server Essentials userdata
 * is involved. It is rendered with a leading `~` so nobody mistakes a nickname for
 * the real username (same marker Essentials used). Without a nickname the plain
 * display name is used, which is the username (`change-displayname` is off in
 * Essentials so it cannot inject a per-server nickname).
 *
 * Prefix/suffix stay outside the nickname on purpose: the backend runs with
 * `add-prefix-suffix: false` and `ops-name-color: 'none'`, so a nickname cannot
 * double the colours. Default group is `&3`; personal colours are per-user LP
 * prefixes (higher priority than `default`).
 */
object LuckPermsNameFormat {
    private val mm = MiniMessage.miniMessage()
    private val legacy = LegacyComponentSerializer.legacyAmpersand()

    fun miniMessageName(player: Player): String {
        // The display name is a child of the prefix, not a sibling: a sibling would
        // drop the prefix colour on an uncoloured name (`&3` is a colour-only stub).
        // A coloured nickname carries its own style and overrides the inherited one.
        val meta = metadata(player)
        val nickname = meta?.getMetaValue(NICKNAME_META)?.takeIf { it.isNotBlank() }
        val display = if (nickname != null) legacy.deserialize("~$nickname") else player.displayName()
        val name = legacy.deserialize(meta?.prefix.orEmpty()).append(display)
        val suffix = legacy.deserialize(meta?.suffix.orEmpty())
        return mm.serialize(Component.text().append(name).append(suffix).build())
    }

    private fun metadata(player: Player): CachedMetaData? =
        runCatching {
            LuckPermsProvider.get()
                .getPlayerAdapter(Player::class.java)
                .getMetaData(player)
        }.getOrNull()

    private const val NICKNAME_META = "nickname"
}
