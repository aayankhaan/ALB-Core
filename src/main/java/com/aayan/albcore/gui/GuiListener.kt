package com.aayan.albcore.gui

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.entity.Player
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

        val action = holder.actions[slot] ?: return
        action(player)
    }

    @EventHandler
    fun onGuiOpen(event: InventoryOpenEvent) {
        val holder = event.inventory.holder

        if(holder !is GuiBuilder) return

        val player = event.player as? Player ?: return

        holder.refresh()
        holder.onOpenAction?.invoke(player)
    }

    @EventHandler
    fun onGuiClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder
        if(holder !is GuiBuilder) return
        holder.refreshTask?.cancel()

        val action = holder.onCloseAction ?: return

        action(event.player as Player)
    }
}