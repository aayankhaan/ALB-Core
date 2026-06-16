package com.aayan.albcore.gui

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.entity.Player

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
}