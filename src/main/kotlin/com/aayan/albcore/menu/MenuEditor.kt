package com.aayan.albcore.menu

import com.aayan.albcore.ALBCore
import com.aayan.albcore.gui.GuiBuilder
import com.aayan.albcore.gui.GuiFill
import com.aayan.albcore.gui.GuiLayout
import com.aayan.albcore.gui.GuiSounds
import com.aayan.albcore.gui.GuiTheme
import com.aayan.albcore.input.ChatInput
import com.aayan.albcore.lang.Lang
import com.aayan.albcore.utils.ItemBuilder
import com.aayan.albcore.utils.MessageUtil
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** A function the action picker offers ("[function] <id>"). */
data class EditorFunction(val id: String, val icon: Material, val label: String)

/** Lets admins make their own menus in the editor, each opened by a command ("/is <name>" in ALBSkyblock). */
class CustomMenuSupport(
    val create: (id: String) -> MenuDef,
    val delete: (id: String) -> Unit,
    /** Shown before the command, e.g. "/is ". */
    val commandPrefix: String,
    /** Why [command] can't be used ("bad-name" / "taken"), or null. [except] = the menu being changed. */
    val commandProblem: (command: String, except: String?) -> String?,
)

/**
 * What a plugin tells the shared [MenuEditor].
 * @param all the menus to list (ALBCore's shared look, menus/common.yml, is added at the end).
 * @param tryOpen "Try it": open [MenuDef] as players see it; its back button returns to the editor.
 * @param functions the "[function]" ids the action picker offers for a menu id.
 * @param exit where the menus list's back button goes (null = close).
 * @param notOpenable menu ids "[open]" may not point to (e.g. confirm screens).
 */
class MenuEditorSpec(
    val plugin: org.bukkit.plugin.Plugin,
    val all: () -> Collection<MenuDef>,
    val find: (String) -> MenuDef?,
    val tryOpen: (Player, MenuDef, (Player) -> Unit) -> Unit,
    val functions: (menuId: String) -> List<EditorFunction> = { emptyList() },
    val exit: ((Player) -> Unit)? = null,
    val custom: CustomMenuSupport? = null,
    val notOpenable: Set<String> = emptySet(),
)

/**
 * The in-game menu editor every ALB plugin shares: edit any YAML menu, and (with [CustomMenuSupport])
 * make new ones. Texts are ALBCore's lang.yml `menu-editor`.
 *
 * The editor is a 6-row chest: the top and bottom rows are controls, the 4 rows in between show the
 * menu being edited (scroll for 5-6 row menus).
 *   - click an item: edit it (item, name, lore, glow, actions, permission + locked look, copy, delete)
 *   - click an empty slot: add an item there
 *   - Rearrange on: click an item, then the slot it goes to (an item there swaps places)
 *   - List slots on: click slots to add / remove them from the list area (more entries than slots = pages)
 * Changes save to the menu's file right away.
 *
 * ```
 * val editor = MenuEditor.forMenus(this, menus, functions = { id -> myFunctions(id) }, exit = { openAdminHub(it) })
 * editor.list(player)
 * ```
 */
class MenuEditor(private val spec: MenuEditorSpec) {

    private class Session(val menuId: String) {
        var scroll = 0
        var rearrange = false
        var listMode = false
        var picked: Int? = null
    }

    private val sessions = HashMap<UUID, Session>()
    private val lang: Lang get() = ALBCore.instance.lang

    // ------------------------------------------------------------------ menus list

    fun list(player: Player) {
        val gui = GuiBuilder(lang.raw("menu-editor.title"), 6).frame()
            .header(item(Material.ITEM_FRAME, lang.raw("menu-editor.list-header"), lang.lines("menu-editor.header-lore")))
        val slots = GuiLayout.inner(6)
        allMenus().take(slots.size).forEachIndexed { i, def ->
            gui.button(slots[i], { _ ->
                val icon = def.items.values.firstOrNull { it.slot >= 0 }?.look?.base()?.takeIf { !it.type.isAir } ?: ItemStack(Material.CHEST)
                val kind = when {
                    isTheme(def) -> lang.raw("menu-editor.kind-theme")
                    def.builtIn -> lang.raw("menu-editor.kind-built-in")
                    else -> lang.raw("menu-editor.kind-custom", "command" to def.command.ifBlank { "-" }.let { c ->
                        if (c == "-") c else (spec.custom?.commandPrefix ?: "") + c
                    })
                }
                item(icon, lang.raw("menu-editor.menu-name", "id" to def.id),
                    def.about.map { "&7$it" } + lang.lines("menu-editor.menu-lore", "rows" to def.rows, "items" to def.items.size,
                        "file" to def.file.name, "kind" to kind))
            }) { click -> if (isTheme(def)) commonItems(click.player) else edit(click.player, def.id, fresh = true) }
        }
        spec.custom?.let {
            gui.button(52, item(Material.LIME_DYE, lang.raw("menu-editor.new-name"), lang.lines("menu-editor.new-lore"))) { click -> newMenu(click.player) }
        }
        gui.backOrClose(spec.exit)
        gui.open(player)
    }

