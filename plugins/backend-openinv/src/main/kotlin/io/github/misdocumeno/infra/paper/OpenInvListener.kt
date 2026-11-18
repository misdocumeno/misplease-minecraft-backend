package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.OpenInvPlugin
import io.github.misdocumeno.domain.GuiMapping
import io.github.misdocumeno.domain.InventoryKind
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory

/**
 * Routes GUI interaction through the session that owns the view.
 *
 * Vanilla keeps handling the actual moves (shift-click, number keys, drag,
 * double-click collect), so every shortcut behaves like a chest. We only block
 * pad slots, item drops and pad-collecting, then reconcile changed slots.
 */
class OpenInvListener(private val plugin: OpenInvPlugin) : Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val session = plugin.sessions.find(player.uniqueId) ?: return
        if (event.view.topInventory !== session.gui) return
        if (session.isSettling()) {
            event.isCancelled = true
            return
        }

        val top = session.gui
        if (GuiPads.isPad(event.currentItem) || isPadSlot(event.rawSlot, top, session.kind)) {
            event.isCancelled = true
            return
        }

        if (event.click == ClickType.DROP || event.click == ClickType.CONTROL_DROP) {
            event.isCancelled = true
            return
        }

        // Double-click collect would scoop up pad panes along with a real gray
        // pane held on the cursor; those stacks cannot be told apart afterwards.
        if (event.click == ClickType.DOUBLE_CLICK &&
            event.cursor?.type == Material.GRAY_STAINED_GLASS_PANE
        ) {
            event.isCancelled = true
            return
        }

        resync(session.viewer)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val session = plugin.sessions.find(player.uniqueId) ?: return
        if (event.view.topInventory !== session.gui) return
        if (session.isSettling()) {
            event.isCancelled = true
            return
        }

        val topSize = session.gui.size
        val padSlots = GuiMapping.pads(session.kind).toSet()
        if (event.rawSlots.any { it < topSize && it in padSlots }) {
            event.isCancelled = true
            return
        }
        if (event.rawSlots.any { it < topSize }) resync(session.viewer)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        // Never let a mirrored item end up in the world (Q while the GUI is open).
        if (plugin.sessions.find(event.player.uniqueId) != null) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val session = plugin.sessions.find(player.uniqueId) ?: return
        if (event.view.topInventory !== session.gui) return
        GuiPads.sanitize(session.gui, session.kind)
        session.finish(closeView = false)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        plugin.sessions.find(event.player.uniqueId)?.finish(closeView = false)
    }

    private fun isPadSlot(rawSlot: Int, top: Inventory, kind: InventoryKind): Boolean =
        rawSlot in 0 until top.size && rawSlot in GuiMapping.pads(kind)

    private fun resync(viewer: java.util.UUID) {
        // Bukkit applies the click result after the event; reconcile next tick.
        plugin.server.scheduler.runTask(plugin, Runnable {
            val player = org.bukkit.Bukkit.getPlayer(viewer) ?: return@Runnable
            val session = plugin.sessions.find(viewer) ?: return@Runnable
            if (!session.isOpenFor(player)) return@Runnable
            session.syncNow()
            GuiPads.sanitize(session.gui, session.kind)
        })
    }
}
