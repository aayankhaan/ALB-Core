package com.aayan.albcore.commands

import org.bukkit.entity.Player

class ParsedArgs(private val values: Map<String, Any>) {

    fun string(name: String): String = values[name] as String
    fun long(name: String): Long = values[name] as Long
    fun double(name: String): Double = values[name] as Double
    fun player(name: String): Player = values[name] as Player
}