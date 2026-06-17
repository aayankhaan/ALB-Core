package com.aayan.albcore.commands

import org.bukkit.Bukkit
import org.bukkit.entity.Player

sealed class ArgType<T>(val typeName: String) {

    abstract fun parse(raw: String): T?

    object STRING : ArgType<String>("text") {
        override fun parse(raw: String): String = raw
    }

    object LONG : ArgType<Long>("number") {
        override fun parse(raw: String): Long? = raw.toLongOrNull()
    }

    object DOUBLE : ArgType<Double>("decimal") {
        override fun parse(raw: String): Double? = raw.toDoubleOrNull()
    }

    object PLAYER : ArgType<Player>("player") {
        override fun parse(raw: String): Player? = Bukkit.getPlayer(raw)
    }
}