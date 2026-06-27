package com.aayan.albcore.gui

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

class GuiItem(val item: ItemStack, val action: ((Player, PageContext) -> Unit)? = null)