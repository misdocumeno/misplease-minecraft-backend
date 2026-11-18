package io.github.misdocumeno.infra.velocity

import com.velocitypowered.api.proxy.Player
import io.github.misdocumeno.domain.Nicknames
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.cacheddata.CachedMetaData

/**
 * Proxy-side name for `/list` and private messages: LuckPerms prefix + nickname.
 *
 * The nickname is the `nickname` LuckPerms meta node set by [NicknameCommand], the
 * same value the backend chat/tab-list formatters read, so a nickname looks
 * identical everywhere. The `~` marks it as a nickname (it is not the real
 * username) and matches what chat shows.
 */
object NameFormat {
    private val legacy = LegacyComponentSerializer.legacyAmpersand()

    fun colored(player: Player): Component {
        val meta = metadata(player)
        val prefix = meta?.prefix.orEmpty()
        val nickname = meta?.getMetaValue(Nicknames.META_KEY)?.takeIf { it.isNotBlank() }
        val name = if (nickname != null) Nicknames.display(nickname) else player.username
        return legacy.deserialize(prefix + name)
    }

    private fun metadata(player: Player): CachedMetaData? =
        runCatching {
            LuckPermsProvider.get()
                .getPlayerAdapter(Player::class.java)
                .getMetaData(player)
        }.getOrNull()
}
