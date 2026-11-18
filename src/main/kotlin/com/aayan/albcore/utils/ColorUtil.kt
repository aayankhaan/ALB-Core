package com.aayan.albcore.utils

import com.aayan.albcore.hooks.PAPIHook
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.OfflinePlayer

/**
 * Turns config text into Adventure components. Accepts MiniMessage (`<red>`, `<gradient:...>`) and
 * legacy codes: `&c`, `&l`, `&#ff8800` and `&x&f&f&8&8&0&0`.
 *
 * Security: never put raw player input (chat, sign text, item names) through [parse]; MiniMessage tags
 * in it would be executed (e.g. click events). Wrap it with [escape] first.
 */
object ColorUtil {

    private val miniMessage = MiniMessage.miniMessage()

    private val hexBungee = Regex("&x((?:&[A-Fa-f0-9]){6})", RegexOption.IGNORE_CASE)
    private val hexAmpersand = Regex("&#([A-Fa-f0-9]{6})")
    private val legacyCode = Regex("&([0-9a-fk-or])", RegexOption.IGNORE_CASE)

    private val legacyTags = mapOf(
        '0' to "<black>", '1' to "<dark_blue>", '2' to "<dark_green>", '3' to "<dark_aqua>",
        '4' to "<dark_red>", '5' to "<dark_purple>", '6' to "<gold>", '7' to "<gray>",
        '8' to "<dark_gray>", '9' to "<blue>", 'a' to "<green>", 'b' to "<aqua>",
        'c' to "<red>", 'd' to "<light_purple>", 'e' to "<yellow>", 'f' to "<white>",
        'k' to "<obfuscated>", 'l' to "<bold>", 'm' to "<strikethrough>", 'n' to "<underlined>",
        'o' to "<italic>", 'r' to "<reset>",
    )

    /** Parses [text] (MiniMessage + legacy `&` codes). */
    fun parse(text: String): Component = miniMessage.deserialize(legacyToMiniMessage(text))

    /** Like [parse], but replaces PlaceholderAPI placeholders for [player] first. */
    fun parseWithPAPI(player: OfflinePlayer?, text: String): Component = parse(PAPIHook.parse(player, text))

    /** The text without any formatting, e.g. `"&cHello <bold>there"` gives `"Hello there"`. */
    fun toPlainText(text: String): String = PlainTextComponentSerializer.plainText().serialize(parse(text))

    /** Converts legacy `&` codes to MiniMessage tags and leaves everything else unchanged. */
    fun legacyToMiniMessage(text: String): String {
        if (text.indexOf('&') < 0) return text
        var result = hexBungee.replace(text) { match -> "<#${match.groupValues[1].replace("&", "")}>" }
        result = hexAmpersand.replace(result) { match -> "<#${match.groupValues[1]}>" }
        result = legacyCode.replace(result) { match -> legacyTags.getValue(match.groupValues[1].lowercase()[0]) }
        return result
    }

    /** Escapes MiniMessage tags so [text] is shown literally. Use on any player-written text. */
    fun escape(text: String): String = miniMessage.escapeTags(text)
}
