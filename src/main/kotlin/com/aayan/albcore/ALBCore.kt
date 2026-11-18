package com.aayan.albcore

import com.aayan.albcore.commands.ALBCoreCommand
import com.aayan.albcore.config.CoreConfig
import com.aayan.albcore.data.PlayerDataListener
import com.aayan.albcore.data.PlayerDataService
import com.aayan.albcore.data.PlayerDataStorage
import com.aayan.albcore.data.PlayerDataStorageType
import com.aayan.albcore.data.SqlPlayerDataStorage
import com.aayan.albcore.data.YamlPlayerDataStorage
import com.aayan.albcore.database.Database
import com.aayan.albcore.gui.GuiBuilder
import com.aayan.albcore.gui.GuiListener
import com.aayan.albcore.hooks.PAPIHook
import com.aayan.albcore.hooks.PacketEventsHook
import com.aayan.albcore.hooks.VaultHook
import com.aayan.albcore.logging.DiscordLogger
import com.aayan.albcore.utils.PriceRegistry
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.logging.Level

/**
 * ALBCore plugin entry point and service locator for child plugins.
 *
 * Child plugins add `depend: [ALBCore]` to their plugin.yml and then use:
 * - [database] for their own tables (see [Database]),
 * - [com.aayan.albcore.data.PlayerData] for simple per-player values,
 * - the GUI, command, hook and util APIs directly.
 *
 * If the database cannot be reached at startup, ALBCore disables itself, and its classes can no longer
 * be loaded by other plugins. Dependents should start `onEnable` with
 * `if (!server.pluginManager.isPluginEnabled("ALBCore")) { disable; return }` before touching any ALBCore class.
 */
class ALBCore : JavaPlugin() {

    companion object {
        /** The running ALBCore plugin. Safe to use from a dependent plugin's `onEnable`. */
        @JvmStatic
        lateinit var instance: ALBCore
            private set
    }

    /** Parsed config.yml. */
    lateinit var coreConfig: CoreConfig
        private set

    /** The shared database (SQLite or MySQL, from `database.type`). */
    lateinit var database: Database
        private set

    /** Per-player data service behind [com.aayan.albcore.data.PlayerData]. */
    lateinit var playerData: PlayerDataService
        private set

    private var autosaveTask: BukkitTask? = null

    override fun onEnable() {
        instance = this
        DiscordLogger.logger = logger
        saveDefaultConfig()
        coreConfig = CoreConfig.load(config, dataFolder) { logger.warning("config.yml: $it") }

        try {
            database = Database(coreConfig.database, logger)
            playerData = PlayerDataService(openPlayerDataStorage(coreConfig.playerData.storage), logger)
        } catch (e: Exception) {
            // Connection problems (wrong host/password, server down) get a one-line message; anything else a stack trace.
            if (e is IllegalStateException) logger.severe("ALBCore could not start its database: ${e.message}")
            else logger.log(Level.SEVERE, "ALBCore could not start its database: ${e.message}", e)
            logger.severe("Check the database section of plugins/ALBCore/config.yml. ALBCore is now disabled.")
            if (::database.isInitialized) database.close()
            server.pluginManager.disablePlugin(this)
            return
        }

        val vault = VaultHook.setup()
        val papi = PAPIHook.setup(this)
        val packets = PacketEventsHook.setup()

        registerWebhooks()
        server.pluginManager.registerEvents(GuiListener(), this)
        server.pluginManager.registerEvents(
            PlayerDataListener(playerData, logger, coreConfig.playerData.loadTimeoutSeconds) {
                coreConfig.playerData.loadFailedMessage
            },
            this,
        )
        server.pluginManager.registerEvents(CleanupListener(), this)
        ALBCoreCommand.register(this)
        startAutosave()
        loadOnlinePlayers()
        logStartup(vault, papi, packets)
    }

    override fun onDisable() {
        autosaveTask?.cancel()
        // The GUI listener stops with this plugin, so close GUIs before their slots become unlocked.
        server.onlinePlayers
            .filter { it.openInventory.topInventory.getHolder(false) is GuiBuilder }
            .forEach { it.closeInventory() }
        PacketEventsHook.unregisterEverything()
        if (::playerData.isInitialized) playerData.close(30)
        if (::database.isInitialized) database.close(30)
        DiscordLogger.clear()
    }

    /**
     * Re-reads config.yml. Webhooks, autosave interval and messages apply at once; database and
     * player-data storage changes need a restart. Returns the warnings to show to the sender.
     */
    fun reloadSettings(): List<String> {
        reloadConfig()
        val warnings = mutableListOf<String>()
        val fresh = CoreConfig.load(config, dataFolder) { warnings += it }
        if (fresh.database != coreConfig.database || fresh.playerData.storage != coreConfig.playerData.storage) {
            warnings += "Database and player-data storage changes apply after a restart."
        }
        coreConfig = fresh.copy(
            database = coreConfig.database,
            playerData = fresh.playerData.copy(storage = coreConfig.playerData.storage),
        )
        registerWebhooks()
        startAutosave()
        warnings.forEach { logger.warning("config.yml: $it") }
        return warnings
    }

