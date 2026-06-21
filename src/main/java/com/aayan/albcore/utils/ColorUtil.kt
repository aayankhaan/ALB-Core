package com.aayan.albcore.utils

import com.aayan.albcore.hooks.PAPIHook
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player

object ColorUtil {

    private val miniMessage = MiniMessage.miniMessage()

    private val legacySerializer = LegacyComponentSerializer.builder()
        .character('&')
        .hexColors()
        .build()

    fun parse(text: String): Component {
        val converted = convertLegacyToMiniMessage(text)
        return miniMessage.deserialize(converted)
    }

    fun parseWithPAPI(player: Player, text: String): Component {
        val withPapi  = PAPIHook.parse(player, text)
        val converted = convertLegacyToMiniMessage(withPapi)
        return miniMessage.deserialize(converted)
    }

    fun parseToString(text: String): String {
        return PlainTextComponentSerializer
            .plainText()
            .serialize(parse(text))
    }

    private fun convertLegacyToMiniMessage(text: String): String {
        var result = text

        result = result.replace(Regex("&#([A-Fa-f0-9]{6})"), "<#$1>")

        val colorMap = mapOf(
            "&0" to "<black>",
            "&1" to "<dark_blue>",
            "&2" to "<dark_green>",
            "&3" to "<dark_aqua>",
            "&4" to "<dark_red>",
            "&5" to "<dark_purple>",
            "&6" to "<gold>",
            "&7" to "<gray>",
            "&8" to "<dark_gray>",
            "&9" to "<blue>",
            "&a" to "<green>",
            "&b" to "<aqua>",
            "&c" to "<red>",
            "&d" to "<light_purple>",
            "&e" to "<yellow>",
            "&f" to "<white>",
            "&l" to "<bold>",
            "&o" to "<italic>",
            "&n" to "<underlined>",
            "&m" to "<strikethrough>",
            "&k" to "<obfuscated>",
            "&r" to "<reset>"
        )

        colorMap.forEach { (legacy, mini) ->
            result = result.replace(legacy, mini, ignoreCase = true)
        }

        return result
    }
}