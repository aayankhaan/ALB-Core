package com.aayan.albcore.lang

import com.aayan.albcore.config.ConfigFile
import com.aayan.albcore.hooks.PAPIHook
import com.aayan.albcore.utils.ColorUtil
import com.aayan.albcore.utils.MessageUtil
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.io.File
import java.time.Duration
import java.util.UUID

/**
 * A plugin's messages, from a YAML file in its data folder (default `lang.yml`, copied from the jar).
 *
 * ```
 * val lang = Lang(this)                                   // in onEnable
 * lang.send(player, "home.created", "name" to home.name)  // "{prefix}&aHome &f{name} &acreated."
 * val lore = lang.lines("home.lore", "warps" to listOf("Spawn", "Shop"))
 * ```
 *
 * - Missing keys fall back to the copy inside the jar, then to the key itself, so a typo is visible.
 * - `{prefix}` is the file's `prefix` key. Placeholder values are escaped (player names can't inject
 *   MiniMessage tags). `{{name}}` stays the literal text `{name}`.
 * - Colours: `&` codes, `&#rrggbb` and MiniMessage (through [ColorUtil]). PlaceholderAPI placeholders
 *   (`%...%`) work in every message [send] gives to a player.
 * - A message may start with `[chat]`, `[actionbar]`, `[title]`, `[bossbar]` or `[off]` to choose where it
 *   shows up (see [MessageMode]).
 * - Versioning: when the jar's `lang-version` is higher than the server file's, the server file is kept
 *   as `<name>-old-<version>.yml` and replaced. Keys added in a newer jar are written into the file,
 *   and keys the jar no longer has are removed.
 *
 * Load and save on the main thread (startup, reload commands): they touch the disk.
 */
class Lang(private val plugin: Plugin, val fileName: String = "lang.yml") {

    private val file: ConfigFile

