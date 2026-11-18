package io.github.misdocumeno.domain

import org.bukkit.inventory.ItemStack

/** One read of a source: the 41/27 slots plus the MC version they came from. */
data class Snapshot(
    val kind: InventoryKind,
    val items: List<ItemStack?>,
    val mcVersion: String,
) {
    init {
        require(items.size == kind.size) { "expected ${kind.size} slots, got ${items.size}" }
    }

    fun copy(): Snapshot = Snapshot(kind, items.map { it?.clone() }, mcVersion)
}
