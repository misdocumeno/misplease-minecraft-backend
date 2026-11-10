package io.github.misdocumeno.infra.velocity

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.luckperms.api.LuckPermsProvider

object NameFormat {
    private val legacy = LegacyComponentSerializer.legacyAmpersand()

    fun colored(player: Player): Component {
        val prefix = runCatching {
            LuckPermsProvider.get()
                .getPlayerAdapter(Player::class.java)
                .getMetaData(player)
                .prefix
        }.getOrNull().orEmpty()
        return legacy.deserialize(prefix + player.username)
    }
}
