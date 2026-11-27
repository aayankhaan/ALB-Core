package com.aayan.albcore.menu

import com.aayan.albcore.gui.GuiBuilder
import com.aayan.albcore.gui.GuiClick
import com.aayan.albcore.gui.GuiItem
import com.aayan.albcore.gui.GuiLayout
import com.aayan.albcore.gui.GuiSounds
import com.aayan.albcore.gui.GuiTheme
import com.aayan.albcore.utils.HeadUtil
import com.aayan.albcore.utils.MessageUtil
import com.aayan.albcore.utils.SoundUtil
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** What a function adds to the look of the items that run it, for one viewer. */
class Decoration(val glow: Boolean = false, val extraLore: List<String> = emptyList(), val hidden: Boolean = false)

/**
 * Code behind "[function] <id>". [click] runs on click; [decorate] (optional) changes the look of every
 * item that runs it (glow when a setting is on, an extra lore line, or hide it). With a [permission],
 * players without it get the no-permission message instead.
 */
class MenuFunction(
    val permission: String? = null,
    val decorate: ((Player) -> Decoration)? = null,
    val click: (GuiClick) -> Unit,
)

/** One entry of the list area (a home, a setting, a player...). [click] null = decoration. */
class MenuEntry(val render: (Player) -> ItemStack, val click: ((GuiClick) -> Unit)? = null)

/**
 * Everything the code tells a menu when it opens it.
 *
 * @param back where "[back]" goes (null: it closes the menu).
 * @param page the page of the list area to open at (0-based).
 * @param title replaces the file's title.
 * @param placeholders {placeholders} for the title, item names and lore, and action values, per viewer.
 * @param functions this menu's own "[function] <id>" code; wins over [Menus.function].
 * @param entries the list area's entries; read again on every refresh.
 * @param head what a PLAYER_HEAD item without item-data shows (default: the viewer's head).
 * @param reopen how to open this menu again at a page, used as "[back]" of menus opened from it.
 *   Null: the same context again.
 */
data class MenuContext(
    val back: ((Player) -> Unit)? = null,
    val page: Int = 0,
    val title: String? = null,
    val placeholders: (Player) -> List<Pair<String, Any>> = { emptyList() },
    val functions: Map<String, MenuFunction> = emptyMap(),
    val entries: () -> List<MenuEntry> = { emptyList() },
    val head: ItemStack? = null,
    val reopen: ((Player, Int) -> Unit)? = null,
)

/** Builds a [GuiBuilder] from a [MenuDef]: frame, items (permission, locked look, actions), list area with pages. */
object MenuView {

    fun open(menus: Menus, player: Player, def: MenuDef, ctx: MenuContext): GuiBuilder {
        val gui = build(menus, player, def, ctx)
        gui.open(player, ctx.page)
        return gui
    }

    /** The GUI of [def] for [player], not opened yet (add more with the GuiBuilder API, then open it). */
    fun build(menus: Menus, player: Player, def: MenuDef, ctx: MenuContext): GuiBuilder {
        val title = MenuDef.fill(ctx.title ?: def.title, menus.basePlaceholders(player) + ctx.placeholders(player))
        val gui = GuiBuilder(title, def.rows).pagedTitle().frame(def.fill)

        // Several items may share a slot (e.g. "make co-owner" / "make member"): the first one the viewer
        // can see is shown there.
        val bySlot = def.items.values.filter { it.slot in 0 until def.size }.groupBy { it.slot }
        for ((slot, items) in bySlot) {
            val pick = { viewer: Player -> items.firstOrNull { visible(menus, ctx, it, viewer, gui) } }
            gui.setItem(slot, { viewer ->
                pick(viewer)?.let { render(menus, ctx, it, viewer, gui) } ?: empty(def, slot)
            }) { viewer ->
                val item = pick(viewer) ?: return@setItem
                val click = gui.click ?: return@setItem
                run(menus, click, def, ctx, if (allowed(item, viewer)) item.actions else item.lockedActions)
            }
        }

        val listSlots = def.listSlots.filter { it in 0 until def.size && it !in bySlot.keys }
        if (listSlots.isNotEmpty()) {
            gui.list(listSlots, def.listLayout, ctx.entries) { viewer, entry ->
                val item = entry.render(viewer)
                val click = entry.click ?: return@list GuiItem(item)
                GuiItem.button(item, sound = false, onClick = click)
            }
        }
        return gui
    }

