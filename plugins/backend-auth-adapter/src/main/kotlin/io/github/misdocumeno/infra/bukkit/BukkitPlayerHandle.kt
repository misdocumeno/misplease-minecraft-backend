package io.github.misdocumeno.infra.bukkit

import io.github.misdocumeno.application.PlayerHandle
import org.bukkit.entity.Player

class BukkitPlayerHandle(private val player: Player) : PlayerHandle {
    override val username = player.name
    fun unwrap() = player
}