    /**
     * Opens a player-data storage of [type]. SQL types reuse the shared [database] when it has the
     * same type; otherwise they open a small separate pool. Blocking (it may connect): not on the main
     * thread during gameplay.
     */
    internal fun openPlayerDataStorage(type: PlayerDataStorageType): PlayerDataStorage {
        val databaseType = type.databaseType
            ?: return YamlPlayerDataStorage(File(dataFolder, "playerdata"), logger)
        if (databaseType == database.type) return SqlPlayerDataStorage(database, ownsDatabase = false)
        val settings = coreConfig.database.copy(
            type = databaseType,
            poolName = "ALBCore-PlayerData",
            pool = coreConfig.database.pool.copy(maximumPoolSize = 3, minimumIdle = 1),
        )
        val separate = Database(settings, logger)
        return try {
            SqlPlayerDataStorage(separate, ownsDatabase = true)
        } catch (e: Exception) {
            separate.close()
            throw e
        }
    }

    private fun registerWebhooks() {
        DiscordLogger.clear()
        coreConfig.webhooks.forEach { (name, hook) -> DiscordLogger.register(name, hook.url, hook.username, hook.avatarUrl) }
    }

    private fun startAutosave() {
        autosaveTask?.cancel()
        val ticks = coreConfig.playerData.autosaveIntervalSeconds * 20L
        autosaveTask = server.scheduler.runTaskTimerAsynchronously(this, Runnable {
            playerData.saveAll()
            playerData.unloadOffline({ uuid -> server.getPlayer(uuid) != null })
        }, ticks, ticks)
    }

    /** After a plugin reload, players are already online and never went through pre-login. */
    private fun loadOnlinePlayers() {
        val online = server.onlinePlayers
        if (online.isEmpty()) return
        logger.info("Loading player data of ${online.size} online player(s).")
        online.forEach { player ->
            try {
                playerData.load(player.uniqueId).get(coreConfig.playerData.loadTimeoutSeconds, TimeUnit.SECONDS)
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Could not load player data of ${player.name}", e)
            }
        }
    }

    private fun logStartup(vault: Boolean, papi: Boolean, packets: Boolean) {
        val green = NamedTextColor.GREEN
        val art = listOf(
            "    _      _       ____       ____    ___    ____    _____ ",
            "   / \\    | |     | __ )     / ___|  / _ \\  |  _ \\  | ____|",
            "  / _ \\   | |     |  _ \\    | |     | | | | | |_) | |  _|  ",
            " / ___ \\  | |___  | |_) |   | |___  | |_| | |  _ <  | |___ ",
            "/_/   \\_\\ |_____| |____/     \\____|  \\___/  |_| \\_\\ |_____|",
        )
        art.forEach { componentLogger.info(Component.text(it, green)) }
        componentLogger.info(Component.text("Version ${pluginMeta.version} by ${pluginMeta.authors.joinToString()}", green))

        val db = coreConfig.database
        componentLogger.info(Component.text("Database: ${db.type.configName} (table prefix '${db.tablePrefix}'), player data: ${playerData.storageType.configName}", green))

        val hooks = Component.text("Hooks:", green)
            .append(hookStatus("Vault", vault))
            .append(hookStatus("PlaceholderAPI", papi))
            .append(hookStatus("PacketEvents", packets))
        componentLogger.info(hooks)

        val dependents = server.pluginManager.plugins
            .filter { plugin ->
                plugin != this && (name in plugin.pluginMeta.pluginDependencies || name in plugin.pluginMeta.pluginSoftDependencies)
            }
            .map { it.name }
        if (dependents.isNotEmpty()) {
            componentLogger.info(Component.text("Plugins using ALBCore: ${dependents.joinToString()}", green))
        }
    }

    private fun hookStatus(name: String, enabled: Boolean): Component =
        if (enabled) Component.text(" $name on", NamedTextColor.GREEN) else Component.text(" $name off", NamedTextColor.RED)

    /** Removes what a disabling plugin registered in ALBCore's shared registries. */
    private class CleanupListener : Listener {
        @EventHandler
        fun onPluginDisable(event: PluginDisableEvent) {
            PAPIHook.unregisterAll(event.plugin)
            PriceRegistry.unregisterAll(event.plugin)
            PacketEventsHook.unregisterAll(event.plugin)
        }
    }
}
