package com.aayan.albcore.commands

import com.aayan.albcore.utils.MessageUtil
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

object CommandUtil {

    fun registerCommand(
        plugin: Plugin,
        name: String,
        block: CommandBuilder.() -> Unit
    ) {
        val builder = CommandBuilder()
        builder.block()

        val command = object : BasicCommand {
            override fun execute(source: CommandSourceStack, args: Array<String>) {
                val sender = source.sender

                if (builder.playerOnly == true && sender !is Player) {
                    MessageUtil.send(sender, builder.playerOnlyMessage)
                    return
                }

                if (builder.permission != null && !sender.hasPermission(builder.permission!!)) {
                    MessageUtil.send(sender, builder.noPermissionMessage)
                    return
                }

                builder.run(sender, args)
            }

            override fun suggest(source: CommandSourceStack, args: Array<String>): Collection<String> {
                val sender = source.sender
                val currentInput = args.lastOrNull() ?: ""

                return builder.tabComplete(sender, args)
                    .filter { it.startsWith(currentInput, ignoreCase = true) }
            }

            override fun permission(): String? {
                return builder.permission
            }
        }

        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(
                name,
                builder.description.takeIf { it.isNotBlank() },
                builder.aliases,
                command
            )
        }
    }
}