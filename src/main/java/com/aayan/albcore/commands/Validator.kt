package com.aayan.albcore.commands

import org.bukkit.command.CommandSender

class Validator<T>(
    val check: (CommandSender, T) -> Boolean,
    val onFail: (CommandSender) -> Unit
)