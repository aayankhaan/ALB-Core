package com.aayan.albcore.hooks

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.entity.Player

class PAPIExpansion : PlaceholderExpansion() {

    companion object {
        private val handlers = mutableMapOf<String, (Player) -> String>()

        fun registerPAPI(placeholder: String, result: (Player) -> String) {
            handlers[placeholder] = result
        }
    }

    override fun getIdentifier(): String {
        return "alb"
    }

    override fun getAuthor(): String {
        return "Aayan"
    }

    override fun getVersion(): String {
        return "1.0"
    }

    override fun onPlaceholderRequest(player: Player, params: String): String? {
        return handlers[params.lowercase()]?.invoke(player)
    }
}