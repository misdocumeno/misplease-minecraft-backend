package io.github.misdocumeno.infra.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.luckperms.api.LuckPermsProvider
import org.bukkit.entity.Player

/**
 * Builds a MiniMessage name out of the player's display name plus LuckPerms
 * prefix/suffix (the same three pieces Essentials renders for `{DISPLAYNAME}`).
 *
 * The name is `player.displayName()`, not `player.name`: Essentials' `nickname`
 * (`/nickname`, `change-displayname: true`) and any other plugin that sets a chat
 * display name write it there, so `/nickname &bMasterAngel` reaches global chat
 * without this plugin knowing about Essentials. With no nickname the display name
 * is just the plain username.
 *
 * Prefix/suffix stay outside the display name on purpose: Essentials runs with
 * `add-prefix-suffix: false` and `ops-name-color: 'none'`, so a display name never
 * contains them and a nickname cannot double the colours. (Op colouring is off
 * because it made every op's chat name dark red, over the LP prefix.) Default group
 * is `&3`; personal colours are per-user LP prefixes (higher priority than `default`).
 */
object LuckPermsNameFormat {
    private val mm = MiniMessage.miniMessage()
    private val legacy = LegacyComponentSerializer.legacyAmpersand()

    fun miniMessageName(player: Player): String {
        // The display name is a child of the prefix, not a sibling: a sibling would
        // drop the prefix colour on an uncoloured name (`&3` is a colour-only stub).
        // A coloured nickname carries its own style and overrides the inherited one.
        val name = legacy.deserialize(prefixOf(player).orEmpty()).append(player.displayName())
        val suffix = legacy.deserialize(suffixOf(player).orEmpty())
        return mm.serialize(Component.text().append(name).append(suffix).build())
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
