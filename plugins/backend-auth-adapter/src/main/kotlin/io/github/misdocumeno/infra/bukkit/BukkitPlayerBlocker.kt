package io.github.misdocumeno.infra.bukkit

import io.github.misdocumeno.application.PlayerBlocker
import io.github.misdocumeno.application.PlayerHandle
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class BukkitPlayerBlocker(private val plugin: Plugin) : PlayerBlocker {

    override fun block(player: PlayerHandle) = onMain {
        val p = (player as BukkitPlayerHandle).unwrap()

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

        Bukkit.getOnlinePlayers().forEach {
            it.showPlayer(plugin, p)
        }

        p.isInvisible = false
        p.isInvulnerable = false
        p.isCollidable = true
        p.canPickupItems = true

        p.removePotionEffect(PotionEffectType.BLINDNESS)
    }

    private fun onMain(block: () -> Unit) {
        if (Bukkit.isPrimaryThread()) {
            block()
        } else {
            Bukkit.getScheduler().runTask(plugin, block)
        }
    }
}
