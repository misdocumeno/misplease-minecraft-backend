package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.domain.GuiMapping
import io.github.misdocumeno.domain.InventoryKind
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/**
 * Pad items (gray panes) that keep armor and offhand apart from storage.
 *
 * Pads are marked with a persistent-data tag so they can be told apart from a
 * real gray pane and are never collectable, draggable or written to a player.
 */
object GuiPads {

    val key = NamespacedKey("openinv", "pad")

    fun item(): ItemStack {
        val stack = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        val meta = stack.itemMeta
        meta.displayName(Component.empty())
        meta.persistentDataContainer.set(key, PersistentDataType.BYTE, 1)
        stack.itemMeta = meta
        return stack
    }

    fun isPad(item: ItemStack?): Boolean {
        val meta = item?.itemMeta ?: return false
        return meta.persistentDataContainer.has(key, PersistentDataType.BYTE)
    }

    fun stamp(inventory: Inventory, kind: InventoryKind) {
        val pad = item()
        for (slot in GuiMapping.pads(kind)) inventory.setItem(slot, pad.clone())
    }

    /** Re-stamps pads and removes any pad item that escaped into a real slot. */
    fun sanitize(inventory: Inventory, kind: InventoryKind) {
        val padSlots = GuiMapping.pads(kind).toSet()
        for (slot in 0 until inventory.size) {
            if (slot in padSlots) continue
            if (isPad(inventory.getItem(slot))) inventory.setItem(slot, null)
        }
        stamp(inventory, kind)
    }
}
