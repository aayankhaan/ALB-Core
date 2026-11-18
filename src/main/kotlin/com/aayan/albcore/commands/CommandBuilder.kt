package com.aayan.albcore.commands

import com.aayan.albcore.utils.MessageUtil
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Describes a command: its arguments, subcommands, checks and action. Built by
 * [CommandUtil.registerCommand] and by `subcommand(...)`.
 *
 * Arguments are parsed first, then the next word picks a subcommand. A command with subcommands may
 * also have its own action, which runs when no subcommand word is given (`/island` vs `/island invite`).
 */
class CommandBuilder {

    var description: String = ""
    var aliases: MutableList<String> = mutableListOf()

    /** Required permission, or null for everyone. Also hides the command/subcommand from tab completion. */
    var permission: String? = null

    /** If true, the console and command blocks get [playerOnlyMessage] instead. */
    var playerOnly: Boolean = false

    var noPermissionMessage: String = "&cYou don't have permission to use this command."
    var playerOnlyMessage: String = "&cThis command can only be used by players."

    private val args = mutableListOf<ArgDefinition<*>>()
    private var action: ((CommandSender, ParsedArgs) -> Unit)? = null
    private val subcommands = LinkedHashMap<String, CommandBuilder>()
    private val subcommandNames = mutableListOf<String>()

    private var missingArgsAction: (CommandSender, String) -> Unit = { sender, usage ->
        MessageUtil.send(sender, "&cMissing arguments. Usage: $usage")
    }

    private var unknownSubcommandAction: (CommandSender, List<String>) -> Unit = { sender, options ->
        MessageUtil.send(sender, "&cUsage: ${options.joinToString("/")}")
    }

    /** Declares the next argument. Required arguments first, then optional ones, then at most one [ArgType.TEXT]. */
    fun <T : Any> arg(name: String, type: ArgType<T>, block: ArgDefinition<T>.() -> Unit = {}) {
        val definition = ArgDefinition(name, type).apply(block)
        require(args.none { it.name == name }) { "Argument '$name' is declared twice" }
        check(args.none { it.type.greedy }) { "No argument can follow a TEXT argument ('$name')" }
        check(definition.optional || args.none { it.optional }) {
            "Required argument '$name' cannot follow an optional argument"
        }
        args += definition
    }

    /** What the command does. Runs only after every argument parsed and validated. */
    fun action(block: (CommandSender, ParsedArgs) -> Unit) {
        action = block
    }

    fun subcommand(name: String, block: CommandBuilder.() -> Unit) {
        val nested = CommandBuilder().apply(block)
        subcommandNames += name.lowercase()
        subcommands[name.lowercase()] = nested
        nested.aliases.forEach { alias -> subcommands[alias.lowercase()] = nested }
    }

    fun onMissingArgs(action: (CommandSender, String) -> Unit) {
        missingArgsAction = action
    }

    fun onUnknownSubcommand(action: (CommandSender, List<String>) -> Unit) {
        unknownSubcommandAction = action
    }

    /** Runs the command for [sender]. Permission and player-only checks of this builder are done by the caller. */
    fun run(sender: CommandSender, rawArgs: Array<String>) {
        execute(sender, rawArgs.filter { it.isNotEmpty() }, mutableMapOf())
    }

    /** Tab suggestions for the last word in [currentArgs] (not yet filtered by what was typed). */
    fun tabComplete(sender: CommandSender, currentArgs: Array<String>): List<String> =
        complete(sender, if (currentArgs.isEmpty()) listOf("") else currentArgs.toList())

    internal fun canUse(sender: CommandSender): Boolean = permission?.let(sender::hasPermission) ?: true

    private fun execute(sender: CommandSender, words: List<String>, parsed: MutableMap<String, Any>) {
        var index = 0
        for ((position, definition) in args.withIndex()) {
            if (index >= words.size) {
                if (definition.optional) break
                missingArgsAction(sender, args.drop(position).joinToString(" ") { it.usage })
                return
            }
            val raw = if (definition.type.greedy) words.subList(index, words.size).joinToString(" ") else words[index]
            if (!parseAndValidate(definition, sender, raw, parsed)) return
            index = if (definition.type.greedy) words.size else index + 1
        }

        val next = words.getOrNull(index)
        if (next != null) {
            val subcommand = subcommands[next.lowercase()]
            if (subcommand != null) {
                if (subcommand.playerOnly && sender !is Player) {
                    MessageUtil.send(sender, subcommand.playerOnlyMessage)
                    return
                }
                if (!subcommand.canUse(sender)) {
                    MessageUtil.send(sender, subcommand.noPermissionMessage)
                    return
                }
                subcommand.execute(sender, words.drop(index + 1), parsed)
                return
            }
        }

        if (subcommands.isNotEmpty() && (next != null || action == null)) {
            unknownSubcommandAction(sender, authorizedSubcommands(sender))
            return
        }
        action?.invoke(sender, ParsedArgs(parsed))
    }

    private fun complete(sender: CommandSender, words: List<String>): List<String> {
        val last = words.lastIndex
        var index = 0
        for (definition in args) {
            if (index == last) return definition.suggestions()
            if (definition.type.greedy) return emptyList()
            index++
        }
        if (index == last) return authorizedSubcommands(sender)
        val subcommand = subcommands[words[index].lowercase()] ?: return emptyList()
        if (!subcommand.canUse(sender)) return emptyList()
        return subcommand.complete(sender, words.drop(index + 1))
    }

    private fun authorizedSubcommands(sender: CommandSender): List<String> =
        subcommandNames.filter { subcommands.getValue(it).canUse(sender) }

    private fun <T : Any> parseAndValidate(
        definition: ArgDefinition<T>,
        sender: CommandSender,
        raw: String,
        parsed: MutableMap<String, Any>,
    ): Boolean {
        val value = definition.type.parse(raw)
        if (value == null) {
            definition.sendInvalid(sender, raw)
            return false
        }
        for (validator in definition.validators) {
            if (!validator.check(sender, value)) {
                validator.onFail(sender)
                return false
            }
        }
        parsed[definition.name] = value
        return true
    }
}
