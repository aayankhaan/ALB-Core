package com.aayan.albcore.gui

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.inventory.ItemStack

/**
 * Locks every GuiBuilder slot except item-input slots, and runs click actions.
 * Registered once by ALBCore; child plugins do not register it.
 */
internal class GuiListener : Listener {

    @EventHandler(priority = EventPriority.LOW)
    fun onClick(event: InventoryClickEvent) {
        val gui = event.inventory.getHolder(false) as? GuiBuilder ?: return
        val player = event.whoClicked as? Player ?: return

        // Double-click collects matching items from the whole view, including locked GUI slots.
        if (event.action == InventoryAction.COLLECT_TO_CURSOR) {
            event.isCancelled = true
            return
        }

        val rawSlot = event.rawSlot
        if (rawSlot < 0) return // clicked outside the window

        val topSize = event.view.topInventory.size
        if (rawSlot >= topSize) {
            // Player inventory. Shift-click would put items into any free GUI slot, so move them ourselves.
            if (event.action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                event.isCancelled = true
                val clicked = event.currentItem
                if (clicked != null && !clicked.isEmpty && gui.interactiveSlots.isNotEmpty()) {
                    val leftover = gui.insertIntoInteractiveSlots(clicked)
                    event.clickedInventory?.setItem(event.slot, leftover)
                }
            }
            return
        }

        if (rawSlot in gui.interactiveSlots) {
            val incoming = incomingItem(event, player)
            if (incoming != null && !incoming.isEmpty && !gui.accepts(rawSlot, incoming)) {
                event.isCancelled = true
            }
            return
        }

        event.isCancelled = true
        gui.handleClick(rawSlot, player)
    }

    @EventHandler(priority = EventPriority.LOW)
    fun onDrag(event: InventoryDragEvent) {
        val gui = event.inventory.getHolder(false) as? GuiBuilder ?: return
        val topSize = event.view.topInventory.size
        val touched = event.rawSlots.filter { it < topSize }
        if (touched.isEmpty()) return
        if (touched.all { gui.accepts(it, event.oldCursor) }) return
        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onOpen(event: InventoryOpenEvent) {
        val gui = event.inventory.getHolder(false) as? GuiBuilder ?: return
        val player = event.player as? Player ?: return
        gui.handleOpen(player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        val gui = event.inventory.getHolder(false) as? GuiBuilder ?: return
        val player = event.player as? Player ?: return
        gui.handleClose(player)
    }

    /** The item a click would put into the clicked slot, or null if the click only takes items out. */
    private fun incomingItem(event: InventoryClickEvent, player: Player): ItemStack? = when (event.action) {
        InventoryAction.PLACE_ALL,
        InventoryAction.PLACE_ONE,
        InventoryAction.PLACE_SOME,
        InventoryAction.SWAP_WITH_CURSOR -> event.cursor

        InventoryAction.HOTBAR_SWAP ->
            if (event.click == ClickType.SWAP_OFFHAND || event.hotbarButton < 0) player.inventory.itemInOffHand
            else player.inventory.getItem(event.hotbarButton)

        else -> null
    }
}
