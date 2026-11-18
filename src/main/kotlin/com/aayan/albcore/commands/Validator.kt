package com.aayan.albcore.commands

import org.bukkit.command.CommandSender

/** A check on a parsed argument, added with `validate(check, onFail)`. */
class Validator<T>(
    val check: (CommandSender, T) -> Boolean,
    val onFail: (CommandSender) -> Unit,
)
