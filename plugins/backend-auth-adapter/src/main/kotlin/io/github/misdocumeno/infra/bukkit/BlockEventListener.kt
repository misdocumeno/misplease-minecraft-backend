package io.github.misdocumeno.infra.bukkit

import io.github.misdocumeno.application.SessionManager
import io.github.misdocumeno.domain.AuthState
import io.papermc.paper.event.player.AsyncChatEvent
import io.papermc.paper.event.player.PlayerPickItemEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent

// TODO: check AuthMe code
class BlockEventListener(private val sessionManager: SessionManager) : Listener {

    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        val session = sessionManager.get(event.player.uniqueId) ?: return

        if (session.snapshot().authState != AuthState.AUTHENTICATED) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onChat(event: AsyncChatEvent) {
        val session = sessionManager.get(event.player.uniqueId) ?: return

        if (session.snapshot().authState != AuthState.AUTHENTICATED) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onCommandPreprocess(event: PlayerCommandPreprocessEvent) {
        val session = sessionManager.get(event.player.uniqueId) ?: return

        if (session.snapshot().authState != AuthState.AUTHENTICATED && event.message.split(' ').first() != "/login") {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val session = sessionManager.get(event.whoClicked.uniqueId) ?: return

        if (session.snapshot().authState != AuthState.AUTHENTICATED) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onInventoryOpen(event: InventoryOpenEvent) {
        val session = sessionManager.get(event.player.uniqueId) ?: return

        if (session.snapshot().authState != AuthState.AUTHENTICATED) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val session = sessionManager.get(event.player.uniqueId) ?: return

        if (session.snapshot().authState != AuthState.AUTHENTICATED) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onPlayerDropItem(event: PlayerDropItemEvent) {
        val session = sessionManager.get(event.player.uniqueId) ?: return

        if (session.snapshot().authState != AuthState.AUTHENTICATED) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onPlayerPickItem(event: PlayerPickItemEvent) {
        val session = sessionManager.get(event.player.uniqueId) ?: return

        if (session.snapshot().authState != AuthState.AUTHENTICATED) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onPlayerSwapHandItems(event: PlayerSwapHandItemsEvent) {
        val session = sessionManager.get(event.player.uniqueId) ?: return

        if (session.snapshot().authState != AuthState.AUTHENTICATED) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onPlayerArmorStandManipulate(event: PlayerArmorStandManipulateEvent) {
        val session = sessionManager.get(event.player.uniqueId) ?: return

        if (session.snapshot().authState != AuthState.AUTHENTICATED) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onPlayerInteractAtEntity(event: PlayerInteractAtEntityEvent) {
        val session = sessionManager.get(event.player.uniqueId) ?: return

        if (session.snapshot().authState != AuthState.AUTHENTICATED) {
            event.isCancelled = true
        }
    }
}