package com.aayan.albcore.data

import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID

/**
 * One player's data: a YAML document kept in memory, addressed by dotted paths such as `"stats.kills"`.
 *
 * Thread-safe: every method locks the document, so placeholder requests from async threads are fine.
 * Values can be anything YAML can hold (numbers, strings, lists, maps, ItemStacks...).
 * Changes are saved by ALBCore's autosave, on quit and on shutdown, never on the calling thread.
 */
class PlayerDataHolder internal constructor(
    val uuid: UUID,
    private val yaml: YamlConfiguration,
) {
    private val lock = Any()

    @Volatile
    private var dirty = false

    /** When this holder was created; used to clean up data of players who never finished joining. */
    internal val loadedAt: Long = System.currentTimeMillis()

    /** True if there are changes that have not been saved yet. */
    val isDirty: Boolean get() = dirty

    fun get(path: String): Any? = synchronized(lock) { yaml.get(path) }

    fun contains(path: String): Boolean = synchronized(lock) { yaml.contains(path) }

    fun getString(path: String, default: String = ""): String =
        synchronized(lock) { yaml.getString(path, default) ?: default }

    fun getInt(path: String, default: Int = 0): Int = synchronized(lock) { yaml.getInt(path, default) }

    fun getLong(path: String, default: Long = 0L): Long = synchronized(lock) { yaml.getLong(path, default) }

    fun getDouble(path: String, default: Double = 0.0): Double = synchronized(lock) { yaml.getDouble(path, default) }

    fun getBoolean(path: String, default: Boolean = false): Boolean = synchronized(lock) { yaml.getBoolean(path, default) }

    fun getStringList(path: String): List<String> = synchronized(lock) { yaml.getStringList(path) }

    /** Keys directly under [path] (or at the root if [path] is empty). With [deep], all nested keys. */
    fun keys(path: String = "", deep: Boolean = false): Set<String> = synchronized(lock) {
        val section = if (path.isEmpty()) yaml else yaml.getConfigurationSection(path)
        section?.getKeys(deep) ?: emptySet()
    }

    /** Sets [path] to [value]; `null` removes it. */
    fun set(path: String, value: Any?) {
        synchronized(lock) {
            yaml.set(path, value)
            dirty = true
        }
    }

    fun remove(path: String) = set(path, null)

    /** Adds [amount] to the number at [path] (0 if missing) in one atomic step and returns the new value. */
    fun increment(path: String, amount: Long = 1): Long = synchronized(lock) {
        val value = yaml.getLong(path, 0L) + amount
        yaml.set(path, value)
        dirty = true
        value
    }

    /** Adds [amount] to the decimal at [path] (0 if missing) in one atomic step and returns the new value. */
    fun increment(path: String, amount: Double): Double = synchronized(lock) {
        val value = yaml.getDouble(path, 0.0) + amount
        yaml.set(path, value)
        dirty = true
        value
    }

    /** The whole document as YAML text. */
    fun toYamlString(): String = synchronized(lock) { yaml.saveToString() }

    /** Serialises the document and clears the dirty flag, or returns null if nothing changed. */
    internal fun snapshotIfDirty(): String? = synchronized(lock) {
        if (!dirty) return null
        dirty = false
        yaml.saveToString()
    }

    internal fun markDirty() {
        dirty = true
    }

    companion object {
        /** Parses stored YAML text (null/blank means a new, empty document). */
        internal fun parse(uuid: UUID, text: String?): PlayerDataHolder {
            val yaml = YamlConfiguration()
            if (!text.isNullOrBlank()) yaml.loadFromString(text)
            return PlayerDataHolder(uuid, yaml)
        }
    }
}
