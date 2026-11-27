package com.aayan.albcore.menu

import com.aayan.albcore.gui.GuiFill
import com.aayan.albcore.gui.ListLayout
import com.aayan.albcore.utils.ItemBuilder
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import java.io.File
import java.util.Base64
import java.util.logging.Logger

/** How something looks: an item (a material, or an exact item set in game), a name, lore and glow. */
class MenuLook {
    var material: Material = Material.PAPER

    /** An exact item (keeps Nexo items / custom model data), saved as `item-data: data:<base64>`; wins over [material]. */
    var custom: ItemStack? = null
    var name: String = ""
    var lore: List<String> = emptyList()
    var glow: Boolean = false

    fun base(): ItemStack = custom?.clone() ?: ItemStack(material)

    fun copyFrom(other: MenuLook) {
        material = other.material
        custom = other.custom?.clone()
        name = other.name
        lore = other.lore
        glow = other.glow
    }

    internal fun read(s: ConfigurationSection) {
        material = Material.matchMaterial(s.getString("item") ?: "") ?: Material.PAPER
        custom = MenuDef.decode(s.getString("item-data"))
        name = s.getString("name") ?: ""
        lore = s.getStringList("lore")
        glow = s.getBoolean("glow")
    }

    internal fun write(yml: YamlConfiguration, path: String) {
        yml.set("$path.item", material.name)
        custom?.let { yml.set("$path.item-data", MenuDef.encode(it)) }
        yml.set("$path.name", name)
        yml.set("$path.lore", lore)
        if (glow) yml.set("$path.glow", true)
    }
}

/**
 * One item of a menu. [actions] run in order when it is clicked (none = decoration; see [MenuAction]).
 * With a [permission], players without it see [locked] (and get [lockedActions]) or nothing ([hideLocked]).
 * Several items may share a slot: the first one the viewer can see is shown.
 */
class MenuItem(val id: String) {
    var slot: Int = -1
    val look = MenuLook()
    var actions: MutableList<String> = mutableListOf()
    var permission: String = ""
    var hideLocked: Boolean = true
    val locked = MenuLook()
    var lockedActions: MutableList<String> = mutableListOf()

    /** The value of every action of type [type] (e.g. "function" -> ["open-settings"]). */
    fun args(type: String, locked: Boolean = false): List<String> =
        (if (locked) lockedActions else actions).mapNotNull { MenuAction.parse(it) }.filter { it.type == type }.map { it.arg }

    fun copyFrom(other: MenuItem) {
        look.copyFrom(other.look)
        actions = other.actions.toMutableList()
        permission = other.permission
        hideLocked = other.hideLocked
        locked.copyFrom(other.locked)
        lockedActions = other.lockedActions.toMutableList()
    }
}

/** How each entry of a list area looks (e.g. one home). Its {placeholders} are filled by the code. */
class MenuTemplate(val id: String) {
    var name: String = "{name}"
    var lore: List<String> = emptyList()
}

/**
 * A menu read from `plugins/<Plugin>/menus/<id>.yml`. Everything a player sees is in the file:
 * title, rows, frame fill, every item (slot, look, actions, permission and locked look), the list
 * area (slots, layout), how list entries look ([templates]) and their [icons].
 *
 * File format (see ALBCore's `menus/common.yml` for a full example):
 * ```
 * version: 2          # file format (keep 2)
 * revision: 1         # bump in the jar when you redesign the menu: older server copies are replaced
 * title: "&8ʜᴏᴍᴇꜱ"
 * rows: 6
 * fill: edges         # none | edges | all  (theme frame panes)
 * items:
 *   header: { slot: 4, item: RED_BED, name: "&#7DD3FC&lHomes", lore: ["&7Your homes."], actions: [] }
 *   back:   { slot: 49, item: ARROW, name: "&#7DD3FC← Back", actions: ["[back]"] }
 *   vip:
 *     slot: 50
 *     item: GOLD_INGOT
 *     name: "&#FBBF24VIP homes"
 *     actions: ["[function] vip-homes"]
 *     permission: "homes.vip"
 *     locked: { hide: false, item: GRAY_DYE, name: "&8VIP only", actions: ["[sound] entity.villager.no"] }
 * list:
 *   slots: "10-16,19-25,28-34"
 *   layout: centered  # fill | centered
 *   look:
 *     entry: { name: "&f{name}", lore: ["&7{world}"] }
 *   icons:
 *     nether: NETHERRACK
 * ```
 * Custom menus (any extra .yml an admin adds) may also have a [command] the plugin can register.
 */
