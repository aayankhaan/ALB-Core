package com.aayan.albcore

import com.aayan.albcore.commands.ArgType
import com.aayan.albcore.commands.CommandUtil
import com.aayan.albcore.gui.GuiBuilder
import com.aayan.albcore.gui.GuiListener
import com.aayan.albcore.utils.ColorUtil
import com.aayan.albcore.utils.MessageUtil
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

class ALBCore : JavaPlugin() {

    override fun onEnable() {
        server.pluginManager.registerEvents(GuiListener(), this)

        registerTestGui()
        registerEcoCommand()
        registerPunishCommand()

        CommandUtil.registerCommand(this, "test") {
            description = "test"
            playerOnly = true
            playerOnlyMessage = "&comly players can type this msg."

            action { sender, _ ->
                MessageUtil.send(sender, "&cyou typed the command")
            }
        }

    }

    override fun onDisable() {
        // Plugin shutdown logic
    }

    // ============================================================
    // /testgui - Confirms GUI system still works
    // ============================================================
    private fun registerTestGui() {
        CommandUtil.registerCommand(this, "testgui") {
            description = "Open the test GUI menu"
            playerOnly = true
            playerOnlyMessage = "&cOnly players can open this menu."

            action { sender, _ ->
                val player = sender as Player

                GuiBuilder("&8Test Menu", 3)
                    .setItem(13, ItemStack(Material.DIAMOND)) { p ->
                        MessageUtil.send(p, "&bYou clicked the diamond!")
                    }
                    .setItem(11, ItemStack(Material.GOLD_INGOT))
                    .open(player)
            }
        }
    }

    // ============================================================
    // /eco <add/remove/check> <player> [amount]
    // SUBCOMMAND-FIRST pattern
    // ============================================================
    private fun registerEcoCommand() {
        CommandUtil.registerCommand(this, "eco") {
            description = "Manage player economy balances."
            aliases = mutableListOf("economy", "money")
            permission = "core.eco"
            noPermissionMessage = "&cYou don't have permission to manage economy."
            playerOnly = false

            onUnknownSubcommand { sender, options ->
                MessageUtil.send(sender, "&cUsage: /eco <${options.joinToString("|")}> <player> [amount]")
            }

            subcommand("add") {
                description = "Add coins to a player's balance."
                aliases = mutableListOf("give")
                permission = "core.eco.add"

                onMissingArgs { sender, usage ->
                    MessageUtil.send(sender, "&cUsage: /eco add $usage")
                }

                arg("target", ArgType.PLAYER) {
                    description = "The player you want to give coins to"
                    suggestPlayers()
                    onInvalid { sender, raw ->
                        MessageUtil.send(sender, "&cPlayer '$raw' not found.")
                    }
                }

                arg("amount", ArgType.LONG) {
                    description = "The amount of coins to add"
                    suggest { listOf("100", "1000", "10000") }
                    onInvalid { sender, raw ->
                        MessageUtil.send(sender, "&c'$raw' is not a valid amount.")
                    }
                    validate({ _, amount -> amount > 0 }) {
                        MessageUtil.send(it, "&cAmount must be greater than 0.")
                    }
                }

                action { sender, args ->
                    val target = args.player("target")
                    val amount = args.long("amount")
                    MessageUtil.send(sender, "&aAdded &f$amount &acoins to &f${target.name}&a's balance.")
                    MessageUtil.send(target, "&aYou received &f$amount &acoins!")
                }
            }

            subcommand("remove") {
                description = "Remove coins from a player's balance."
                aliases = mutableListOf("take")
                permission = "core.eco.remove"

                onMissingArgs { sender, usage ->
                    MessageUtil.send(sender, "&cUsage: /eco remove $usage")
                }

                arg("target", ArgType.PLAYER) {
                    suggestPlayers()
                    onInvalid { sender, raw ->
                        MessageUtil.send(sender, "&cPlayer '$raw' not found.")
                    }
                }

                arg("amount", ArgType.LONG) {
                    suggest { listOf("100", "1000", "10000") }
                    validate({ _, amount -> amount > 0 }) {
                        MessageUtil.send(it, "&cAmount must be greater than 0.")
                    }
                }

                action { sender, args ->
                    val target = args.player("target")
                    val amount = args.long("amount")
                    MessageUtil.send(sender, "&cRemoved &f$amount &ccoins from &f${target.name}&c's balance.")
                }
            }

            subcommand("check") {
                description = "Check a player's balance."
                aliases = mutableListOf("bal", "balance")
                permission = "core.eco.check"

                onMissingArgs { sender, usage ->
                    MessageUtil.send(sender, "&cUsage: /eco check $usage")
                }

                arg("target", ArgType.PLAYER) {
                    suggestPlayers()
                    onInvalid { sender, raw ->
                        MessageUtil.send(sender, "&cPlayer '$raw' not found.")
                    }
                }

                action { sender, args ->
                    val target = args.player("target")
                    MessageUtil.send(sender, "&7${target.name}'s balance: &f1,000 coins")
                }
            }
        }
    }

