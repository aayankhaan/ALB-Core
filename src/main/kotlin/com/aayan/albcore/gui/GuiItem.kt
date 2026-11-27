package com.aayan.albcore.gui

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** An item on a page, with an optional click action. */
class GuiItem(val item: ItemStack, val action: ((Player, PageContext) -> Unit)? = null) {

    companion object {
        /**
         * An item whose click action gets the whole [GuiClick] (player, click type, slot, GUI).
         * Plays the theme click sound first unless [sound] is false (e.g. a toggle that plays its own).
         */
        fun button(item: ItemStack, sound: Boolean = true, onClick: (GuiClick) -> Unit): GuiItem =
            GuiItem(item) { player, context ->
                val click = context.click ?: return@GuiItem
                if (sound) GuiSounds.click(player)
                onClick(click)
            }
    }
}
