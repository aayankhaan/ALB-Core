package com.aayan.albcore.gui

import com.aayan.albcore.utils.ColorUtil
import org.bukkit.Bukkit
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask

class GuiBuilder(title: String, rows: Int) : InventoryHolder {

    internal val actions = mutableMapOf<Int, (Player) -> Unit>()
    private val dynamicItem = mutableMapOf<Int,() -> ItemStack>()
    internal var refreshTask: BukkitTask? = null
    internal var onOpenAction: ((Player) -> Unit)? = null
    internal var onCloseAction: ((Player) -> Unit)? = null

    private val guiInventory: Inventory = Bukkit.createInventory(this, rows * 9, ColorUtil.parse(title))

    override fun getInventory(): Inventory = guiInventory

    fun setItem(slot: Int, item: () -> ItemStack, action: ((Player) -> Unit)? = null) = apply {
        val builtItem = item()
        inventory.setItem(slot, builtItem)
        dynamicItem[slot] = item

        if (action != null) actions[slot] = action

    }

    fun onOpen(action: (Player) -> Unit) = apply {
        onOpenAction = action
    }

    fun onClose(action: (Player) -> Unit) = apply {
        onCloseAction = action
    }

    fun refresh() = apply {
        dynamicItem.forEach { (slot, item ) -> inventory.setItem(slot, item()) }
    }

    fun refreshEvery(ticks: Long, plugin: Plugin) {
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            refresh()
        }, 0L, ticks)
    }

    fun open(player: Player) = apply {
        player.openInventory(inventory)
    }
}