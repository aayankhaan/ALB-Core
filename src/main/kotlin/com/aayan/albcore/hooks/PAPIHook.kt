package com.aayan.albcore.hooks

import com.aayan.albcore.ALBCore
import me.clip.placeholderapi.PlaceholderAPI
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/**
 * PlaceholderAPI support: parse placeholders, and publish placeholders from any ALB plugin.
 *
 * ```
 * PAPIHook.register(this, "level") { player -> ... }                // %alb_level%
 * PAPIHook.register(this, "albskills", "total") { player -> ... }   // %albskills_total%
 * ```
 *
 * Each identifier (`alb`, `albskills`, ...) becomes its own PlaceholderAPI expansion, created on first use.
 * Handlers may be called from async threads (TAB, scoreboards), so they must be thread-safe and must
 * not touch the world. Registering works even when PlaceholderAPI is missing (it just never gets called).
 * Placeholders are removed automatically when their plugin disables. Register from the main thread
 * (normally in `onEnable`).
 */
object PAPIHook {

    /** ALBCore's shared identifier: `%alb_<key>%`. */
    const val IDENTIFIER = "alb"

    // PlaceholderAPI splits "%albskills_total%" at the first "_", so identifiers cannot contain one.
    private val identifierPattern = Regex("[a-z0-9]+")
    private val keyPattern = Regex("[a-z0-9_]+")

    /** identifier -> key -> handler */
    private val handlers = ConcurrentHashMap<String, ConcurrentHashMap<String, Handler>>()
    private val expansions = ConcurrentHashMap<String, Expansion>()

    @Volatile
    private var enabled = false

    @Volatile
    private var core: ALBCore? = null

    private class Handler(val owner: String, val resolve: (Player) -> String?)

    /** True if PlaceholderAPI is installed and ALBCore's expansions are registered. */
    fun isEnabled(): Boolean = enabled

    /** Replaces `%...%` placeholders in [text] for [player]. Returns [text] unchanged without PlaceholderAPI. */
    fun parse(player: OfflinePlayer?, text: String): String {
        if (!enabled || text.indexOf('%') < 0) return text
        return PlaceholderAPI.setPlaceholders(player, text)
    }

    /** Registers `%alb_<key>%`. Same as `register(plugin, "alb", key, resolve)`. */
    fun register(plugin: Plugin, key: String, resolve: (Player) -> String?) =
        register(plugin, IDENTIFIER, key, resolve)

    /**
     * Registers `%<identifier>_<key>%`, e.g. identifier `albskills` and key `total` give `%albskills_total%`.
     * [identifier] is lower-case letters and digits (no `_`); [key] is lower-case letters, digits and `_`.
     * Registering the same placeholder again replaces the old handler.
     */
    fun register(plugin: Plugin, identifier: String, key: String, resolve: (Player) -> String?) {
        val id = identifier.lowercase()
        val normalizedKey = key.lowercase()
        require(identifierPattern.matches(id)) { "Invalid placeholder identifier '$identifier' (use a-z and 0-9, no _)" }
        require(keyPattern.matches(normalizedKey)) { "Invalid placeholder key '$key' (use a-z, 0-9 and _)" }
        handlers.computeIfAbsent(id) { ConcurrentHashMap() }[normalizedKey] = Handler(plugin.name, resolve)
        if (enabled) ensureExpansion(id)
    }

    fun unregister(identifier: String, key: String) {
        handlers[identifier.lowercase()]?.remove(key.lowercase())
    }

    /** Removes `%alb_<key>%`. */
    fun unregister(key: String) = unregister(IDENTIFIER, key)

    /**
     * Removes every placeholder [plugin] registered, and the expansions that become empty
     * (except `alb`). Called automatically when a plugin disables.
     */
    fun unregisterAll(plugin: Plugin) {
        for ((id, keys) in handlers) {
            keys.values.removeIf { it.owner == plugin.name }
            if (keys.isEmpty() && id != IDENTIFIER) {
                handlers.remove(id, keys)
                expansions.remove(id)?.unregister()
            }
        }
    }

    /** Every registered placeholder without the `%` signs, e.g. `alb_level`, `albskills_total`. */
    fun registeredPlaceholders(): Set<String> =
        handlers.flatMap { (id, keys) -> keys.keys.map { "${id}_$it" } }.toSortedSet()

    internal fun setup(plugin: ALBCore): Boolean {
        core = plugin
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return false
        enabled = true
        ensureExpansion(IDENTIFIER)
        handlers.keys.forEach(::ensureExpansion)
        return expansions[IDENTIFIER]?.isRegistered == true
    }

    /** Looks up and runs a handler. Returns null for unknown placeholders or offline players. */
    internal fun resolve(identifier: String, player: OfflinePlayer?, params: String): String? {
        val online = player?.player ?: return null
        val handler = handlers[identifier]?.get(params.lowercase()) ?: return null
        return try {
            handler.resolve(online)
        } catch (t: Throwable) {
            core?.logger?.log(Level.WARNING, "Placeholder %${identifier}_$params% from ${handler.owner} failed", t)
            null
        }
    }

    private fun ensureExpansion(identifier: String) {
        val plugin = core ?: return
        expansions.computeIfAbsent(identifier) { id -> Expansion(plugin, id) }.let { expansion ->
            if (!expansion.isRegistered) expansion.register()
        }
    }

    private class Expansion(private val plugin: ALBCore, private val id: String) : PlaceholderExpansion() {
        override fun getIdentifier(): String = id
        override fun getAuthor(): String = plugin.pluginMeta.authors.joinToString().ifEmpty { "Aayan" }
        override fun getVersion(): String = plugin.pluginMeta.version

        // Keep the expansion across "/papi reload".
        override fun persist(): Boolean = true
        override fun canRegister(): Boolean = true

        override fun onRequest(player: OfflinePlayer?, params: String): String? = resolve(id, player, params)
    }
}