    private fun allMenus(): List<MenuDef> {
        val list = spec.all().toMutableList()
        val theme = runCatching { ALBCore.instance.menus.find(GuiTheme.COMMON) }.getOrNull()
        if (theme != null && list.none { it === theme || isTheme(it) }) list += theme
        return list
    }

    private fun isTheme(def: MenuDef): Boolean =
        runCatching { ALBCore.instance.menus.find(GuiTheme.COMMON) }.getOrNull()?.let { it === def || it.file == def.file } ?: false

    private fun findMenu(id: String): MenuDef? =
        spec.find(id) ?: if (id == GuiTheme.COMMON) runCatching { ALBCore.instance.menus.find(id) }.getOrNull() else null

    private fun newMenu(player: Player) {
        val custom = spec.custom ?: return
        ask(player, "menu-editor.type-menu-id", { list(it) }) { text ->
            val id = text.trim().lowercase()
            when {
                !NAME.matches(id) -> false.also { lang.send(player, "menu-editor.bad-name") }
                findMenu(id) != null -> false.also { lang.send(player, "menu-editor.menu-exists", "id" to id) }
                else -> {
                    val def = custom.create(id)
                    if (custom.commandProblem(id, id) == null) {
                        def.command = id
                        def.save()
                        lang.send(player, "menu-editor.menu-created", "id" to id, "command" to custom.commandPrefix + id)
                    } else {
                        lang.send(player, "menu-editor.menu-created-no-command", "id" to id)
                    }
                    edit(player, id, fresh = true)
                    true
                }
            }
        }
    }

    // ------------------------------------------------------------------ the editor

    private fun session(player: Player, menuId: String, fresh: Boolean): Session {
        val current = sessions[player.uniqueId]
        if (!fresh && current != null && current.menuId == menuId) return current
        return Session(menuId).also { sessions[player.uniqueId] = it }
    }

    fun edit(player: Player, menuId: String, fresh: Boolean = false) {
        val def = findMenu(menuId) ?: return list(player)
        val s = session(player, menuId, fresh)
        s.scroll = s.scroll.coerceIn(0, maxOf(0, def.rows - PREVIEW_ROWS))
        val mode = when {
            s.rearrange -> "rearrange"
            s.listMode -> "list"
            else -> "edit"
        }
        val last = minOf(def.rows, s.scroll + PREVIEW_ROWS)
        val gui = GuiBuilder(lang.raw("menu-editor.edit-title-$mode", "id" to menuId, "from" to s.scroll + 1, "to" to last, "rows" to def.rows), 6)
        val pane = GuiTheme.pane(ItemStack(Material.GRAY_STAINED_GLASS_PANE))
        gui.setItems(*((0..8) + (45..53)).toList().toIntArray(), item = { pane })
        val reopen: (Player) -> Unit = { edit(it, menuId) }

        // --- top controls
        gui.button(0, { GuiTheme.button("back") }) { click ->
            sessions.remove(click.player.uniqueId)
            list(click.player)
        }
        gui.button(2, item(Material.COMPARATOR, lang.raw("menu-editor.options-name"), lang.lines("menu-editor.options-lore"))) { options(it.player, menuId) }
        gui.setItem(4, { item(Material.BOOK, lang.raw("menu-editor.info-name", "id" to menuId), lang.lines("menu-editor.info-$mode")) })
        gui.button(6, { item(Material.PISTON, lang.raw("menu-editor.rearrange-name"),
            listOf(GuiTheme.text(if (s.rearrange) "status-on" else "status-off")) + lang.lines("menu-editor.rearrange-lore"), s.rearrange) }, sound = false) { click ->
            s.rearrange = !s.rearrange
            s.listMode = false
            s.picked = null
            GuiSounds.toggled(click.player, s.rearrange)
            reopen(click.player)
        }
        if (def.templates.isNotEmpty() || def.listSlots.isNotEmpty()) {
            gui.button(7, { item(Material.CHEST_MINECART, lang.raw("menu-editor.list-mode-name"),
                listOf(GuiTheme.text(if (s.listMode) "status-on" else "status-off")) + lang.lines("menu-editor.list-mode-lore", "count" to def.listSlots.size), s.listMode) },
                sound = false) { click ->
                s.listMode = !s.listMode
                s.rearrange = false
                s.picked = null
                GuiSounds.toggled(click.player, s.listMode)
                reopen(click.player)
            }
        }
        if (s.scroll > 0) gui.button(8, item(Material.SPECTRAL_ARROW, lang.raw("menu-editor.up-name"), emptyList())) { click ->
            s.scroll--
            reopen(click.player)
        }
        // --- bottom controls
        gui.button(45, item(Material.ENDER_EYE, lang.raw("menu-editor.try-name"), lang.lines("menu-editor.try-lore"))) { click ->
            spec.tryOpen(click.player, def, reopen)
        }
        gui.button(49, { GuiTheme.button("close") }) { click ->
            sessions.remove(click.player.uniqueId)
            click.player.closeInventory()
        }
        if (s.scroll + PREVIEW_ROWS < def.rows) gui.button(53, item(Material.SPECTRAL_ARROW, lang.raw("menu-editor.down-name"), emptyList())) { click ->
            s.scroll++
            reopen(click.player)
        }

        // --- the menu itself
        for (r in 0 until PREVIEW_ROWS) for (c in 0 until 9) {
            val slot = 9 + r * 9 + c
            val row = s.scroll + r
            if (row >= def.rows) {
                gui.setItem(slot, { item(Material.BLACK_STAINED_GLASS_PANE, lang.raw("menu-editor.outside"), emptyList()) })
                continue
            }
            val target = row * 9 + c
            gui.setItem(slot, { preview(def, s, target) }) { clicker -> clickSlot(clicker, def, s, target) }
        }
        gui.open(player)
        MessageUtil.actionBar(player, lang.raw("menu-editor.hint-$mode"))
    }

