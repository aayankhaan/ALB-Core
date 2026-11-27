package com.aayan.albcore.lang

import com.aayan.albcore.utils.ColorUtil

/** Where a message shows up. Set per message with a tag at the start, e.g. `"[actionbar] &cNo!"`. */
enum class MessageMode {
    CHAT, ACTIONBAR, TITLE, BOSSBAR, OFF;

    /** The tag written in front of the text, e.g. `[actionbar]`. */
    val tag: String get() = "[${name.lowercase()}]"

    fun next(): MessageMode = entries[(ordinal + 1) % entries.size]
}

/** The text rules of [Lang] (no files, no Bukkit), kept apart so they can be tested. */
internal object LangText {

    /** Splits a leading mode tag off [text]: `"[title] Hi"` -> TITLE to `"Hi"`. No tag = CHAT. */
    fun stripMode(text: String): Pair<MessageMode, String> {
        val trimmed = text.trimStart()
        for (mode in MessageMode.entries) {
            if (trimmed.startsWith(mode.tag, ignoreCase = true)) return mode to trimmed.substring(mode.tag.length).trimStart()
        }
        return MessageMode.CHAT to text
    }

    /** [text] with [mode]'s tag in front (no tag for chat). */
    fun withMode(mode: MessageMode, text: String): String = if (mode == MessageMode.CHAT) text else "${mode.tag} $text"

    /**
     * Fills {prefix} and [placeholders]. Values are escaped, so player names can't inject MiniMessage tags.
     * "{{name}}" stays as the literal text "{name}" (for help texts).
     */
    fun fill(text: String, prefix: String, placeholders: Array<out Pair<String, Any>>): String {
        var result = text.replace("{{", "\u0000").replace("}}", "\u0001").replace("{prefix}", prefix)
        for ((name, value) in placeholders) {
            result = result.replace("{$name}", ColorUtil.escape(value.toString()))
        }
        return result.replace("\u0000", "{").replace("\u0001", "}")
    }

    /**
     * Fills every line. A line that is only a list placeholder (e.g. "{requirements}") becomes one line
     * per value (an empty list gives no line). List values are formatted lines from code and are NOT
     * escaped: escape player-written text in them with `ColorUtil.escape` yourself.
     */
    fun fillLines(lines: List<String>, prefix: String, placeholders: Array<out Pair<String, Any>>): List<String> =
        lines.flatMap { line ->
            val text = stripMode(line).second
            val multi = placeholders.firstOrNull { (k, v) -> text.trim() == "{$k}" && v is List<*> }
            if (multi != null) (multi.second as List<*>).map { it.toString() }
            else listOf(fill(text, prefix, placeholders))
        }
}
