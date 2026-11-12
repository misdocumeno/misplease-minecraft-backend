package io.github.misdocumeno.infra.bukkit

import io.github.misdocumeno.application.PlayerBlocker
import io.github.misdocumeno.application.PlayerHandle
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BukkitPlayerBlocker(private val plugin: Plugin) : PlayerBlocker {

    private val hidden = ConcurrentHashMap.newKeySet<UUID>()

    override fun block(player: PlayerHandle) = onMain {
        val p = (player as BukkitPlayerHandle).unwrap()
        hidden.add(p.uniqueId)

        Bukkit.getOnlinePlayers().forEach {
            it.hidePlayer(plugin, p)
        }

        p.isInvisible = true
        p.isInvulnerable = true
        p.isCollidable = false
        p.canPickupItems = false

        p.addPotionEffect(
            PotionEffect(
                PotionEffectType.BLINDNESS,
                Int.MAX_VALUE,
                1,
                true,
                false,
                false,
            )
        )
    }

    override fun unblock(player: PlayerHandle) = onMain {
        val p = (player as BukkitPlayerHandle).unwrap()
        val wasHidden = hidden.remove(p.uniqueId)

        p.isInvisible = false
        p.isInvulnerable = false
        p.isCollidable = true
        p.canPickupItems = true

        p.removePotionEffect(PotionEffectType.BLINDNESS)

        // PlayerCommandSendEvent left only /login while unauthenticated; refresh Brigadier.
        p.updateCommands()

        if (!wasHidden) return@onMain

        // The entity tracker sends the spawn packet after PlayerJoinEvent.
        // showPlayer in that same moment is dropped, and the player stays invisible.
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!p.isOnline || p.uniqueId in hidden) return@Runnable
            Bukkit.getOnlinePlayers().forEach { it.showPlayer(plugin, p) }
        }, 5L)
    }

    private fun onMain(block: () -> Unit) {
        if (Bukkit.isPrimaryThread()) {
            block()
        } else {
            Bukkit.getScheduler().runTask(plugin, block)
        }
    }
}