    // ============================================================
    // /punish <player> <mute/ban/kick> [time - only for mute/ban]
    // PLAYER-FIRST pattern - arg() declared BEFORE subcommand()
    // ============================================================
    private fun registerPunishCommand() {
        CommandUtil.registerCommand(this, "punish") {
            description = "Punish a player on the server."
            aliases = mutableListOf("mod", "staff")
            permission = "core.punish"
            noPermissionMessage = "&cYou don't have permission to punish players."

            arg("target", ArgType.PLAYER) {
                description = "The player to punish"
                suggestPlayers()
                onInvalid { sender, raw ->
                    MessageUtil.send(sender, "&cPlayer '$raw' not found.")
                }
            }

            onMissingArgs { sender, _ ->
                MessageUtil.send(sender, "&cUsage: /punish <player> <mute|ban|kick> [time]")
            }

            onUnknownSubcommand { sender, options ->
                MessageUtil.send(sender, "&cUsage: /punish <player> <${options.joinToString("|")}> [time]")
            }

            subcommand("mute") {
                description = "Mute a player in chat."
                permission = "core.punish.mute"

                onMissingArgs { sender, usage ->
                    MessageUtil.send(sender, "&cUsage: /punish <player> mute $usage")
                }

                arg("time", ArgType.STRING) {
                    description = "Duration of the mute"
                    suggest { listOf("10m", "1h", "1d", "permanent") }
                    onInvalid { sender, raw ->
                        MessageUtil.send(sender, "&cInvalid time format: $raw")
                    }
                }

                action { sender, args ->
                    val target = args.player("target")
                    val time = args.string("time")
                    MessageUtil.send(sender, "&aMuted &f${target.name} &afor &f$time&a.")
                    MessageUtil.send(target, "&cYou have been muted for $time.")
                }
            }

            subcommand("ban") {
                description = "Ban a player from the server."
                permission = "core.punish.ban"

                onMissingArgs { sender, usage ->
                    MessageUtil.send(sender, "&cUsage: /punish <player> ban $usage")
                }

                arg("time", ArgType.STRING) {
                    description = "Duration of the ban"
                    suggest { listOf("1h", "1d", "7d", "permanent") }
                    onInvalid { sender, raw ->
                        MessageUtil.send(sender, "&cInvalid time format: $raw")
                    }
                }

                action { sender, args ->
                    val target = args.player("target")
                    val time = args.string("time")
                    MessageUtil.send(sender, "&cBanned &f${target.name} &cfor &f$time&c.")
                    target.kick(ColorUtil.parse("&cYou have been banned for $time."))
                }
            }

            subcommand("kick") {
                description = "Kick a player from the server."
                permission = "core.punish.kick"

                action { sender, args ->
                    val target = args.player("target")
                    MessageUtil.send(sender, "&cKicked &f${target.name}&c.")
                    target.kick(ColorUtil.parse("&cYou have been kicked from the server."))
                }
            }
        }
    }
}