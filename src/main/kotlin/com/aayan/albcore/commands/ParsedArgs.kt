package com.aayan.albcore.commands

import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player

/** Parsed argument values, by argument name. Getters throw if a required value is missing; use `...OrNull` for optional args. */
class ParsedArgs(private val values: Map<String, Any>) {

    fun has(name: String): Boolean = name in values

    fun string(name: String): String = get(name)
    fun int(name: String): Int = get(name)
    fun long(name: String): Long = get(name)
    fun double(name: String): Double = get(name)
    fun boolean(name: String): Boolean = get(name)
    fun player(name: String): Player = get(name)
    fun offlinePlayer(name: String): OfflinePlayer = get(name)

    fun stringOrNull(name: String): String? = values[name] as String?
    fun intOrNull(name: String): Int? = values[name] as Int?
    fun longOrNull(name: String): Long? = values[name] as Long?
    fun doubleOrNull(name: String): Double? = values[name] as Double?
    fun booleanOrNull(name: String): Boolean? = values[name] as Boolean?
    fun playerOrNull(name: String): Player? = values[name] as Player?
    fun offlinePlayerOrNull(name: String): OfflinePlayer? = values[name] as OfflinePlayer?

    /** Any value by name, e.g. for custom [ArgType]s: `args.get<Island>("island")`. */
    @Suppress("UNCHECKED_CAST")
    fun <T> get(name: String): T =
        (values[name] ?: throw IllegalStateException("Argument '$name' was not given")) as T
}
