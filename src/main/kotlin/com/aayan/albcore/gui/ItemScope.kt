package com.aayan.albcore.gui

import org.bukkit.entity.Player

/** One or more slots that share the same item factory. */
class ItemScope(val slots: List<Int>, val item: (Player, PageContext) -> GuiItem)
