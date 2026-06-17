package com.aayan.albcore.gui

import com.aayan.albcore.utils.ColorUtil
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

class GuiBuilder(title: String, rows: Int) : InventoryHolder {

    internal val actions = mutableMapOf<Int, (Player) -> Unit>()

    private val guiInventory: Inventory = Bukkit.createInventory(
        this,
        rows * 9,
        ColorUtil.parse(title)
    )

    override fun getInventory(): Inventory = guiInventory

    fun setItem(slot: Int, item: ItemStack, action: ((Player) -> Unit)? = null) = apply {
        inventory.setItem(slot, item)
        if (action != null) {
            actions[slot] = action
        }
    }

    fun open(player: Player) = apply {
        player.openInventory(inventory)
    }
}