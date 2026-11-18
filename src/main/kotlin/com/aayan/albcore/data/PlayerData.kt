package com.aayan.albcore.data

import com.aayan.albcore.ALBCore
import org.bukkit.OfflinePlayer
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Simple access to per-player data (replaces the old `PlayerDataUtil`).
 *
 * Data of online players is loaded before they join and kept in memory, so every getter here is
 * instant and safe on any thread. `set` only changes memory; ALBCore saves in the background
 * (autosave, quit, shutdown) to the storage chosen in config.yml (`yaml`, `sqlite` or `mysql`).
 *
 * ```
 * val kills = PlayerData.getInt(player, "stats.kills")
 * PlayerData.set(player, "stats.kills", kills + 1)
 * // or, atomically:
 * PlayerData.increment(player, "stats.kills")
 * ```
 *
 * For offline players use [fetch] (read) and [edit] (change), which are async.
 */
object PlayerData {

    private val service: PlayerDataService
        get() = ALBCore.instance.playerData

    /** The live data of a loaded (online) player, or null if it is not loaded. */
    fun of(player: OfflinePlayer): PlayerDataHolder? = service.get(player.uniqueId)

    /** The live data of a loaded (online) player, or null if it is not loaded. */
    fun of(uuid: UUID): PlayerDataHolder? = service.get(uuid)

    fun isLoaded(player: OfflinePlayer): Boolean = service.isLoaded(player.uniqueId)

    fun get(player: OfflinePlayer, path: String): Any? = of(player)?.get(path)

    fun contains(player: OfflinePlayer, path: String): Boolean = of(player)?.contains(path) ?: false

    fun getString(player: OfflinePlayer, path: String, default: String = ""): String =
        of(player)?.getString(path, default) ?: default

    fun getInt(player: OfflinePlayer, path: String, default: Int = 0): Int = of(player)?.getInt(path, default) ?: default

    fun getLong(player: OfflinePlayer, path: String, default: Long = 0L): Long =
        of(player)?.getLong(path, default) ?: default

    fun getDouble(player: OfflinePlayer, path: String, default: Double = 0.0): Double =
        of(player)?.getDouble(path, default) ?: default

    fun getBoolean(player: OfflinePlayer, path: String, default: Boolean = false): Boolean =
        of(player)?.getBoolean(path, default) ?: default

    fun getStringList(player: OfflinePlayer, path: String): List<String> = of(player)?.getStringList(path) ?: emptyList()

    /**
     * Sets a value for a loaded player (`null` removes it). Saved in the background.
     * Logs a warning and does nothing if the player's data is not loaded (e.g. an NPC).
     */
    fun set(player: OfflinePlayer, path: String, value: Any?) {
        val holder = of(player)
        if (holder == null) {
            warnNotLoaded(player)
            return
        }
        holder.set(path, value)
    }

    /** Atomically adds [amount] to a whole number and returns the new value (or null if not loaded). */
    fun increment(player: OfflinePlayer, path: String, amount: Long = 1): Long? {
        val holder = of(player) ?: return null.also { warnNotLoaded(player) }
        return holder.increment(path, amount)
    }

    /** Atomically adds [amount] to a decimal number and returns the new value (or null if not loaded). */
    fun increment(player: OfflinePlayer, path: String, amount: Double): Double? {
        val holder = of(player) ?: return null.also { warnNotLoaded(player) }
        return holder.increment(path, amount)
    }

    /** Saves the player's changes now instead of waiting for the autosave. */
    fun save(player: OfflinePlayer): CompletableFuture<Void?> = service.save(player.uniqueId)

    /** Async read of any player's data. For offline players the copy is read-only (changes are not saved). */
    fun fetch(uuid: UUID): CompletableFuture<PlayerDataHolder> = service.fetch(uuid)

    /** Async change of any player's data, online or offline. [action] runs off the main thread. */
    fun edit(uuid: UUID, action: (PlayerDataHolder) -> Unit): CompletableFuture<Void?> = service.edit(uuid, action)

    private fun warnNotLoaded(player: OfflinePlayer) {
        ALBCore.instance.logger.warning("Player data of ${player.name ?: player.uniqueId} is not loaded; change ignored. Use PlayerData.edit() for offline players.")
    }
}