    private fun preview(def: MenuDef, s: Session, target: Int): ItemStack {
        val item = def.itemAt(target)
        val inList = target in def.listSlots
        return when {
            item != null -> {
                val summary = item.actions.ifEmpty { listOf(lang.raw("menu-editor.no-actions")) }.map { "&8» &7$it" }
                MenuDef.build(item.look.base().takeIf { !it.type.isAir } ?: ItemStack(Material.STRUCTURE_VOID), item.look.name, item.look.lore +
                    lang.lines("menu-editor.item-info", "slot" to target, "id" to item.id,
                        "permission" to item.permission.ifBlank { lang.raw("menu-editor.everyone") }) + summary +
                    lang.lines(if (s.rearrange) "menu-editor.item-hint-rearrange" else "menu-editor.item-hint"),
                    item.look.glow || s.picked == target)
            }
            s.listMode && inList -> item(Material.LIME_STAINED_GLASS_PANE, lang.raw("menu-editor.slot-in-list", "slot" to target), lang.lines("menu-editor.slot-toggle"))
            s.listMode -> item(Material.GRAY_STAINED_GLASS_PANE, lang.raw("menu-editor.slot-not-in-list", "slot" to target), lang.lines("menu-editor.slot-toggle"))
            inList -> {
                val look = def.templates.keys.firstOrNull { !it.startsWith("state-") && it != "chosen" && it != "select" } ?: "entry"
                def.entry(look, def.icons.keys.firstOrNull(), ItemStack(Material.BOOK), emptyList(),
                    extraLore = lang.lines("menu-editor.list-slot-lore", "slot" to target))
            }
            GuiLayout.isFilled(def.fill, target, def.rows) -> MenuDef.build(GuiTheme.filler(target, def.rows),
                lang.raw("menu-editor.empty-name", "slot" to target), lang.lines("menu-editor.empty-filler-lore"), false)
            else -> ItemStack(Material.AIR)
        }
    }

    private fun clickSlot(player: Player, def: MenuDef, s: Session, target: Int) {
        val item = def.itemAt(target)
        when {
            s.listMode -> {
                if (item != null) {
                    GuiSounds.denied(player)
                    lang.send(player, "menu-editor.list-slot-taken")
                    return
                }
                def.listSlots = if (target in def.listSlots) def.listSlots - target else (def.listSlots + target).sorted()
                def.save()
                GuiSounds.toggled(player, target in def.listSlots)
            }
            s.rearrange -> {
                val picked = s.picked
                when {
                    picked == null && item == null -> {
                        GuiSounds.denied(player)
                        return
                    }
                    picked == null -> s.picked = target
                    picked == target -> s.picked = null
                    else -> {
                        def.move(picked, target)
                        s.picked = null
                        GuiSounds.success(player)
                    }
                }
                GuiSounds.click(player)
            }
            item != null -> return editItem(player, def.id, item.id)
            target in def.listSlots -> return listEditor(player, def.id)
            else -> {
                val created = MenuItem(def.newItemId()).apply {
                    slot = target
                    look.material = Material.PAPER
                    look.name = "&fNew item"
                }
                def.items[created.id] = created
                def.save()
                GuiSounds.success(player)
                return editItem(player, def.id, created.id)
            }
        }
        edit(player, def.id)
    }

    // ------------------------------------------------------------------ one item

