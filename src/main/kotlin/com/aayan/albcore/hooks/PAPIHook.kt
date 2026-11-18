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
 * PlaceholderAPI support: parse placeholders, and register `%alb_<key>%` placeholders from any ALB plugin.
 *
 * ```
 * PAPIHook.register(this, "level") { player -> PlayerData.getInt(player, "level").toString() }
 * // usable everywhere as %alb_level%
 * ```
 *
 * Handlers may be called from async threads (TAB, scoreboards), so they must be thread-safe and must
 * not touch the world. Registering works even when PlaceholderAPI is missing (it just never gets called).
 * Placeholders are removed automatically when their plugin disables.
 */
object PAPIHook {

    const val IDENTIFIER = "alb"

    private val keyPattern = Regex("[a-z0-9_]+")
    private val handlers = ConcurrentHashMap<String, Handler>()

    @Volatile
    private var enabled = false

    private class Handler(val owner: String, val resolve: (Player) -> String?)

    /** True if PlaceholderAPI is installed and the `alb` expansion is registered. */
    fun isEnabled(): Boolean = enabled

    /** Replaces `%...%` placeholders in [text] for [player]. Returns [text] unchanged without PlaceholderAPI. */
    fun parse(player: OfflinePlayer?, text: String): String {
        if (!enabled || text.indexOf('%') < 0) return text
        return PlaceholderAPI.setPlaceholders(player, text)
    }

    /**
     * Registers `%alb_<key>%`. [key] is lower-case letters, digits and `_`.
     * Registering the same key again replaces the old handler.
     */
    fun register(plugin: Plugin, key: String, resolve: (Player) -> String?) {
        val normalized = key.lowercase()
        require(keyPattern.matches(normalized)) { "Invalid placeholder key '$key' (use a-z, 0-9 and _)" }
        handlers[normalized] = Handler(plugin.name, resolve)
    }

    fun unregister(key: String) {
        handlers.remove(key.lowercase())
    }

    /** Removes every placeholder [plugin] registered. Called automatically when a plugin disables. */
    fun unregisterAll(plugin: Plugin) {
        handlers.values.removeIf { it.owner == plugin.name }
    }

    /** Keys currently registered (without the `alb_` prefix). */
    fun registeredKeys(): Set<String> = handlers.keys.toSortedSet()

    internal fun setup(plugin: ALBCore): Boolean {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return false
        enabled = Expansion(plugin).register()
        return enabled
    }

    internal fun resolve(player: OfflinePlayer?, params: String): String? {
        val online = player?.player ?: return null
        val handler = handlers[params.lowercase()] ?: return null
        return try {
            handler.resolve(online)
        } catch (t: Throwable) {
            ALBCore.instance.logger.log(Level.WARNING, "Placeholder %${IDENTIFIER}_$params% from ${handler.owner} failed", t)
            null
        }
    }

    private class Expansion(private val plugin: ALBCore) : PlaceholderExpansion() {
        override fun getIdentifier(): String = IDENTIFIER
        override fun getAuthor(): String = plugin.pluginMeta.authors.joinToString().ifEmpty { "Aayan" }
        override fun getVersion(): String = plugin.pluginMeta.version

        // Keep the expansion across "/papi reload".
        override fun persist(): Boolean = true
        override fun canRegister(): Boolean = true

        override fun onRequest(player: OfflinePlayer?, params: String): String? = resolve(player, params)
    }
}
