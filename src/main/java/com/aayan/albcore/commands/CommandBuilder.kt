package com.aayan.albcore.commands

import com.aayan.albcore.utils.MessageUtil
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class CommandBuilder {

    var description: String = ""
    var aliases = mutableListOf<String>()
    var permission: String? = null
    var playerOnly: Boolean? = null
    var noPermissionMessage: String = "&cYou don't have permission to use this command."
    var playerOnlyMessage: String = "&cThis command can only be used by players."

    private val args = mutableListOf<ArgDefinition<*>>()
    private var action: ((CommandSender, ParsedArgs) -> Unit)? = null
    private val subcommands = mutableMapOf<String, CommandBuilder>()

    private var missingArgsAction: ((CommandSender, String) -> Unit) = { sender, usage ->
        MessageUtil.send(sender, "&cMissing arguments. Usage: $usage")
    }

    private var unknownSubcommandAction: ((CommandSender, List<String>) -> Unit) = { sender, options ->
        MessageUtil.send(sender, "&cUsage: ${options.joinToString("/")}")
    }

    fun <T> arg(name: String, type: ArgType<T>, block: ArgDefinition<T>.() -> Unit = {}) {
        val definition = ArgDefinition(name, type)
        definition.block()
        args.add(definition)
    }

    fun action(block: (CommandSender, ParsedArgs) -> Unit) {
        action = block
    }

    fun subcommand(name: String, block: CommandBuilder.() -> Unit) {
        val nested = CommandBuilder()
        nested.block()


        subcommands[name.lowercase()] = nested

        nested.aliases.forEach { alias ->
            subcommands[alias.lowercase()] = nested
        }
    }

    fun onMissingArgs(action: (CommandSender, String) -> Unit) {
        missingArgsAction = action
    }

    fun onUnknownSubcommand(action: (CommandSender, List<String>) -> Unit) {
        unknownSubcommandAction = action
    }

    fun run(sender: CommandSender, rawArgs: Array<String>) {
        process(sender, rawArgs, argIndex = 0, parsedValues = mutableMapOf())
    }

    private fun process(
        sender: CommandSender,
        rawArgs: Array<String>,
        argIndex: Int,
        parsedValues: MutableMap<String, Any>
    ) {
        val currentWord = rawArgs.getOrNull(0)

        val nextDefinition = args.getOrNull(argIndex)
        if (nextDefinition != null) {
            if (currentWord == null) {
                val usage = args.drop(argIndex).joinToString(" ") { "<${it.name}>" }
                missingArgsAction(sender, usage)
                return
            }

            val success = parseAndValidate(nextDefinition, sender, currentWord, parsedValues)
            if (!success) return

            process(sender, rawArgs.drop(1).toTypedArray(), argIndex + 1, parsedValues)
            return
        }

        if (currentWord != null) {
            val matched = subcommands[currentWord.lowercase()]
            if (matched != null) {

                if (matched.playerOnly == true && sender !is Player) {
                    MessageUtil.send(sender, matched.playerOnlyMessage)
                    return
                }

                if (matched.permission != null && !sender.hasPermission(matched.permission!!)) {
                    MessageUtil.send(sender, matched.noPermissionMessage)
                    return
                }

                matched.process(sender, rawArgs.drop(1).toTypedArray(), argIndex = 0, parsedValues = parsedValues)
                return
            }
        }

        if (subcommands.isNotEmpty() && currentWord != null) {
            unknownSubcommandAction(sender, getAuthorizedSubcommands(sender))
            return
        }

        if (subcommands.isNotEmpty() && currentWord == null) {
            unknownSubcommandAction(sender, getAuthorizedSubcommands(sender))
            return
        }

        action?.invoke(sender, ParsedArgs(parsedValues))
    }

    fun tabComplete(sender: CommandSender, currentArgs: Array<String>): List<String> {
        return tabCompleteWalk(sender, currentArgs, argIndex = 0)
    }

    private fun tabCompleteWalk(sender: CommandSender, rawArgs: Array<String>, argIndex: Int): List<String> {
        val currentWord = rawArgs.getOrNull(0)
        val isLastWord = rawArgs.size <= 1

        val pendingArg = args.getOrNull(argIndex)
        if (pendingArg != null) {
            if (isLastWord) {
                return pendingArg.suggestions()
            } else {
                return tabCompleteWalk(sender, rawArgs.drop(1).toTypedArray(), argIndex + 1)
            }
        }

        if (isLastWord) {
            return getAuthorizedSubcommands(sender)
        }

        if (currentWord != null) {
            val matched = subcommands[currentWord.lowercase()]
            if (matched != null) {
                if (matched.permission != null && !sender.hasPermission(matched.permission!!)) {
                    return emptyList()
                }
                return matched.tabCompleteWalk(sender, rawArgs.drop(1).toTypedArray(), argIndex = 0)
            }
        }

        return emptyList()
    }

    private fun getAuthorizedSubcommands(sender: CommandSender): List<String> {
        return subcommands.entries
            .filter { (_, subBuilder) ->
                subBuilder.permission == null || sender.hasPermission(subBuilder.permission!!)
            }
            .map { it.key }
    }

    private fun <T> parseAndValidate(
        definition: ArgDefinition<T>,
        sender: CommandSender,
        raw: String,
        parsedValues: MutableMap<String, Any>
    ): Boolean {
        val parsed = definition.type.parse(raw)

        if (parsed == null) {
            definition.sendInvalid(sender, raw)
            return false
        }

        for (validator in definition.validators) {
            val passed = validator.check(sender, parsed)
            if (!passed) {
                validator.onFail(sender)
                return false
            }
        }

        parsedValues[definition.name] = parsed
        return true
    }
}