    private fun editItem(player: Player, menuId: String, itemId: String) {
        val def = findMenu(menuId) ?: return list(player)
        val item = def.items[itemId] ?: return edit(player, menuId)
        val common = isTheme(def)
        val back: (Player) -> Unit = { if (common) commonItems(it) else edit(it, menuId) }
        val again: (Player) -> Unit = { editItem(it, menuId, itemId) }
        val rows = 5
        val gui = GuiBuilder(lang.raw("menu-editor.item-title", "id" to menuId, "item" to itemId), rows).frame()
        gui.setItem(4, { MenuDef.build(item.look.base().takeIf { !it.type.isAir } ?: ItemStack(Material.STRUCTURE_VOID), item.look.name, item.look.lore, item.look.glow) })
        lookButtons(gui, def, item.look, listOf(19, 20, 21, 22), again)
        if (!common) {
            gui.button(23, { item(Material.COMMAND_BLOCK, lang.raw("menu-editor.actions-name"),
                item.actions.ifEmpty { listOf(lang.raw("menu-editor.no-actions")) }.map { "&8» &7$it" } + GuiTheme.lines("open-hint")) }) {
                actions(it.player, menuId, itemId, locked = false)
            }
            gui.button(24, { item(Material.TRIPWIRE_HOOK, lang.raw("menu-editor.permission-name"),
                lang.lines("menu-editor.permission-lore", "permission" to item.permission.ifBlank { lang.raw("menu-editor.everyone") },
                    "when" to lang.raw(if (item.hideLocked) "menu-editor.locked-hide" else "menu-editor.locked-show")) + GuiTheme.lines("open-hint")) }) {
                permission(it.player, menuId, itemId)
            }
            gui.button(25, item(Material.SLIME_BALL, lang.raw("menu-editor.copy-name"), lang.lines("menu-editor.copy-lore"))) { click ->
                val p = click.player
                val free = (0 until def.size).firstOrNull { slot -> def.itemAt(slot) == null && slot !in def.listSlots }
                if (free == null) {
                    GuiSounds.denied(p)
                    lang.send(p, "menu-editor.no-free-slot")
                    return@button
                }
                val copy = MenuItem(def.newItemId()).apply {
                    copyFrom(item)
                    slot = free
                }
                def.items[copy.id] = copy
                def.save()
                lang.send(p, "menu-editor.copied", "slot" to free)
                editItem(p, menuId, copy.id)
            }
            var armed = false
            gui.button(31, { item(if (armed) Material.TNT else Material.BARRIER, lang.raw(if (armed) "menu-editor.confirm" else "menu-editor.delete-item-name"),
                lang.lines("menu-editor.delete-item-lore")) }) { click ->
                if (!armed) {
                    armed = true
                    click.gui.refresh()
                    return@button
                }
                def.items.remove(itemId)
                def.save()
                GuiSounds.denied(click.player)
                back(click.player)
            }
        }
        gui.backOrClose(back)
        gui.open(player)
    }

    /** Item / name / lore / glow buttons for [look] in [slots]. */
    private fun lookButtons(gui: GuiBuilder, def: MenuDef, look: MenuLook, slots: List<Int>, again: (Player) -> Unit) {
        gui.button(slots[0], item(Material.ITEM_FRAME, lang.raw("menu-editor.item-name"), lang.lines("menu-editor.item-lore"))) { click ->
            val hand = click.player.inventory.itemInMainHand
            when {
                hand.isEmpty -> look.custom = null
                MenuDef.isPlain(hand.asOne()) -> {
                    look.material = hand.type
                    look.custom = null
                }
                else -> {
                    look.custom = hand.asOne()
                    look.material = hand.type
                }
            }
            def.save()
            again(click.player)
        }
        gui.button(slots[1], { item(Material.NAME_TAG, lang.raw("menu-editor.name-name"), lang.lines("menu-editor.name-lore", "value" to look.name)) }) { click ->
            askText(click.player, look.name, again) { text ->
                look.name = text
                def.save()
            }
        }
        gui.button(slots[2], item(Material.WRITABLE_BOOK, lang.raw("menu-editor.lore-name"), lang.lines("menu-editor.lore-lore"))) { click ->
            askLines(click.player, look.lore, again) { lines ->
                look.lore = lines
                def.save()
            }
        }
        gui.button(slots[3], { item(Material.GLOWSTONE_DUST, lang.raw("menu-editor.glow-name"),
            listOf(GuiTheme.text(if (look.glow) "status-on" else "status-off")) + GuiTheme.lines("toggle-hint"), look.glow) }, sound = false) { click ->
            look.glow = !look.glow
            def.save()
            GuiSounds.toggled(click.player, look.glow)
            again(click.player)
        }
    }

    // ------------------------------------------------------------------ actions

