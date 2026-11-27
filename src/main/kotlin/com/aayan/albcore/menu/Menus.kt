package com.aayan.albcore.menu

import com.aayan.albcore.ALBCore
import com.aayan.albcore.gui.GuiBuilder
import com.aayan.albcore.gui.GuiClick
import com.aayan.albcore.gui.GuiSounds
import com.aayan.albcore.lang.Lang
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A plugin's YAML menus: one file each in `plugins/<Plugin>/menus/`. The [builtIn] ones are copied from
 * the jar (`src/main/resources/menus/<id>.yml`) when missing; any other `.yml` an admin puts there is a
 * custom menu (items and actions only).
 *
 * ```
 * val menus = Menus(this, builtIn = listOf("homes", "home-manage"), lang = lang)   // in onEnable
 * menus.function("sethome", permission = "homes.set") { click -> askHomeName(click.player) }
 * menus.opener("homes") { player, back -> openHomes(player, back) }           // "[open] homes" runs your code
 *
 * fun openHomes(player: Player, back: ((Player) -> Unit)?) = menus.open(player, "homes", MenuContext(
 *     back = back,
 *     placeholders = { listOf("count" to homesOf(it).size) },
 *     entries = { homesOf(player).map { home -> MenuEntry({ icon(home) }) { click -> teleport(click.player, home) } } },
 * ))
 * ```
 *
 * "[open] <id>" opens: the [opener] registered for that id, else the menu file as it is, else tells
 * the player the menu is missing. "[open] <plugin>:<id>" opens a menu of another plugin's registry.
 * Main thread only.
 */
