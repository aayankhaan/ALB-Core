package com.aayan.albcore.data

import com.aayan.albcore.utils.ColorUtil
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/** Loads player data before join (off the main thread) and saves it on quit. */
internal class PlayerDataListener(
    private val service: PlayerDataService,
    private val logger: Logger,
    private val loadTimeoutSeconds: Long,
    private val loadFailedMessage: () -> String,
) : Listener {

    // Runs on a login thread, so blocking here is fine and the data is ready before PlayerJoinEvent.
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) return
        try {
            service.load(event.uniqueId).get(loadTimeoutSeconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            val cause = PlayerDataService.unwrap(e)
            logger.log(Level.SEVERE, "Could not load player data of ${event.name} (${event.uniqueId}): ${cause.message}", cause)
            // Never let a player in with empty data: the next save would overwrite their real data.
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, ColorUtil.parse(loadFailedMessage()))
        }
    }

    // Another plugin denied the login after we loaded: drop the cached copy again.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPreLoginResult(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult == AsyncPlayerPreLoginEvent.Result.ALLOWED) return
        if (Bukkit.getPlayer(event.uniqueId) == null) service.discard(event.uniqueId)
    }

    // Safety net for the rare case where data was unloaded between pre-login and join (duplicate login).
    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        if (!service.isLoaded(event.player.uniqueId)) service.load(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        service.unload(event.player.uniqueId)
    }
}