    private fun actions(player: Player, menuId: String, itemId: String, locked: Boolean) {
        val def = findMenu(menuId) ?: return list(player)
        val item = def.items[itemId] ?: return edit(player, menuId)
        val list = if (locked) item.lockedActions else item.actions
        val gui = GuiBuilder(lang.raw(if (locked) "menu-editor.locked-actions-title" else "menu-editor.actions-title", "item" to itemId), 6).frame()
            .header(item(Material.COMMAND_BLOCK, lang.raw("menu-editor.actions-name"), lang.lines("menu-editor.actions-help")))
        val slots = GuiLayout.inner(6)
        list.take(slots.size).forEachIndexed { i, raw ->
            val parsed = MenuAction.parse(raw)
            gui.button(slots[i], item(iconOf(parsed?.type ?: ""), lang.raw("menu-editor.action-entry", "n" to i + 1, "type" to (parsed?.type ?: "?")),
                listOfNotNull(parsed?.arg?.takeIf { it.isNotBlank() }?.let { "&f$it" }) + lang.lines("menu-editor.action-entry-lore")), sound = false) { click ->
                // Left click (also Bedrock tap): move up. Right click or Q: remove.
                if (click.isRight || click.isDrop) {
                    list.removeAt(i)
                    GuiSounds.denied(click.player)
                } else if (i > 0) {
                    list.add(i - 1, list.removeAt(i))
                    GuiSounds.click(click.player)
                }
                def.save()
                actions(click.player, menuId, itemId, locked)
            }
        }
        gui.button(51, item(Material.BARRIER, lang.raw("menu-editor.remove-last-name"), lang.lines("menu-editor.remove-last-lore"))) { click ->
            if (list.isNotEmpty()) {
                list.removeAt(list.size - 1)
                def.save()
            }
            actions(click.player, menuId, itemId, locked)
        }
        gui.button(52, item(Material.LIME_DYE, lang.raw("menu-editor.add-action-name"), lang.lines("menu-editor.add-action-lore"))) { click ->
            pickAction(click.player, menuId) { p, action ->
                if (action != null) {
                    list += action
                    def.save()
                }
                actions(p, menuId, itemId, locked)
            }
        }
        gui.backOrClose({ if (locked) permission(it, menuId, itemId) else editItem(it, menuId, itemId) })
        gui.open(player)
    }

    /** Every kind of action, then the functions this menu can use. [done] gets the new action (null = cancelled). */
    private fun pickAction(player: Player, menuId: String, done: (Player, String?) -> Unit) {
        val gui = GuiBuilder(lang.raw("menu-editor.pick-action-title"), 6).frame()
        val simple = listOf(10 to "close", 11 to "back", 12 to "open", 13 to "previous-page", 14 to "next-page", 15 to "refresh",
            19 to "player", 20 to "console", 21 to "message", 22 to "sound")
        for ((slot, type) in simple) {
            gui.button(slot, item(iconOf(type), lang.raw("menu-editor.type.$type-name"), lang.lines("menu-editor.type.$type-lore"))) { click ->
                val p = click.player
                when (type) {
                    "close", "back", "previous-page", "next-page", "refresh" -> done(p, "[$type]")
                    "open" -> pickMenu(p, menuId) { pp, target -> done(pp, target?.let { t -> "[open] $t" }) }
                    else -> ask(p, "menu-editor.type.$type-ask", { pp -> done(pp, null) }) { text ->
                        done(p, "[$type] $text")
                        true
                    }
                }
            }
        }
        val fSlots = (28..34) + (37..43)
        val functions = spec.functions(menuId)
        val shown = if (functions.size > fSlots.size) functions.take(fSlots.size - 1) else functions
        shown.forEachIndexed { i, f ->
            gui.button(fSlots[i], item(f.icon, f.label, lang.lines("menu-editor.function-lore", "id" to f.id))) { done(it.player, "[function] ${f.id}") }
        }
        if (shown.size < functions.size) {
            gui.button(fSlots.last(), item(Material.SPECTRAL_ARROW, lang.raw("menu-editor.more-functions-name"),
                lang.lines("menu-editor.more-functions-lore", "count" to functions.size))) { click ->
                pickFunction(click.player, functions, done) { p -> pickAction(p, menuId, done) }
            }
        }
        gui.backOrClose({ done(it, null) })
        gui.open(player)
    }

    /** Every function of a menu on pages, for menus with more than fit next to the other actions. */
    private fun pickFunction(player: Player, functions: List<EditorFunction>, done: (Player, String?) -> Unit, back: (Player) -> Unit) {
        GuiBuilder(lang.raw("menu-editor.pick-function-title"), 6).frame().pagedTitle().pageButtons()
            .list(GuiLayout.inner(6), functions) { _, f ->
                com.aayan.albcore.gui.GuiItem.button(item(f.icon, f.label, lang.lines("menu-editor.function-lore", "id" to f.id))) { click ->
                    done(click.player, "[function] ${f.id}")
                }
            }
            .backOrClose(back)
            .open(player, 0)
    }

    private fun pickMenu(player: Player, from: String, done: (Player, String?) -> Unit) {
        val gui = GuiBuilder(lang.raw("menu-editor.pick-menu-title"), 6).frame()
        val slots = GuiLayout.inner(6)
        spec.all().filter { !isTheme(it) && it.id != from && it.id !in spec.notOpenable }.take(slots.size).forEachIndexed { i, def ->
            gui.button(slots[i], item(Material.CHEST, lang.raw("menu-editor.menu-name", "id" to def.id), def.about.map { "&7$it" })) { done(it.player, def.id) }
        }
        gui.backOrClose({ done(it, null) })
        gui.open(player)
    }

    // ------------------------------------------------------------------ permission + locked look

