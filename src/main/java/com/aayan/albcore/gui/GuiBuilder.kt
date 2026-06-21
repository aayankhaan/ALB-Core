package com.aayan.albcore.gui

import com.aayan.albcore.ALBCore
import com.aayan.albcore.utils.ColorUtil
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask

class GuiBuilder(private val title: String, private val rows: Int) : InventoryHolder {

    internal val actions = mutableMapOf<Int, (Player) -> Unit>()
    private val dynamicItem = mutableMapOf<Int, (Player) -> ItemStack>()
    internal var refreshTask: BukkitTask? = null
    internal var onOpenAction: ((Player) -> Unit)? = null
    internal var onCloseAction: ((Player) -> Unit)? = null

    private var guiInventory: Inventory = Bukkit.createInventory(this, rows * 9)

    override fun getInventory(): Inventory = guiInventory
    fun setItems(vararg slots: Int, item: (Player) -> ItemStack, action: ((Player) -> Unit)? = null) = apply {
        slots.forEach { slot -> setItem(slot, item, action) }
    }

    fun setItem(slot: Int, item: (Player) -> ItemStack, action: ((Player) -> Unit)? = null) = apply {
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
        val player = inventory.viewers.firstOrNull() as? Player ?: return@apply
        dynamicItem.forEach { (slot, item) -> inventory.setItem(slot, item(player)) }
    }

    fun refreshEvery(ticks: Long) = apply {
        refreshTask = Bukkit.getScheduler().runTaskTimer(ALBCore.instance, Runnable {
            refresh()
        }, 0L, ticks)
    }

    fun open(player: Player) = apply {
        val parsedTitle = ColorUtil.parseWithPAPI(player, title)
        guiInventory = Bukkit.createInventory(this, rows * 9, parsedTitle)
        refresh()
        player.openInventory(guiInventory)
    }
}