package com.aayan.albcore.commands

import com.aayan.albcore.utils.MessageUtil
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/** Registers commands with Paper's command system. No plugin.yml entry is needed. */
object CommandUtil {

    /**
     * Registers `/<name>` for [plugin]. Call it in `onEnable`.
     *
     * ```
     * CommandUtil.registerCommand(this, "level") {
     *     description = "Show a player's level"
     *     arg("target", ArgType.PLAYER) { optional = true }
     *     action { sender, args ->
     *         val target = args.playerOrNull("target") ?: sender as? Player ?: return@action
     *         MessageUtil.send(sender, "&a${target.name}: ${PlayerData.getInt(target, "level", 1)}")
     *     }
     *     subcommand("set") {
     *         permission = "albl.admin"
     *         arg("target", ArgType.OFFLINE_PLAYER)
     *         arg("level", ArgType.INT) { validate({ _, v -> v in 1..100 }) { MessageUtil.send(it, "&c1-100 only") } }
     *         action { sender, args -> setLevel(args.offlinePlayer("target"), args.int("level")) }
     *     }
     * }
     * ```
     */
    fun registerCommand(plugin: Plugin, name: String, block: CommandBuilder.() -> Unit) {
        val builder = CommandBuilder().apply(block)

        val command = object : BasicCommand {
            override fun execute(source: CommandSourceStack, args: Array<String>) {
                val sender = source.sender
                if (builder.playerOnly && sender !is Player) {
                    MessageUtil.send(sender, builder.playerOnlyMessage)
                    return
                }
                if (!builder.canUse(sender)) {
                    MessageUtil.send(sender, builder.noPermissionMessage)
                    return
                }
                builder.run(sender, args)
            }

            override fun suggest(source: CommandSourceStack, args: Array<String>): Collection<String> {
                val typed = args.lastOrNull() ?: ""
                return builder.tabComplete(source.sender, args).filter { it.startsWith(typed, ignoreCase = true) }
            }

            override fun permission(): String? = builder.permission
        }

        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(name, builder.description.takeIf { it.isNotBlank() }, builder.aliases, command)
        }
    }
}
