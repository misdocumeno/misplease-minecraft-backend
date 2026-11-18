package io.github.misdocumeno.domain

/**
 * GUI slot layout (pure, no Bukkit types so it stays unit-testable).
 *
 * `/openinv` (45 slots): row 1 = helmet, chestplate, leggings, boots, pad,
 * offhand, pad, pad, pad; rows 2-4 = storage 9..35; row 5 = hotbar 0..8.
 * `/openender` is the vanilla 27-slot layout.
 */
object GuiMapping {

    private val INVENTORY_PADS = listOf(4, 6, 7, 8)

    fun size(kind: InventoryKind): Int = if (kind == InventoryKind.INVENTORY) 45 else 27

    fun pads(kind: InventoryKind): List<Int> =
        if (kind == InventoryKind.INVENTORY) INVENTORY_PADS else emptyList()

    /** Player inventory index -> GUI slot. */
    fun toGui(kind: InventoryKind, index: Int): Int? =
        when (kind) {
            InventoryKind.ENDER_CHEST -> index.takeIf { it in 0..26 }
            InventoryKind.INVENTORY -> when (index) {
                in 0..8 -> 36 + index
                in 9..35 -> index
                36 -> 3 // boots
                37 -> 2 // leggings
                38 -> 1 // chestplate
                39 -> 0 // helmet
                40 -> 5 // off hand
                else -> null
            }
        }

    /** GUI slot -> player inventory index; null for pads and unrelated slots. */
    fun toPlayer(kind: InventoryKind, slot: Int): Int? =
        when (kind) {
            InventoryKind.ENDER_CHEST -> slot.takeIf { it in 0..26 }
            InventoryKind.INVENTORY -> when (slot) {
                in 9..35 -> slot
                in 36..44 -> slot - 36
                3 -> 36
                2 -> 37
                1 -> 38
                0 -> 39
                5 -> 40
                else -> null
            }
        }
}
