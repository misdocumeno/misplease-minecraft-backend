package io.github.misdocumeno.infra.paper

import org.bukkit.Sound
import org.bukkit.configuration.file.FileConfiguration

data class AnnounceSounds(
    val join: Sound,
    val leave: Sound,
    val volume: Float,
    val pitch: Float,
) {
    companion object {
        fun from(config: FileConfiguration): AnnounceSounds {
            val section = config.getConfigurationSection("sounds")
                ?: error("config.yml missing sounds section")
            val join = section.getString("join") ?: "BLOCK_NOTE_BLOCK_PLING"
            val leave = section.getString("leave") ?: "BLOCK_NOTE_BLOCK_BASS"
            return AnnounceSounds(
                join = Sound.valueOf(join),
                leave = Sound.valueOf(leave),
                volume = section.getDouble("volume", 1.0).toFloat(),
                pitch = section.getDouble("pitch", 1.0).toFloat(),
            )
        }
    }
}