package com.aayan.albcore.commands

import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player

/**
 * How one command argument is parsed. [parse] returns null for invalid input.
 * Implement your own by extending this class (e.g. an island name or a skill id).
 */
abstract class ArgType<T : Any>(val typeName: String) {

    abstract fun parse(raw: String): T?

    /** Default tab suggestions; an argument's own `suggest { }` replaces them. */
    open fun suggestions(): List<String> = emptyList()

    /** True for [TEXT]: the argument takes every remaining word. */
    open val greedy: Boolean = false

    /** One word. */
    object STRING : ArgType<String>("text") {
        override fun parse(raw: String): String = raw
    }

    /** Everything after this point, spaces included (e.g. a message). Must be the last argument. */
    object TEXT : ArgType<String>("text") {
        override fun parse(raw: String): String = raw
        override val greedy: Boolean = true
    }

    object INT : ArgType<Int>("whole number") {
        override fun parse(raw: String): Int? = raw.toIntOrNull()
    }

    object LONG : ArgType<Long>("number") {
        override fun parse(raw: String): Long? = raw.toLongOrNull()
    }

    object DOUBLE : ArgType<Double>("decimal") {
        override fun parse(raw: String): Double? = raw.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    object BOOLEAN : ArgType<Boolean>("true/false") {
        override fun parse(raw: String): Boolean? = when (raw.lowercase()) {
            "true", "yes", "on" -> true
            "false", "no", "off" -> false
            else -> null
        }

        override fun suggestions(): List<String> = listOf("true", "false")
    }

    /** An online player, by exact name (Bedrock names include the Floodgate prefix). */
    object PLAYER : ArgType<Player>("player") {
        override fun parse(raw: String): Player? = Bukkit.getPlayerExact(raw)
        override fun suggestions(): List<String> = Bukkit.getOnlinePlayers().map { it.name }
    }

    /** A player who has joined before (online or not), by exact name. Never blocks on a web lookup. */
    object OFFLINE_PLAYER : ArgType<OfflinePlayer>("player") {
        override fun parse(raw: String): OfflinePlayer? = Bukkit.getPlayerExact(raw) ?: Bukkit.getOfflinePlayerIfCached(raw)
        override fun suggestions(): List<String> = Bukkit.getOnlinePlayers().map { it.name }
    }
}
