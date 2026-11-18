package com.aayan.albcore.commands

import com.aayan.albcore.utils.MessageUtil
import org.bukkit.command.CommandSender

/** One argument declared with `arg(name, type) { ... }` inside a command. */
class ArgDefinition<T : Any>(
    val name: String,
    val type: ArgType<T>,
) {
    var description: String = ""

    /** Optional arguments may be left out. They must come after all required ones. */
    var optional: Boolean = false

    internal val validators = mutableListOf<Validator<T>>()

    private var invalidAction: (CommandSender, String) -> Unit = { sender, raw ->
        MessageUtil.send(sender, "&cInvalid ${type.typeName} for '$name': $raw")
    }

    internal var suggestions: () -> List<String> = { type.suggestions() }

    /** Adds a check that runs after parsing; [onFail] tells the sender what was wrong. */
    fun validate(check: (CommandSender, T) -> Boolean, onFail: (CommandSender) -> Unit) {
        validators += Validator(check, onFail)
    }

    /** Replaces the default "Invalid ..." message for input that cannot be parsed. */
    fun onInvalid(action: (CommandSender, String) -> Unit) {
        invalidAction = action
    }

    /** Tab suggestions for this argument (filtered by what the player typed). */
    fun suggest(provider: () -> List<String>) {
        suggestions = provider
    }

    fun suggestPlayers() {
        suggestions = { ArgType.PLAYER.suggestions() }
    }

    internal fun sendInvalid(sender: CommandSender, raw: String) = invalidAction(sender, raw)

    internal val usage: String get() = if (optional) "[$name]" else "<$name>"
}
