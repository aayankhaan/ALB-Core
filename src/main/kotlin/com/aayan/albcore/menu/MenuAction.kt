package com.aayan.albcore.menu

/**
 * One thing a menu item does when clicked, written "[type] value" in the menu file. They run in order.
 *   [close]                        close the menu
 *   [back]                         the menu this one was opened from (or close when there is none)
 *   [open] <menu>                  another menu of the same plugin, or "<plugin>:<menu>" of another one
 *   [next-page] / [previous-page]  page of the list area (the item hides when there is no such page)
 *   [refresh]                      redraw the menu
 *   [function] <id>                code of the plugin (see [Menus.function] and [MenuContext.functions])
 *   [player] <command>             run a command as the player ({player} = their name)
 *   [console] <command>            run a command from the console
 *   [message] <text>               send a chat message ({prefix} and PlaceholderAPI work)
 *   [sound] <sound> [volume] [pitch]
 * Without a [sound] action, a click plays the theme click sound.
 */
data class MenuAction(val type: String, val arg: String) {

    override fun toString(): String = if (arg.isBlank()) "[$type]" else "[$type] $arg"

    companion object {
        val TYPES = listOf("close", "back", "open", "next-page", "previous-page", "refresh", "function", "player", "console", "message", "sound")

        private val PATTERN = Regex("^\\s*\\[([a-z-]+)]\\s*(.*)$")

        /** Parses "[type] value"; null when the text is not an action or the type is unknown. */
        fun parse(raw: String): MenuAction? {
            val m = PATTERN.find(raw) ?: return null
            val type = m.groupValues[1].lowercase()
            if (type !in TYPES) return null
            return MenuAction(type, m.groupValues[2].trim())
        }
    }
}
