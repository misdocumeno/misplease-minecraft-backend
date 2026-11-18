package io.github.misdocumeno.domain

/**
 * What an OpenInv session mirrors.
 *
 * [INVENTORY] uses the Bukkit player inventory slot space:
 * `0..8` hotbar, `9..35` storage, `36..39` armor (boots, leggings, chestplate,
 * helmet) and `40` the off hand. [ENDER_CHEST] is the vanilla 27 slots.
 */
enum class InventoryKind(val size: Int) {
    INVENTORY(41),
    ENDER_CHEST(27),
}