    private fun permission(player: Player, menuId: String, itemId: String) {
        val def = findMenu(menuId) ?: return list(player)
        val item = def.items[itemId] ?: return edit(player, menuId)
        val again: (Player) -> Unit = { permission(it, menuId, itemId) }
        val gui = GuiBuilder(lang.raw("menu-editor.permission-title", "item" to itemId), 4).frame()
        gui.setItem(4, { MenuDef.build(item.locked.base().takeIf { !it.type.isAir } ?: ItemStack(Material.STRUCTURE_VOID),
            item.locked.name, item.locked.lore + lang.lines("menu-editor.locked-preview"), item.locked.glow) })
        gui.button(10, { item(Material.TRIPWIRE_HOOK, lang.raw("menu-editor.permission-name"),
            lang.lines("menu-editor.permission-set-lore", "permission" to item.permission.ifBlank { lang.raw("menu-editor.everyone") })) }) { click ->
            ask(click.player, "menu-editor.type-permission", again) { text ->
                if (text.equals("none", true)) {
                    item.permission = ""
                } else {
                    item.permission = text.trim()
                    if (item.locked.name.isBlank()) item.locked.copyFrom(item.look)
                }
                def.save()
                again(click.player)
                true
            }
        }
        gui.button(11, { item(if (item.hideLocked) Material.ENDER_PEARL else Material.ENDER_EYE, lang.raw("menu-editor.when-locked-name"),
            listOf(lang.raw(if (item.hideLocked) "menu-editor.locked-hide" else "menu-editor.locked-show")) + GuiTheme.lines("cycle-hint")) }, sound = false) { click ->
            item.hideLocked = !item.hideLocked
            def.save()
            GuiSounds.toggled(click.player, !item.hideLocked)
            again(click.player)
        }
        lookButtons(gui, def, item.locked, listOf(12, 13, 14, 15), again)
        gui.button(16, { item(Material.COMMAND_BLOCK, lang.raw("menu-editor.locked-actions-name"),
            item.lockedActions.ifEmpty { listOf(lang.raw("menu-editor.no-actions")) }.map { "&8» &7$it" } + GuiTheme.lines("open-hint")) }) {
            actions(it.player, menuId, itemId, locked = true)
        }
        gui.button(22, item(Material.PAPER, lang.raw("menu-editor.copy-look-name"), lang.lines("menu-editor.copy-look-lore"))) { click ->
            item.locked.copyFrom(item.look)
            def.save()
            again(click.player)
        }
        gui.backOrClose({ editItem(it, menuId, itemId) }, 31)
        gui.open(player)
    }

    // ------------------------------------------------------------------ menu options

    private fun options(player: Player, menuId: String) {
        val def = findMenu(menuId) ?: return list(player)
        val again: (Player) -> Unit = { options(it, menuId) }
        val gui = GuiBuilder(lang.raw("menu-editor.options-title", "id" to menuId), 4).frame()
        gui.setItem(4, { item(Material.CHEST, lang.raw("menu-editor.menu-name", "id" to menuId), def.about.map { "&7$it" }) })
        gui.button(10, { item(Material.NAME_TAG, lang.raw("menu-editor.title-name"), lang.lines("menu-editor.name-lore", "value" to def.title)) }) { click ->
            askText(click.player, def.title, again) { text ->
                def.title = text
                def.save()
            }
        }
        gui.button(11, { item(Material.LADDER, lang.raw("menu-editor.rows-name", "rows" to def.rows), lang.lines("menu-editor.rows-lore")) }, sound = false) { click ->
            // Left click (also Bedrock tap): one more row, back to 1 after 6. Right click: one fewer.
            val next = if (click.isRight) def.rows - 1 else if (def.rows >= 6) 1 else def.rows + 1
            if (next !in 1..6) {
                GuiSounds.denied(click.player)
                return@button
            }
            def.rows = next
            def.save()
            GuiSounds.click(click.player)
            click.gui.refresh()
        }
        gui.button(12, { item(Material.BLACK_STAINED_GLASS_PANE, lang.raw("menu-editor.fill-name"),
            listOf(lang.raw("menu-editor.fill-${def.fill.name.lowercase()}")) + lang.lines("menu-editor.fill-lore")) }) { click ->
            def.fill = GuiFill.entries[(def.fill.ordinal + 1) % GuiFill.entries.size]
            def.save()
            click.gui.refresh()
        }
        val custom = spec.custom
        if (custom != null && !def.builtIn) gui.button(13, { item(Material.OAK_SIGN, lang.raw("menu-editor.command-name"),
            lang.lines("menu-editor.command-lore", "command" to if (def.command.isBlank()) "-" else custom.commandPrefix + def.command)) }) { click ->
            val p = click.player
            ask(p, "menu-editor.type-command", again, "example" to custom.commandPrefix + "test") { text ->
                val command = text.trim().lowercase().removePrefix(custom.commandPrefix.trim().lowercase()).trim().removePrefix("/")
                if (command == "none") {
                    def.command = ""
                    def.save()
                    again(p)
                    return@ask true
                }
                val problem = custom.commandProblem(command, menuId)
                if (problem != null) {
                    lang.send(p, "menu-editor.command-$problem", "command" to custom.commandPrefix + command)
                    false
                } else {
                    def.command = command
                    def.save()
                    lang.send(p, "menu-editor.command-set", "command" to custom.commandPrefix + command)
                    again(p)
                    true
                }
            }
        }
        if (def.templates.isNotEmpty() || def.icons.isNotEmpty()) gui.button(14, item(Material.CHEST_MINECART, lang.raw("menu-editor.list-name"), lang.lines("menu-editor.list-lore"))) {
            listEditor(it.player, menuId)
        }
        if (def.builtIn || custom != null) {
            var armed = false
            gui.button(16, {
                val key = if (def.builtIn) "reset-menu" else "delete-menu"
                item(if (armed) Material.TNT else Material.BARRIER, lang.raw(if (armed) "menu-editor.confirm" else "menu-editor.$key-name"), lang.lines("menu-editor.$key-lore"))
            }) { click ->
                if (!armed) {
                    armed = true
                    click.gui.refresh()
                    return@button
                }
                GuiSounds.denied(click.player)
                if (def.builtIn) {
                    def.reset()
                    edit(click.player, menuId, fresh = true)
                } else {
                    custom?.delete(menuId)
                    list(click.player)
                }
            }
        }
        gui.backOrClose({ edit(it, menuId) }, 31)
        gui.open(player)
    }