class MenuDef(
    val id: String,
    val file: File,
    private val defaults: YamlConfiguration?,
    private val logger: Logger,
    private val header: List<String> = emptyList(),
) {

    /** True for a menu that ships in the plugin jar. */
    val builtIn: Boolean get() = defaults != null

    /** Bumped in the plugin's file when a built-in menu is redesigned (older copies are replaced). */
    private val revision: Int get() = defaults?.getInt("revision", 0) ?: 0

    var title = ""
    var rows = 3
    var fill = GuiFill.EDGES
    var command = ""
    var about: List<String> = emptyList()
    val items = LinkedHashMap<String, MenuItem>()
    var listSlots: List<Int> = emptyList()
    var listLayout = ListLayout.FILL
    val templates = LinkedHashMap<String, MenuTemplate>()
    val icons = LinkedHashMap<String, ItemStack>()

    val size: Int get() = rows * 9

    /** (Re)reads the file. A missing built-in file is copied from the jar first. */
    fun load() {
        if (!file.isFile && defaults != null) {
            file.parentFile?.mkdirs()
            defaults.save(file)
        }
        var yml = if (file.isFile) YamlConfiguration.loadConfiguration(file) else YamlConfiguration()
        // Older format, or older than a redesign of this menu ("revision" in the jar): keep the server's
        // file as <id>.old.yml and start from the jar's version.
        val outdated = yml.getInt("version", 1) < VERSION || yml.getInt("revision", 0) < revision
        if (outdated && defaults != null) {
            val old = File(file.parentFile, "$id.old.yml")
            old.delete()
            file.renameTo(old)
            defaults.save(file)
            logger.info("menus/$id.yml was redesigned; your old one is saved as ${old.name}.")
            yml = YamlConfiguration.loadConfiguration(file)
        }
        read(yml)
    }

    internal fun read(yml: YamlConfiguration) {
        title = yml.getString("title") ?: ""
        rows = yml.getInt("rows", 3).coerceIn(1, 6)
        fill = runCatching { GuiFill.valueOf((yml.getString("fill") ?: "edges").uppercase()) }.getOrDefault(GuiFill.EDGES)
        command = yml.getString("command") ?: ""
        about = yml.getStringList("about")
        items.clear()
        yml.getConfigurationSection("items")?.let { section ->
            for (key in section.getKeys(false)) {
                val s = section.getConfigurationSection(key) ?: continue
                items[key] = MenuItem(key).apply {
                    slot = s.getInt("slot", -1)
                    look.read(s)
                    actions = s.getStringList("actions").toMutableList()
                    permission = s.getString("permission") ?: ""
                    s.getConfigurationSection("locked")?.let { l ->
                        hideLocked = l.getBoolean("hide", true)
                        locked.read(l)
                        lockedActions = l.getStringList("actions").toMutableList()
                    }
                }
            }
        }
        listSlots = parseSlots(yml.getString("list.slots") ?: "")
        listLayout = runCatching { ListLayout.valueOf((yml.getString("list.layout") ?: "fill").uppercase()) }.getOrDefault(ListLayout.FILL)
        templates.clear()
        yml.getConfigurationSection("list.look")?.let { section ->
            for (key in section.getKeys(false)) {
                val s = section.getConfigurationSection(key) ?: continue
                templates[key] = MenuTemplate(key).apply {
                    name = s.getString("name") ?: "{name}"
                    lore = s.getStringList("lore")
                }
            }
        }
        icons.clear()
        yml.getConfigurationSection("list.icons")?.let { section ->
            for (key in section.getKeys(false)) {
                val raw = section.getString(key) ?: continue
                (decode(raw) ?: Material.matchMaterial(raw)?.let { ItemStack(it) })?.let { icons[key] = it }
            }
        }
    }

    /** Writes the menu back to its file (e.g. after an in-game change). */
    fun save() {
        val yml = YamlConfiguration()
        val fileHeader = defaults?.options()?.getHeader().orEmpty().ifEmpty { header }.ifEmpty { listOf("Menu '$id'.") }
        yml.options().setHeader(fileHeader)
        yml.set("version", VERSION)
        if (revision > 0) yml.set("revision", revision)
        yml.set("title", title)
        yml.set("rows", rows)
        yml.set("fill", fill.name.lowercase())
        if (!builtIn) yml.set("command", command)
        if (about.isNotEmpty()) yml.set("about", about)
        for (item in items.values) {
            val p = "items.${item.id}"
            yml.set("$p.slot", item.slot)
            item.look.write(yml, p)
            yml.set("$p.actions", item.actions)
            if (item.permission.isNotBlank()) {
                yml.set("$p.permission", item.permission)
                yml.set("$p.locked.hide", item.hideLocked)
                item.locked.write(yml, "$p.locked")
                yml.set("$p.locked.actions", item.lockedActions)
            }
        }
        if (listSlots.isNotEmpty() || templates.isNotEmpty()) yml.set("list.slots", formatSlots(listSlots))
        if (listLayout != ListLayout.FILL) yml.set("list.layout", listLayout.name.lowercase())
        for (t in templates.values) {
            yml.set("list.look.${t.id}.name", t.name)
            yml.set("list.look.${t.id}.lore", t.lore)
        }
        for ((key, item) in icons) yml.set("list.icons.$key", if (isPlain(item)) item.type.name else encode(item))
        file.parentFile?.mkdirs()
        runCatching { yml.save(file) }.onFailure { logger.severe("Could not save ${file.name}: ${it.message}") }
    }

    /** Puts the menu back to the jar's version (built-in menus only). */
    fun reset() {
        if (defaults == null) return
        file.delete()
        load()
    }

    /** The first item standing in [slot] (null if none). */
    fun itemAt(slot: Int): MenuItem? = items.values.firstOrNull { it.slot == slot }

    /** A free id for a new item. */
    fun newItemId(): String {
        var n = items.size + 1
        while ("item-$n" in items) n++
        return "item-$n"
    }

    /** Moves the item in [from] to [to]; an item already in [to] goes to [from] (swap). Saves. */
    fun move(from: Int, to: Int) {
        val a = itemAt(from) ?: return
        val b = itemAt(to)
        a.slot = to
        b?.slot = from
        save()
    }

    /** The jar's icon for list entry [key] (null if it has none). */
    fun defaultIcon(key: String): ItemStack? {
        val raw = defaults?.getString("list.icons.$key") ?: return null
        return decode(raw) ?: Material.matchMaterial(raw)?.let { ItemStack(it) }
    }

    /** The icon for list entry [key], or [fallback] when the file has none. */
    fun iconOr(key: String, fallback: Material): ItemStack = icons[key]?.clone() ?: ItemStack(fallback)

    /** Item [itemId]'s look with [placeholders] filled ([viewer] = whose PlaceholderAPI placeholders). Barrier if missing. */
    fun item(itemId: String, placeholders: List<Pair<String, Any>> = emptyList(), viewer: Player? = null): ItemStack {
        val look = items[itemId]?.look ?: return ItemStack(Material.BARRIER)
        return build(look.base(), fill(look.name, placeholders), fillLines(look.lore, placeholders), look.glow, viewer)
    }

    /** One list entry: icon [key] (or [fallback]), look [templateId], [placeholders] filled. */
    fun entry(
        templateId: String,
        key: String?,
        fallback: ItemStack,
        placeholders: List<Pair<String, Any>>,
        glow: Boolean = false,
        extraLore: List<String> = emptyList(),
        viewer: Player? = null,
    ): ItemStack {
        val t = templates[templateId]
        val base = key?.let { icons[it]?.clone() } ?: fallback
        return build(base, fill(t?.name ?: "{name}", placeholders), fillLines(t?.lore ?: emptyList(), placeholders) + extraLore, glow, viewer)
    }

    companion object {
        /** File format version. Files with a lower `version` are replaced by the jar's copy. */
        const val VERSION = 2

        /** Builds a menu item: name, lore, every item flag hidden, optional glow. [viewer] fills PlaceholderAPI placeholders. */
        fun build(base: ItemStack, name: String, lore: List<String>, glow: Boolean, viewer: Player? = null): ItemStack {
            if (base.type.isAir) return base
            val b = ItemBuilder(base).name(viewer, name).lore(lore, viewer).flag(*ItemFlag.entries.toTypedArray())
            if (glow) b.glow()
            return b.build()
        }

        /** Replaces every {key} in [text]. List values are joined with spaces. Values are not escaped. */
        fun fill(text: String, placeholders: List<Pair<String, Any>>): String {
            var out = text
            for ((k, v) in placeholders) out = out.replace("{$k}", if (v is List<*>) v.joinToString(" ") else v.toString())
            return out
        }

        /** Fills placeholders; a line that is only a list placeholder (e.g. "{roles}") becomes one line per value. "\n" splits lines. */
        fun fillLines(lines: List<String>, placeholders: List<Pair<String, Any>>): List<String> {
            val out = ArrayList<String>()
            for (line in lines) {
                val multi = placeholders.firstOrNull { (k, v) -> line.trim() == "{$k}" && v is List<*> }
                if (multi != null) {
                    out += (multi.second as List<*>).map { it.toString() }
                    continue
                }
                out += fill(line, placeholders).split('\n')
            }
            return out
        }

        /** "10-16,19-25" -> [10..16, 19..25]. Bad parts are skipped. */
        fun parseSlots(text: String): List<Int> = text.split(',').flatMap { part ->
            val p = part.trim()
            when {
                p.isEmpty() -> emptyList()
                '-' in p -> {
                    val (a, b) = p.split('-', limit = 2).map { it.trim().toIntOrNull() ?: -1 }
                    if (a < 0 || b < a) emptyList() else (a..b).toList()
                }
                else -> listOfNotNull(p.toIntOrNull())
            }
        }.distinct()

        /** [10, 11, 12, 20] -> "10-12,20". */
        fun formatSlots(slots: List<Int>): String {
            if (slots.isEmpty()) return ""
            val sorted = slots.sorted()
            val out = ArrayList<String>()
            var start = sorted[0]
            var prev = sorted[0]
            for (s in sorted.drop(1) + Int.MIN_VALUE) {
                if (s == prev + 1) {
                    prev = s
                    continue
                }
                out += if (start == prev) "$start" else "$start-$prev"
                start = s
                prev = s
            }
            return out.joinToString(",")
        }

        fun encode(item: ItemStack): String = "data:" + Base64.getEncoder().encodeToString(item.serializeAsBytes())

        fun decode(raw: String?): ItemStack? {
            if (raw == null || !raw.startsWith("data:")) return null
            return runCatching { ItemStack.deserializeBytes(Base64.getDecoder().decode(raw.removePrefix("data:"))) }.getOrNull()
        }

        /** A plain item (just a material) is saved as its name, anything else as data. */
        fun isPlain(item: ItemStack): Boolean = !item.hasItemMeta() && item.amount == 1
    }
}
