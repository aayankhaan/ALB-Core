package com.aayan.albcore.gui

import com.aayan.albcore.ALBCore
import com.aayan.albcore.menu.MenuDef
import com.aayan.albcore.utils.ItemBuilder
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/**
 * The shared look of every ALB menu.
 *
 * Items come from `plugins/ALBCore/menus/common.yml`: `frame`, `corner`, `corner-bottom` (panes) and
 * the buttons `back`, `close`, `previous`, `next`. Shared text pieces (status lines, click hints, the
 * paged title) come from `plugins/ALBCore/lang.yml`, section `gui`. Change those two files to restyle
 * the menus of every ALB plugin at once.
 */
object GuiTheme {

    /** Id of ALBCore's shared menu file (menus/common.yml). */
    const val COMMON = "common"

    private fun common(): MenuDef? = runCatching { ALBCore.instance.menus.find(COMMON) }.getOrNull()

    /** The plain frame pane (no name, no tooltip). */
    fun frame(): ItemStack = pane(look("frame"))

    /** A corner pane: `corner` on top, `corner-bottom` at the bottom (falls back to `corner`). */
    fun corner(bottom: Boolean = false): ItemStack {
        val items = common()?.items
        val look = (if (bottom) items?.get("corner-bottom") else null) ?: items?.get("corner")
        return pane(look?.look?.base() ?: ItemStack(Material.CYAN_STAINED_GLASS_PANE))
    }

    /** The frame pane for [slot] of a [rows]-row menu: corner panes in the corners, the frame elsewhere. */
    fun filler(slot: Int, rows: Int): ItemStack = when {
        slot == 0 || slot == 8 -> corner()
        GuiLayout.isBottomCorner(slot, rows) -> corner(bottom = true)
        else -> frame()
    }

    /**
     * A shared button of common.yml (`back`, `close`, `previous`, `next`, or any other item id there)
     * with {placeholders} filled. A missing id gives a barrier, so the mistake is visible.
     */
    fun button(id: String, vararg placeholders: Pair<String, Any>): ItemStack =
        common()?.item(id, placeholders.toList()) ?: ItemStack(Material.BARRIER)

    /** A frame item: name " " and no tooltip at all. */
    fun pane(base: ItemStack): ItemStack =
        if (base.type.isAir) base else ItemBuilder(base).name(" ").meta { isHideTooltip = true }.build()

    /** [title] with the page number (lang `gui.paged-title`) when there is more than one page. [page] is 0-based. */
    fun pagedTitle(title: String, page: Int, pages: Int): String {
        if (pages <= 1) return title
        val lang = runCatching { ALBCore.instance.lang }.getOrNull() ?: return "$title &8(${page + 1}/$pages)"
        // Filled by hand: Lang escapes placeholder values, which would break a MiniMessage title.
        return lang.raw("gui.paged-title").replace("{title}", title).replace("{page}", "${page + 1}").replace("{pages}", "$pages")
    }

    /** A shared text piece from ALBCore lang.yml `gui.<key>` (e.g. `status-on`, `choice-current`). */
    fun text(key: String, vararg placeholders: Pair<String, Any>): String =
        runCatching { ALBCore.instance.lang.raw("gui.$key", *placeholders) }.getOrDefault("")

    /** A shared multi-line text piece from ALBCore lang.yml `gui.<key>` (e.g. `toggle-hint`, `open-hint`). */
    fun lines(key: String, vararg placeholders: Pair<String, Any>): List<String> =
        runCatching { ALBCore.instance.lang.lines("gui.$key", *placeholders) }.getOrDefault(emptyList())

    private fun look(id: String): ItemStack =
        common()?.items?.get(id)?.look?.base() ?: ItemStack(Material.CYAN_STAINED_GLASS_PANE)
}
