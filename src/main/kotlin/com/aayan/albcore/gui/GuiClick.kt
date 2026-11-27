package com.aayan.albcore.gui

import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/**
 * One click on a GUI slot: who clicked, how, and where.
 *
 * Click actions get it from [GuiBuilder.button], [GuiItem.button], [GuiBuilder.list], or while any
 * click action runs from [GuiBuilder.click] / [PageContext.click].
 *
 * Bedrock touch players can only tap, which arrives as a left click. Every action must work with a
 * left click; right-click, shift-click and Q may only be shortcuts.
 */
class GuiClick(val player: Player, val type: ClickType, val slot: Int, val gui: GuiBuilder) {
    val isLeft: Boolean get() = type.isLeftClick
    val isRight: Boolean get() = type.isRightClick
    val isShift: Boolean get() = type.isShiftClick

    /** Q (the drop key) on the item, with or without Ctrl. */
    val isDrop: Boolean get() = type == ClickType.DROP || type == ClickType.CONTROL_DROP

    /** Page information of the GUI that was clicked. */
    val page: PageContext get() = gui.pageContext()
}
