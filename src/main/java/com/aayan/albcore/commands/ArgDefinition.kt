package com.aayan.albcore.commands

import com.aayan.albcore.utils.MessageUtil
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender

class ArgDefinition<T>(
    val name: String,
    val type: ArgType<T>
) {
    var description: String = ""

    val validators = mutableListOf<Validator<T>>()

    private var invalidAction: ((CommandSender, String) -> Unit) = { sender, raw ->
        MessageUtil.send(sender, "&cInvalid ${type.typeName} for '$name': $raw")
    }

    internal var suggestions: () -> List<String> = { emptyList() }

    fun validate(check: (CommandSender, T) -> Boolean, onFail: (CommandSender) -> Unit) {
        validators.add(Validator(check, onFail))
    }

    fun onInvalid(action: (CommandSender, String) -> Unit) {
        invalidAction = action
    }

    internal fun sendInvalid(sender: CommandSender, raw: String) {
        invalidAction(sender, raw)
    }

    fun suggest(provider: () -> List<String>) {
        suggestions = provider
    }

    fun suggestPlayers() {
        suggestions = { Bukkit.getOnlinePlayers().map { it.name } }
    }
}