    /**
     * Runs [actions] in order for [click] on menu [def] (the actions of a menu item). Without a "[sound]"
     * action the theme click sound plays first. A function the player may not use stops the remaining actions.
     */
    fun run(menus: Menus, click: GuiClick, def: MenuDef, ctx: MenuContext, actions: List<String>) {
        val parsed = actions.mapNotNull { MenuAction.parse(it) }
        if (parsed.isEmpty()) return
        val player = click.player
        val gui = click.gui
        if (parsed.none { it.type == "sound" }) GuiSounds.click(player)
        val placeholders = menus.basePlaceholders(player) + ctx.placeholders(player)
        // "[back]" of a menu opened from here comes back to this menu, at the same page.
        val here: (Player) -> Unit = { p ->
            val reopen = ctx.reopen
            if (reopen != null) reopen(p, gui.currentPage) else menus.open(p, def, ctx.copy(page = gui.currentPage))
        }
        for (a in parsed) {
            val arg = MenuDef.fill(a.arg, placeholders)
            when (a.type) {
                "close" -> player.closeInventory()
                "back" -> ctx.back?.invoke(player) ?: player.closeInventory()
                "open" -> menus.open(player, a.arg, here)
                "next-page" -> gui.goToPage(gui.currentPage + 1)
                "previous-page" -> gui.goToPage(gui.currentPage - 1)
                "refresh" -> gui.refresh()
                "function" -> if (!menus.runFunction(click, a.arg, ctx, here)) return
                "player" -> player.performCommand(arg.removePrefix("/"))
                "console" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), arg.removePrefix("/"))
                "message" -> MessageUtil.sendWithPAPI(player, arg)
                "sound" -> {
                    val parts = arg.split(' ').filter { it.isNotBlank() }
                    val key = parts.getOrNull(0) ?: continue
                    SoundUtil.play(player, key.lowercase(), parts.getOrNull(1)?.toFloatOrNull() ?: 1f, parts.getOrNull(2)?.toFloatOrNull() ?: 1f)
                }
            }
        }
    }

    private fun empty(def: MenuDef, slot: Int): ItemStack =
        if (GuiLayout.isFilled(def.fill, slot, def.rows)) GuiTheme.filler(slot, def.rows) else ItemStack(Material.AIR)

    private fun allowed(item: MenuItem, viewer: Player) = item.permission.isBlank() || viewer.hasPermission(item.permission)

    /** Page buttons hide when there is no page to go to; functions can hide their item too. */
    private fun hidden(menus: Menus, ctx: MenuContext, actions: List<String>, viewer: Player, gui: GuiBuilder): Boolean {
        val parsed = actions.mapNotNull { MenuAction.parse(it) }
        val page = gui.pageContext()
        if (parsed.any { it.type == "next-page" } && !page.hasNextPage) return true
        if (parsed.any { it.type == "previous-page" } && !page.hasPreviousPage) return true
        return parsed.filter { it.type == "function" }.any { menus.functionFor(ctx, it.arg)?.decorate?.invoke(viewer)?.hidden == true }
    }

    /** Shown to [viewer]: allowed (or has a locked look) and not hidden by a page button or a function. */
    private fun visible(menus: Menus, ctx: MenuContext, item: MenuItem, viewer: Player, gui: GuiBuilder): Boolean {
        val allowed = allowed(item, viewer)
        if (!allowed && item.hideLocked) return false
        return !hidden(menus, ctx, if (allowed) item.actions else item.lockedActions, viewer, gui)
    }

    private fun render(menus: Menus, ctx: MenuContext, item: MenuItem, viewer: Player, gui: GuiBuilder): ItemStack {
        val allowed = allowed(item, viewer)
        val actions = if (allowed) item.actions else item.lockedActions
        val look = if (allowed) item.look else item.locked
        val decorations = actions.mapNotNull { MenuAction.parse(it) }.filter { it.type == "function" }
            .mapNotNull { menus.functionFor(ctx, it.arg)?.decorate?.invoke(viewer) }
        val placeholders = menus.basePlaceholders(viewer) + ctx.placeholders(viewer) +
            listOf("page" to gui.currentPage + 1, "pages" to maxOf(1, gui.totalPages))
        val base = if (look.custom == null && look.material == Material.PLAYER_HEAD) ctx.head?.clone() ?: HeadUtil.fromPlayer(viewer) else look.base()
        return MenuDef.build(
            base,
            MenuDef.fill(look.name, placeholders),
            MenuDef.fillLines(look.lore, placeholders) + decorations.flatMap { it.extraLore },
            look.glow || decorations.any { it.glow },
            viewer,
        )
    }
}
