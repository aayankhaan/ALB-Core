package com.aayan.albcore.listeners

import com.aayan.albcore.utils.PlayerDataUtil
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class PlayerListener : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) = PlayerDataUtil.load(event.player)

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) = PlayerDataUtil.unload(event.player)

}