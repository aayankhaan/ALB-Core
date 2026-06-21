package com.aayan.albcore.hooks

import com.aayan.albcore.ALBCore
import me.clip.placeholderapi.PlaceholderAPI
import org.bukkit.Bukkit
import org.bukkit.entity.Player

object PAPIHook {
    private var loaded = false

    fun setup(plugin: ALBCore): Boolean {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            plugin.logger.warning("PlaceHolderAPI not found! PAPI features disabled.")
            return false
        }
        loaded = true
        return true
    }

    private fun isLoaded(): Boolean = loaded

    fun parse(player: Player, text: String): String {
        if (!isLoaded()) return text
        return PlaceholderAPI.setPlaceholders(player, text)
    }
}