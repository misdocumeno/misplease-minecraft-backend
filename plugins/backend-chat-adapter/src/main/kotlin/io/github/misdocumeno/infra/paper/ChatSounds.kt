package io.github.misdocumeno.infra.paper

import org.bukkit.Sound
import org.bukkit.configuration.file.FileConfiguration

data class ChatSounds(
    val chat: Sound,
    val volume: Float,
    val pitch: Float,
) {
    companion object {
        fun from(config: FileConfiguration): ChatSounds {
            val section = config.getConfigurationSection("sounds")
                ?: error("config.yml missing sounds section")
            val chat = section.getString("chat") ?: "ENTITY_ITEM_PICKUP"
            return ChatSounds(
                chat = Sound.valueOf(chat),
                volume = section.getDouble("volume", 1.0).toFloat(),
                pitch = section.getDouble("pitch", 1.0).toFloat(),
            )
        }
    }
}