package io.github.misdocumeno

import io.github.misdocumeno.domain.InventoryKind
import io.github.misdocumeno.domain.GuiMapping
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The GUI mapping is the one place where an off-by-one silently splits or
 * duplicates items (armor written into storage, hotbar into the wrong row).
 */
class GuiMappingTest {

    @Test
    fun `inventory mapping is a bijection between player slots and gui slots`() {
        val playerSlots = (0 until InventoryKind.INVENTORY.size).mapNotNull { GuiMapping.toGui(InventoryKind.INVENTORY, it) }
        assertEquals(41, playerSlots.size)
        assertEquals(41, playerSlots.toSet().size, "player slots map to distinct gui slots")

        for (guiSlot in playerSlots) {
            val playerIndex = GuiMapping.toPlayer(InventoryKind.INVENTORY, guiSlot)
            assertEquals(guiSlot, GuiMapping.toGui(InventoryKind.INVENTORY, playerIndex!!))
        }
    }

    @Test
    fun `storage rows keep their index and hotbar goes to the last row`() {
        for (index in 9..35) assertEquals(index, GuiMapping.toGui(InventoryKind.INVENTORY, index))
        for (index in 0..8) assertEquals(36 + index, GuiMapping.toGui(InventoryKind.INVENTORY, index))
    }

    @Test
    fun `armor is head to feet and offhand sits after the armor`() {
        val helmet = 39
        val chestplate = 38
        val leggings = 37
        val boots = 36
        assertEquals(0, GuiMapping.toGui(InventoryKind.INVENTORY, helmet))
        assertEquals(1, GuiMapping.toGui(InventoryKind.INVENTORY, chestplate))
        assertEquals(2, GuiMapping.toGui(InventoryKind.INVENTORY, leggings))
        assertEquals(3, GuiMapping.toGui(InventoryKind.INVENTORY, boots))
        assertEquals(5, GuiMapping.toGui(InventoryKind.INVENTORY, 40))
    }

    @Test
    fun `pads and unrelated slots have no player index`() {
        for (pad in GuiMapping.pads(InventoryKind.INVENTORY)) {
            assertNull(GuiMapping.toPlayer(InventoryKind.INVENTORY, pad))
        }
        assertNull(GuiMapping.toPlayer(InventoryKind.INVENTORY, 44 + 1))
    }

    @Test
    fun `ender chest is the vanilla layout`() {
        for (slot in 0..26) {
            assertEquals(slot, GuiMapping.toGui(InventoryKind.ENDER_CHEST, slot))
            assertEquals(slot, GuiMapping.toPlayer(InventoryKind.ENDER_CHEST, slot))
        }
        assertNull(GuiMapping.toGui(InventoryKind.ENDER_CHEST, 27))
        assertEquals(27, GuiMapping.size(InventoryKind.ENDER_CHEST))
    }
}