    /** How long a `[title]` message shows. */
    var titleTimes: Title.Times = Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500))

    /** How long a `[bossbar]` message shows. */
    var bossBarSeconds: Long = 5

    var bossBarColor: BossBar.Color = BossBar.Color.BLUE

    init {
        upgrade()
        file = ConfigFile(plugin, fileName)
        addMissing()
    }

    /** Re-reads the file (and every added source) from disk. */
    fun reload() {
        file.reload()
        addMissing()
        for (source in sources.values.distinct()) source.load()
    }

    // ------------------------------------------------------------------ reading

    /** Raw text with {prefix} and the given {placeholders} filled in (still with colour codes, no mode tag). */
    fun raw(key: String, vararg placeholders: Pair<String, Any>): String {
        val text = configFor(key).getString(key) ?: key
        return LangText.fill(LangText.stripMode(text).second, prefix(), placeholders)
    }

    /** A message as lines (a list in the file, or one line). A line that is only a list placeholder becomes one line per value. */
    fun lines(key: String, vararg placeholders: Pair<String, Any>): List<String> {
        val config = configFor(key)
        val list = if (config.isList(key)) config.getStringList(key) else listOfNotNull(config.getString(key))
        return LangText.fillLines(list, prefix(), placeholders)
    }

    fun component(key: String, vararg placeholders: Pair<String, Any>): Component =
        ColorUtil.parse(raw(key, *placeholders))

    /** True if the file (or the jar's copy) has [key]. */
    fun has(key: String): Boolean = configFor(key).contains(key)

    fun mode(key: String): MessageMode = LangText.stripMode(configFor(key).getString(key) ?: "").first

    // ------------------------------------------------------------------ sending

    /** Sends the message where its mode says (chat by default). Empty text or `[off]` sends nothing. */
    fun send(sender: CommandSender, key: String, vararg placeholders: Pair<String, Any>) {
        val text = raw(key, *placeholders).let { if (sender is Player) PAPIHook.parse(sender, it) else it }
        if (text.isBlank()) return
        when (mode(key)) {
            MessageMode.CHAT -> MessageUtil.send(sender, text)
            MessageMode.ACTIONBAR -> if (sender is Player) sender.sendActionBar(ColorUtil.parse(text)) else MessageUtil.send(sender, text)
            MessageMode.TITLE -> if (sender is Player) {
                sender.showTitle(Title.title(Component.empty(), ColorUtil.parse(text), titleTimes))
            } else {
                MessageUtil.send(sender, text)
            }
            MessageMode.BOSSBAR -> if (sender is Player) bossBar(sender, text) else MessageUtil.send(sender, text)
            MessageMode.OFF -> {}
        }
    }

    /** "1h 5m" style text from the file's `time.hours` / `time.minutes` / `time.seconds` keys ({h} {m} {s}). */
    fun duration(millis: Long): String {
        val totalSeconds = (millis + 999) / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val key = when {
            h > 0 -> "time.hours"
            m > 0 -> "time.minutes"
            else -> "time.seconds"
        }
        return raw(key, "h" to h, "m" to m, "s" to s)
    }

    private val bars = HashMap<UUID, BossBar>()

    /** Shows [text] in a boss bar for [bossBarSeconds]; it empties while it shows. A newer one replaces it. */
    private fun bossBar(player: Player, text: String) {
        val ticks = bossBarSeconds.coerceIn(1, 600) * 20
        bars.remove(player.uniqueId)?.let { player.hideBossBar(it) }
        val bar = BossBar.bossBar(ColorUtil.parse(text), 1f, bossBarColor, BossBar.Overlay.PROGRESS)
        bars[player.uniqueId] = bar
        player.showBossBar(bar)
        var left = ticks
        plugin.server.scheduler.runTaskTimer(plugin, { task ->
            left -= 2
            if (left <= 0 || bars[player.uniqueId] !== bar || !player.isOnline) {
                task.cancel()
                player.hideBossBar(bar)
                if (bars[player.uniqueId] === bar) bars.remove(player.uniqueId)
                return@runTaskTimer
            }
            bar.progress((left.toFloat() / ticks).coerceIn(0f, 1f))
        }, 2L, 2L)
    }

    // ------------------------------------------------------------------ extra sources (addons)

    /** Messages of an add-on in their own file: its top-level sections are read from there. */
    private class Source(val file: File, val defaults: YamlConfiguration) {
        lateinit var config: YamlConfiguration

        fun load() {
            if (file.isFile) {
                val have = YamlConfiguration.loadConfiguration(file).getInt("lang-version", 1)
                val want = defaults.getInt("lang-version", 1)
                if (have < want) {
                    val backup = File(file.parentFile, "${file.nameWithoutExtension}-old-$have.yml")
                    backup.delete()
                    file.renameTo(backup)
                }
            }
            config = if (file.isFile) YamlConfiguration.loadConfiguration(file) else YamlConfiguration()
            config.setDefaults(defaults)
            config.options().copyDefaults(true)
            for (key in config.getKeys(true).toList().sortedByDescending { it.length }) {
                if (!defaults.contains(key, true)) config.set(key, null)
            }
            save()
        }

        fun save() {
            file.parentFile?.mkdirs()
            runCatching { config.save(file) }
        }
    }

    /** Top-level section -> the file that has it. */
    private val sources = LinkedHashMap<String, Source>()

    /**
     * Adds messages from another file ([file] on disk, [defaults] from a jar), e.g. an add-on's. Their
     * top-level section names must not exist in the main file; keys under them are read from [file].
     */
    fun addSource(file: File, defaults: YamlConfiguration) {
        val source = Source(file, defaults).also { it.load() }
        for (section in defaults.getKeys(false)) if (section != "lang-version") sources[section] = source
    }

    fun removeSource(file: File) {
        sources.entries.removeIf { it.value.file == file }
    }

    // ------------------------------------------------------------------ changing texts (for editors)

    /** Every message key (dotted path) under [section], or all top-level sections when [section] is null. */
    fun keys(section: String?): List<String> {
        if (section == null) {
            val main = file.config.getKeys(false).filter { file.config.isConfigurationSection(it) }
            return (main + sources.keys).distinct().sorted()
        }
        val config = configFor(section)
        val sec = config.getConfigurationSection(section) ?: return emptyList()
        return sec.getKeys(true).map { "$section.$it" }.filter { !config.isConfigurationSection(it) }.sorted()
    }

    fun isList(key: String): Boolean = configFor(key).isList(key)

    /** The stored text without its mode tag. */
    fun text(key: String): String = LangText.stripMode(configFor(key).getString(key) ?: "").second

    /** Replaces the text of [key] (keeps its mode) and saves the file. */
    fun setText(key: String, text: String) {
        configFor(key).set(key, LangText.withMode(mode(key), text))
        saveFor(key)
    }

    fun setLines(key: String, lines: List<String>) {
        configFor(key).set(key, lines)
        saveFor(key)
    }

    fun setMode(key: String, mode: MessageMode) {
        configFor(key).set(key, LangText.withMode(mode, text(key)))
        saveFor(key)
    }

    // ------------------------------------------------------------------ internals

    private fun prefix(): String = LangText.stripMode(file.config.getString("prefix") ?: "").second

    private fun configFor(key: String): YamlConfiguration = sources[key.substringBefore('.')]?.config ?: file.config

    private fun saveFor(key: String) {
        val source = sources[key.substringBefore('.')]
        if (source != null) source.save() else file.save()
    }

    /** A redesign (higher lang-version in the jar) keeps the server's file as <name>-old-<version>.yml and starts fresh. */
    private fun upgrade() {
        val current = File(plugin.dataFolder, fileName)
        if (!current.isFile) return
        val have = YamlConfiguration.loadConfiguration(current).getInt("lang-version", 1)
        val want = plugin.getResource(fileName)?.reader(Charsets.UTF_8)?.use {
            YamlConfiguration.loadConfiguration(it).getInt("lang-version", 1)
        } ?: return
        if (have >= want) return
        val backup = File(current.parentFile, "${current.nameWithoutExtension}-old-$have.yml")
        backup.delete()
        if (current.renameTo(backup)) {
            plugin.logger.info("$fileName was redesigned (version $have -> $want). Your old one is saved as ${backup.name}.")
        }
    }

    /** Writes keys added in a newer jar into the server file and drops keys the jar no longer has. */
    private fun addMissing() {
        val config = file.config
        config.options().copyDefaults(true)
        val defaults = config.defaults
        if (defaults != null) {
            for (key in config.getKeys(true).toList()) {
                if (!config.isConfigurationSection(key) && !defaults.contains(key, true)) config.set(key, null)
            }
            for (key in config.getKeys(true).toList().sortedByDescending { it.length }) {
                if (config.isConfigurationSection(key) && !defaults.contains(key, true)) config.set(key, null)
            }
        }
        runCatching { file.save() }
    }
}
