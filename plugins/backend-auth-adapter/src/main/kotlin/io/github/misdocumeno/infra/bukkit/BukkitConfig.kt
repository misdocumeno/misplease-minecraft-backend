package io.github.misdocumeno.infra.bukkit

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Sound
import org.bukkit.plugin.Plugin

class BukkitConfig(private val plugin: Plugin) {

    private val mm = MiniMessage.miniMessage()
    private val config get() = plugin.config

    fun unauthenticatedTitle() = color(config.getString("messages.unauthenticated.title")!!)
    fun unauthenticatedSubtitle() = color(config.getString("messages.unauthenticated.subtitle")!!)
    fun authenticatedTitle() = color(config.getString("messages.authenticated.title")!!)
    fun authenticatedSubtitle() = color(config.getString("messages.authenticated.subtitle")!!)
    fun wrongPasswordMessage() = color(config.getString("messages.wrong-password")!!)
    fun timeoutKickMessage() = color(config.getString("messages.kick-timeout")!!)
    fun alreadyLoggedInMessage() = color(config.getString("messages.already-logged-in")!!)

    fun wrongPasswordSound() = config.getString("sounds.wrong-password")?.let { Sound.valueOf(it) }
    fun alreadyLoggedInSound() = config.getString("sounds.already-logged-in")?.let { Sound.valueOf(it) }

    private fun color(raw: String) = mm.deserialize(raw)
}