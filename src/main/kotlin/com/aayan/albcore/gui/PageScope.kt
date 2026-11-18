package com.aayan.albcore.gui

import org.bukkit.entity.Player

/** The items of one page (or of the global layer). Items are built for the viewer on every refresh. */
class PageScope {

    internal val itemScopes = mutableListOf<ItemScope>()

    fun item(slot: Int, item: (Player, PageContext) -> GuiItem): ItemScope = add(listOf(slot), item)

    fun item(vararg slots: Int, item: (Player, PageContext) -> GuiItem): ItemScope = add(slots.toList(), item)

    fun item(slots: IntRange, item: (Player, PageContext) -> GuiItem): ItemScope = add(slots.toList(), item)

    private fun add(slots: List<Int>, item: (Player, PageContext) -> GuiItem): ItemScope =
        ItemScope(slots, item).also { itemScopes += it }
}
