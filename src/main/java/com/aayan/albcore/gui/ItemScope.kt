package com.aayan.albcore.gui

import org.bukkit.entity.Player

class ItemScope(val slots: List<Int>, val item: (Player, PageContext) -> GuiItem)