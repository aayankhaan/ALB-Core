package com.aayan.albcore.commands

import com.aayan.albcore.ALBCore
import com.aayan.albcore.data.PlayerDataStorageType
import com.aayan.albcore.hooks.PAPIHook
import com.aayan.albcore.hooks.PacketEventsHook
import com.aayan.albcore.hooks.VaultHook
import com.aayan.albcore.logging.DiscordLogger
import com.aayan.albcore.utils.MessageUtil
import com.aayan.albcore.utils.thenSync
import org.bukkit.command.CommandSender
import java.util.concurrent.CompletableFuture

/** `/albcore` admin command (permission `albcore.admin`). */
internal object ALBCoreCommand {

    private object StorageArg : ArgType<PlayerDataStorageType>("storage type") {
        override fun parse(raw: String): PlayerDataStorageType? = PlayerDataStorageType.parse(raw)
        override fun suggestions(): List<String> = PlayerDataStorageType.entries.map { it.configName }
    }

    fun register(plugin: ALBCore) {
        CommandUtil.registerCommand(plugin, "albcore") {
            description = "ALBCore admin commands"
            permission = "albcore.admin"
            action { sender, _ -> info(plugin, sender) }

            subcommand("info") {
                action { sender, _ -> info(plugin, sender) }
            }

            subcommand("reload") {
                action { sender, _ ->
                    val warnings = plugin.reloadSettings()
                    MessageUtil.send(sender, "&aALBCore config, lang.yml and menus reloaded.")
                    warnings.forEach { MessageUtil.send(sender, "&e$it") }
                }
            }

            subcommand("playerdata") {
                subcommand("save") {
                    action { sender, _ ->
                        plugin.playerData.saveAll()
                            .thenSync(plugin) { MessageUtil.send(sender, "&aSaved all loaded player data.") }
                            .exceptionally { error -> sendLater(plugin, sender, "&cSave failed: ${error.message}") }
                    }
                }

                subcommand("import") {
                    description = "Copy all player data from another storage into the current one"
                    arg("from", StorageArg)
                    action { sender, args -> import(plugin, sender, args.get("from")) }
                }
            }
        }
    }

    private fun info(plugin: ALBCore, sender: CommandSender) {
        val db = plugin.database
        val stats = db.poolStats()
        MessageUtil.send(sender, "&6ALBCore &e${plugin.pluginMeta.version}")
        MessageUtil.send(
            sender,
            "&7Database: &f${db.type.configName} &7(prefix '${db.tablePrefix}') " +
                "&7pool: &f${stats.active} active, ${stats.idle} idle, ${stats.queuedTasks} queued",
        )
        MessageUtil.send(
            sender,
            "&7Player data: &f${plugin.playerData.storageType.configName}&7, loaded: &f${plugin.playerData.loadedPlayers().size}",
        )
        MessageUtil.send(
            sender,
            "&7Hooks: Vault ${flag(VaultHook.isEnabled())}&7, PlaceholderAPI ${flag(PAPIHook.isEnabled())}" +
                "&7, PacketEvents ${flag(PacketEventsHook.isEnabled())}",
        )
        MessageUtil.send(sender, "&7Placeholders: &f${PAPIHook.registeredPlaceholders().size}&7, webhooks: &f${DiscordLogger.registeredNames().joinToString().ifEmpty { "none" }}")
    }

    private fun import(plugin: ALBCore, sender: CommandSender, from: PlayerDataStorageType) {
        if (from == plugin.playerData.storageType) {
            MessageUtil.send(sender, "&cPlayer data is already stored in ${from.configName}.")
            return
        }
        MessageUtil.send(sender, "&7Importing player data from &f${from.configName}&7 into &f${plugin.playerData.storageType.configName}&7...")
        // Opening another storage may connect to a database, so do it off the main thread.
        CompletableFuture.supplyAsync { plugin.openPlayerDataStorage(from) }
            .thenCompose { source ->
                // Close on a pool thread: a callback may run on the source's own worker, which must not wait for itself.
                plugin.playerData.importFrom(source).whenCompleteAsync { _, _ -> source.close() }
            }
            .thenSync(plugin) { result ->
                MessageUtil.send(sender, "&aImported ${result.imported} player(s).")
                if (result.skippedOnline > 0) {
                    MessageUtil.send(sender, "&e${result.skippedOnline} online player(s) were skipped; their current data is kept.")
                }
            }
            .exceptionally { error ->
                val cause = error.cause ?: error
                plugin.logger.warning("Player data import from ${from.configName} failed: ${cause.message}")
                sendLater(plugin, sender, "&cImport failed: ${cause.message}")
            }
    }

    private fun flag(enabled: Boolean) = if (enabled) "&aon" else "&coff"

    private fun sendLater(plugin: ALBCore, sender: CommandSender, text: String) {
        if (plugin.isEnabled) plugin.server.scheduler.runTask(plugin, Runnable { MessageUtil.send(sender, text) })
    }
}