    // ------------------------------------------------------------------ list area: looks and icons

    private fun listEditor(player: Player, menuId: String) {
        val def = findMenu(menuId) ?: return list(player)
        val gui = GuiBuilder(lang.raw("menu-editor.list-title", "id" to menuId), 3).frame()
        gui.button(11, item(Material.LIME_STAINED_GLASS_PANE, lang.raw("menu-editor.slots-name"), lang.lines("menu-editor.slots-lore", "count" to def.listSlots.size))) { click ->
            val s = session(click.player, menuId, fresh = false)
            s.listMode = true
            s.rearrange = false
            edit(click.player, menuId)
        }
        if (def.templates.isNotEmpty()) gui.button(13, item(Material.PAINTING, lang.raw("menu-editor.looks-name"), lang.lines("menu-editor.looks-lore", "count" to def.templates.size))) {
            looks(it.player, menuId)
        }
        if (def.icons.isNotEmpty()) gui.button(15, item(Material.GLOW_ITEM_FRAME, lang.raw("menu-editor.icons-name"), lang.lines("menu-editor.icons-lore", "count" to def.icons.size))) {
            icons(it.player, menuId, 0)
        }
        gui.backOrClose({ edit(it, menuId) })
        gui.open(player)
    }

    private fun looks(player: Player, menuId: String) {
        val def = findMenu(menuId) ?: return list(player)
        val gui = GuiBuilder(lang.raw("menu-editor.looks-title", "id" to menuId), 4).frame()
        val slots = GuiLayout.inner(4)
        def.templates.values.take(slots.size).forEachIndexed { i, t ->
            gui.button(slots[i], { _ ->
                def.entry(t.id, def.icons.keys.firstOrNull(), ItemStack(Material.PAPER), emptyList(), extraLore = lang.lines("menu-editor.look-info", "look" to t.id))
            }) { look(it.player, menuId, t.id) }
        }
        gui.backOrClose({ listEditor(it, menuId) })
        gui.open(player)
    }

    private fun look(player: Player, menuId: String, lookId: String) {
        val def = findMenu(menuId) ?: return list(player)
        val t = def.templates[lookId] ?: return looks(player, menuId)
        val again: (Player) -> Unit = { look(it, menuId, lookId) }
        val gui = GuiBuilder(lang.raw("menu-editor.look-title", "id" to menuId, "look" to lookId), 3).frame()
        gui.setItem(4, { def.entry(lookId, def.icons.keys.firstOrNull(), ItemStack(Material.PAPER), emptyList()) })
        gui.button(11, { item(Material.NAME_TAG, lang.raw("menu-editor.name-name"), lang.lines("menu-editor.name-lore", "value" to t.name)) }) { click ->
            askText(click.player, t.name, again) { text ->
                t.name = text
                def.save()
            }
        }
        gui.button(15, item(Material.WRITABLE_BOOK, lang.raw("menu-editor.lore-name"), lang.lines("menu-editor.lore-lore"))) { click ->
            askLines(click.player, t.lore, again) { lines ->
                t.lore = lines
                def.save()
            }
        }
        gui.backOrClose({ looks(it, menuId) })
        gui.open(player)
    }

    private fun icons(player: Player, menuId: String, page: Int) {
        val def = findMenu(menuId) ?: return list(player)
        val keys = def.icons.keys.toList()
        GuiBuilder(lang.raw("menu-editor.icons-title", "id" to menuId), 6).frame().pagedTitle().pageButtons()
            .list(GuiLayout.inner(6), keys) { _, key ->
                com.aayan.albcore.gui.GuiItem.button(item(def.iconOr(key, Material.PAPER), "&f$key", lang.lines("menu-editor.icon-lore"))) { click ->
                    val hand = click.player.inventory.itemInMainHand
                    val icon = if (hand.isEmpty) def.defaultIcon(key) else hand.asOne()
                    if (icon != null) def.icons[key] = icon
                    def.save()
                    click.gui.refresh()
                }
            }
            .backOrClose({ listEditor(it, menuId) })
            .open(player, page)
    }

