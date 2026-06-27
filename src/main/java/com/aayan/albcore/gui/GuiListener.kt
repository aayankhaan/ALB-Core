package com.aayan.albcore.gui

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent

class GuiListener : Listener {

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder
        if (holder !is GuiBuilder) return

        event.isCancelled = true

        val player = event.whoClicked as? Player ?: return
        val slot = event.slot

        holder.actions[slot]?.invoke(player)

        if (holder.pages.isNotEmpty() || holder.globalScope != null) {
            val ctx = PageContext(holder.currentPage, holder.pages.size, holder)
            holder.pageActions[slot]?.invoke(player, ctx)
        }
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