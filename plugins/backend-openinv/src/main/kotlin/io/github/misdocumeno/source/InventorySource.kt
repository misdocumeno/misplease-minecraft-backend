package io.github.misdocumeno.source

import io.github.misdocumeno.domain.InventoryKind
import io.github.misdocumeno.domain.Snapshot
import org.bukkit.inventory.ItemStack

/**
 * One storage backend for a session. Exactly one source is active per read or
 * write; the session re-resolves it before every operation.
 */
interface InventorySource {
    val server: String
    val group: String

    /** True when the source is the live player inventory (main thread only). */
    val live: Boolean

    fun read(): Snapshot

    /** Writes only the changed slots. Live sources run on the main thread. */
    fun apply(changed: Map<Int, ItemStack?>)
}

/** Live player: the only writer while they are online in the session group. */
class LiveSource(
    private val player: org.bukkit.entity.Player,
    private val kind: InventoryKind,
    override val server: String,
    override val group: String,
) : InventorySource {

    override val live: Boolean = true

    override fun read(): Snapshot {
        val items = ArrayList<ItemStack?>(kind.size)
        if (kind == InventoryKind.INVENTORY) {
            val inventory = player.inventory
            for (i in 0 until 36) items.add(inventory.getItem(i)?.clone())
            val armor = inventory.armorContents
            for (i in 0 until 4) items.add(armor?.getOrNull(i)?.clone())
            items.add(inventory.itemInOffHand?.clone()?.takeIf { !it.type.isAir })
        } else {
            val ender = player.enderChest
            for (i in 0 until 27) items.add(ender.getItem(i)?.clone()?.takeIf { !it.type.isAir })
        }
        return Snapshot(kind, items, io.github.misdocumeno.domain.ItemCodec.version())
    }

    override fun apply(changed: Map<Int, ItemStack?>) {
        if (kind == InventoryKind.INVENTORY) {
            val inventory = player.inventory
            for ((index, item) in changed) {
                when {
                    index < 36 -> inventory.setItem(index, item?.clone())
                    index < 40 -> {
                        val armor = inventory.armorContents ?: arrayOfNulls(4)
                        armor[index - 36] = item?.clone()
                        inventory.armorContents = armor
                    }
                    index == 40 -> inventory.setItemInOffHand(item?.clone())
                }
            }
        } else {
            val ender = player.enderChest
            for ((index, item) in changed) {
                if (index in 0 until 27) ender.setItem(index, item?.clone())
            }
        }
    }
}