    // ------------------------------------------------------------------ the shared look (common.yml, no slots)

    private fun commonItems(player: Player) {
        val def = runCatching { ALBCore.instance.menus.get(GuiTheme.COMMON) }.getOrNull() ?: return list(player)
        val gui = GuiBuilder(lang.raw("menu-editor.common-title"), 3).frame()
        val slots = GuiLayout.inner(3)
        def.items.values.take(slots.size).forEachIndexed { i, item ->
            gui.button(slots[i], { _ ->
                MenuDef.build(item.look.base(), item.look.name.ifBlank { "&f${item.id}" }, item.look.lore + lang.lines("menu-editor.common-lore", "id" to item.id), item.look.glow)
            }) { editItem(it.player, GuiTheme.COMMON, item.id) }
        }
        gui.backOrClose({ list(it) })
        gui.open(player)
    }

    // ------------------------------------------------------------------ chat input

    /** Asks for one line of text; [handle] returns true when done (false = ask again). "cancel" runs [onCancel]. */
    private fun ask(player: Player, key: String, onCancel: (Player) -> Unit, vararg placeholders: Pair<String, Any>, handle: (String) -> Boolean) {
        ChatInput.ask(spec.plugin, player, prompt = lang.raw(key, *placeholders), hint = false, onCancel = onCancel, handler = handle)
    }

    /** Shows the current text, saves what the player types, then [reopen]s. */
    private fun askText(player: Player, current: String, reopen: (Player) -> Unit, save: (String) -> Unit) {
        player.closeInventory()
        MessageUtil.send(player, lang.raw("menu-editor.current") + " &r" + current)
        ask(player, "menu-editor.type-text", reopen) { text ->
            save(text)
            lang.send(player, "menu-editor.saved")
            reopen(player)
            true
        }
    }

    /** Lines typed one by one in chat, "done" saves, "clear" empties, "cancel" keeps the old ones. */
    private fun askLines(player: Player, current: List<String>, reopen: (Player) -> Unit, save: (List<String>) -> Unit) {
        player.closeInventory()
        MessageUtil.send(player, lang.raw("menu-editor.current"))
        current.forEach { MessageUtil.send(player, " &r$it") }
        val lines = ArrayList<String>()
        ask(player, "menu-editor.type-lines", reopen) { text ->
            when {
                text.equals("clear", true) -> {
                    save(emptyList())
                    lang.send(player, "menu-editor.saved")
                    reopen(player)
                    true
                }
                text.equals("done", true) -> {
                    save(lines.toList())
                    lang.send(player, "menu-editor.saved")
                    reopen(player)
                    true
                }
                else -> {
                    lines += if (text == ".") "" else text
                    lang.send(player, "menu-editor.line-added", "count" to lines.size)
                    false
                }
            }
        }
    }

    private fun item(material: Material, name: String, lore: List<String>, glow: Boolean = false): ItemStack = item(ItemStack(material), name, lore, glow)

    private fun item(base: ItemStack, name: String, lore: List<String>, glow: Boolean = false): ItemStack {
        val b = ItemBuilder(base).name(name).lore(lore).flag(*ItemFlag.entries.toTypedArray())
        if (glow) b.glow()
        return b.build()
    }

    companion object {
        private const val PREVIEW_ROWS = 4

        /** Menu ids a custom menu may use. */
        val NAME = Regex("[a-z0-9_-]{1,24}")

        /** The editor icon of an action type. */
        fun iconOf(type: String): Material = when (type) {
            "close" -> Material.BARRIER
            "back" -> Material.ARROW
            "open" -> Material.CHEST
            "next-page", "previous-page" -> Material.SPECTRAL_ARROW
            "refresh" -> Material.CLOCK
            "function" -> Material.COMMAND_BLOCK
            "player" -> Material.WRITABLE_BOOK
            "console" -> Material.REPEATING_COMMAND_BLOCK
            "message" -> Material.PAPER
            "sound" -> Material.NOTE_BLOCK
            else -> Material.STRUCTURE_VOID
        }

        /** An editor for one [Menus] registry: lists its menus, "Try it" opens them like "[open] <id>". */
        fun forMenus(
            plugin: org.bukkit.plugin.Plugin,
            menus: Menus,
            functions: (menuId: String) -> List<EditorFunction> = { emptyList() },
            exit: ((Player) -> Unit)? = null,
        ): MenuEditor = MenuEditor(
            MenuEditorSpec(
                plugin = plugin,
                all = { menus.all() },
                find = { menus.find(it) },
                tryOpen = { player, def, back -> menus.open(player, def.id, back) },
                functions = functions,
                exit = exit,
            ),
        )
    }
}
