package com.aayan.albcore.hooks

import com.aayan.albcore.ALBCore
import com.github.retrooper.packetevents.PacketEvents

object PacketEventsHook {

    private var loaded = false

    fun setup(plugin: ALBCore): Boolean {
        if (plugin.server.pluginManager.getPlugin("packetevents") == null) {
            return false
        }

        loaded = true
        return true
    }

    fun isLoaded(): Boolean = loaded

    fun registerListener(listener: com.github.retrooper.packetevents.event.PacketListenerAbstract) {
        if (!loaded) return
        PacketEvents.getAPI().eventManager.registerListener(listener)
    }

    fun unregisterAll() {
        if (!loaded) return
        PacketEvents.getAPI().eventManager.unregisterAllListeners()
    }
}