class Menus(
    val plugin: Plugin,
    val builtIn: List<String> = emptyList(),
    private val lang: Lang? = null,
    private val header: List<String> = emptyList(),
) {

    val folder = File(plugin.dataFolder, "menus")
    private val defs = LinkedHashMap<String, MenuDef>()
    private val extra = LinkedHashMap<String, MenuDef>()
    private val openers = LinkedHashMap<String, (Player, ((Player) -> Unit)?) -> Unit>()
    private val functions = LinkedHashMap<String, MenuFunction>()

    init {
        reload()
        registries[plugin.name.lowercase()] = this
    }

    /** Re-reads every menu file. */
    fun reload() {
        defs.clear()
        for (id in builtIn) {
            val defaults = plugin.getResource("menus/$id.yml")?.reader(Charsets.UTF_8)?.use { YamlConfiguration.loadConfiguration(it) }
            if (defaults == null) plugin.logger.warning("menus/$id.yml is missing from the plugin jar.")
            defs[id] = MenuDef(id, File(folder, "$id.yml"), defaults, plugin.logger, header).also { it.load() }
        }
        for ((id, def) in extra) {
            def.load()
            defs[id] = def
        }
        folder.listFiles { f -> f.isFile && f.name.endsWith(".yml") && !f.name.endsWith(".old.yml") }
            ?.sortedBy { it.name }
            ?.forEach { f ->
                val id = f.nameWithoutExtension
                if (id in defs) return@forEach
                defs[id] = MenuDef(id, f, null, plugin.logger, header).also { it.load() }
            }
    }

    /** Adds a menu whose file lives elsewhere (e.g. an add-on's folder). It is re-read on [reload]. */
    fun add(def: MenuDef) {
        extra[def.id] = def
        defs[def.id] = def
    }

    fun remove(id: String) {
        extra.remove(id)
        defs.remove(id)
    }

    fun get(id: String): MenuDef = defs[id] ?: error("No menu '$id' in ${plugin.name}'s menus folder")

    fun find(id: String): MenuDef? = defs[id]

    fun all(): Collection<MenuDef> = defs.values

    /** The custom menu with this `command:` value (case ignored), for plugins that let admins add menu commands. */
    fun byCommand(command: String): MenuDef? =
        defs.values.firstOrNull { !it.builtIn && it.command.isNotBlank() && it.command.equals(command, ignoreCase = true) }

    // ------------------------------------------------------------------ code hooks

    /** Makes "[open] [id]" (and [open] by id) run [open] instead of showing the file as it is. */
    fun opener(id: String, open: (player: Player, back: ((Player) -> Unit)?) -> Unit) {
        openers[id] = open
    }

    /** A function any menu of this plugin can run with "[function] [id]". */
    fun function(
        id: String,
        permission: String? = null,
        decorate: ((Player) -> Decoration)? = null,
        click: (GuiClick) -> Unit,
    ) {
        functions[id] = MenuFunction(permission, decorate, click)
    }

    /** Ids of the functions registered with [function]. */
    fun functionIds(): Set<String> = functions.keys

    /** The permission function [id] needs (null = none). */
    fun permissionOf(id: String): String? = functions[id]?.permission?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------ opening

    /** Opens menu [id] the way "[open] id" does ([back] = where its "[back]" goes). */
    fun open(player: Player, id: String, back: ((Player) -> Unit)? = null) {
        val target = id.trim()
        if (':' in target) {
            val other = registries[target.substringBefore(':').lowercase()]
            if (other == null) missing(player, target) else other.open(player, target.substringAfter(':'), back)
            return
        }
        val opener = openers[target]
        if (opener != null) {
            opener(player, back)
            return
        }
        val def = defs[target]
        if (def == null) missing(player, target) else open(player, def, MenuContext(back = back))
    }

    /** Opens menu [id] with [ctx] (your entries, placeholders, functions...). Null when the file is missing. */
    fun open(player: Player, id: String, ctx: MenuContext): GuiBuilder? {
        val def = defs[id]
        if (def == null) {
            missing(player, id)
            return null
        }
        return open(player, def, ctx)
    }

    fun open(player: Player, def: MenuDef, ctx: MenuContext): GuiBuilder = MenuView.open(this, player, def, ctx)

    /** The GUI of menu [id] with [ctx], not opened yet, to add more with the GuiBuilder API first. */
    fun build(player: Player, id: String, ctx: MenuContext = MenuContext()): GuiBuilder = MenuView.build(this, player, get(id), ctx)

    /** {player} and {prefix} (from this registry's [Lang]) for every menu text. */
    fun basePlaceholders(player: Player): List<Pair<String, Any>> =
        listOf("player" to player.name, "prefix" to (lang?.raw("prefix")?.takeIf { it != "prefix" } ?: ""))

    // ------------------------------------------------------------------ internals

    internal fun functionFor(ctx: MenuContext, id: String): MenuFunction? = ctx.functions[id] ?: functions[id]

    /** Runs function [id]; false (and a message) when it is missing or the player lacks its permission. */
    internal fun runFunction(click: GuiClick, id: String, ctx: MenuContext, back: (Player) -> Unit): Boolean {
        val player = click.player
        val function = functionFor(ctx, id)
        if (function == null) {
            missing(player, id)
            return false
        }
        val permission = function.permission?.takeIf { it.isNotBlank() } ?: permissionOf(id)
        if (permission != null && !player.hasPermission(permission)) {
            GuiSounds.denied(player)
            coreLang()?.send(player, "menus.no-permission")
            return false
        }
        function.click(click)
        return true
    }

    private fun missing(player: Player, what: String) {
        player.closeInventory()
        GuiSounds.denied(player)
        coreLang()?.send(player, "menus.missing", "what" to what)
        plugin.logger.warning("Menu action asked for '$what', but ${plugin.name} has no such menu or function.")
    }

    private fun coreLang(): Lang? = runCatching { ALBCore.instance.lang }.getOrNull()

    companion object {
        private val registries = ConcurrentHashMap<String, Menus>()

        /** The registry of plugin [name] (case ignored), for "[open] <plugin>:<menu>". */
        fun of(name: String): Menus? = registries[name.lowercase()]

        internal fun unregisterAll(plugin: Plugin) {
            registries.values.removeIf { it.plugin == plugin }
        }
    }
}
