package com.aayan.albcore.gui

import org.bukkit.entity.Player

class PageScope {

    internal val itemScopes = mutableListOf<ItemScope>()

    fun item(slot: Int, item: (Player, PageContext) -> GuiItem): ItemScope {
        val scope = ItemScope(listOf(slot), item)
        itemScopes.add(scope)
        return scope
    }

    fun item(vararg slots: Int, item: (Player, PageContext) -> GuiItem): ItemScope {
        val scope = ItemScope(slots.toList(), item)
        itemScopes.add(scope)
        return scope
    }

    fun item(slots: IntRange, item: (Player, PageContext) -> GuiItem): ItemScope {
        val scope = ItemScope(slots.toList(), item)
        itemScopes.add(scope)
        return scope
    }
}