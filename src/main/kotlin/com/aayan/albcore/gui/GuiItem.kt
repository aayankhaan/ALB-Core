package com.aayan.albcore.gui

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** An item on a page, with an optional click action. */
class GuiItem(val item: ItemStack, val action: ((Player, PageContext) -> Unit)? = null)
