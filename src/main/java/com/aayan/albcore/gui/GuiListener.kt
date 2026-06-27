package com.aayan.albcore.gui

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent

class GuiListener : Listener {

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder
        if (holder !is GuiBuilder) return

        val slot = event.slot
        val player = event.whoClicked as? Player ?: return

        if (event.rawSlot >= event.inventory.size) {
            if (event.isShiftClick) {
                if (holder.interactiveSlots.isEmpty()) {
                    event.isCancelled = true
                    return
                }
                val item = event.currentItem ?: return
                val firstInteractiveSlot = holder.interactiveSlots.first()
                val filter = holder.slotFilters[firstInteractiveSlot]
                if (filter != null && !filter(item)) {
                    event.isCancelled = true
                }
            }
            return
        }

        if (slot in holder.interactiveSlots) {
            val cursor = event.cursor
            if (cursor.type != Material.AIR) {
                val filter = holder.slotFilters[slot]
                if (filter != null && !filter(cursor)) {
                    event.isCancelled = true
                }
            }
            return
        }

        event.isCancelled = true

        holder.actions[slot]?.invoke(player)

        if (holder.pages.isNotEmpty() || holder.globalScope != null) {
            val ctx = PageContext(holder.currentPage, holder.pages.size, holder)
            holder.pageActions[slot]?.invoke(player, ctx)
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        val holder = event.inventory.holder
        if (holder !is GuiBuilder) return

        val guiSize = event.inventory.size
        val touchedGuiSlots = event.rawSlots.filter { it < guiSize }

        if (touchedGuiSlots.isEmpty()) return

        if (touchedGuiSlots.all { slot ->
                slot in holder.interactiveSlots &&
                        holder.slotFilters[slot]?.invoke(event.oldCursor) != false
            }) return

        event.isCancelled = true
    }

    @EventHandler
    fun onGuiOpen(event: InventoryOpenEvent) {
        val holder = event.inventory.holder
        if (holder !is GuiBuilder) return

        val player = event.player as? Player ?: return
        holder.refresh()
        holder.onOpenAction?.invoke(player)
    }

    @EventHandler
    fun onGuiClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder
        if (holder !is GuiBuilder) return

        val player = event.player as? Player ?: return
        holder.refreshTask?.cancel()
        holder.onCloseAction?.invoke(player)
    }
}