package com.aayan.albcore.hooks

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerCommon
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap

/**
 * Optional PacketEvents support. Registers packet listeners and remembers which plugin owns them,
 * so they are removed when that plugin disables (other plugins' listeners are never touched).
 * Only reference this object if [isEnabled] is true; otherwise PacketEvents classes are missing.
 */
object PacketEventsHook {

    @Volatile
    private var enabled = false

    private val listeners = ConcurrentHashMap<String, MutableSet<PacketListenerCommon>>()

    internal fun setup(): Boolean {
        enabled = Bukkit.getPluginManager().isPluginEnabled("packetevents")
        return enabled
    }

    fun isEnabled(): Boolean = enabled

    /** Registers [listener] for [plugin]. Returns false if PacketEvents is not installed. */
    fun register(plugin: Plugin, listener: PacketListenerCommon): Boolean {
        if (!enabled) return false
        val registered = PacketEvents.getAPI().eventManager.registerListener(listener)
        listeners.computeIfAbsent(plugin.name) { ConcurrentHashMap.newKeySet() }.add(registered)
        return true
    }

    /** Unregisters every listener [plugin] registered through this hook. Called automatically on plugin disable. */
    fun unregisterAll(plugin: Plugin) {
        val owned = listeners.remove(plugin.name) ?: return
        if (!enabled) return
        val manager = PacketEvents.getAPI().eventManager
        owned.forEach { manager.unregisterListener(it) }
    }

    /** Unregisters the listeners of every plugin that used this hook (ALBCore shutdown). */
    internal fun unregisterEverything() {
        if (!enabled) {
            listeners.clear()
            return
        }
        val manager = PacketEvents.getAPI().eventManager
        listeners.values.forEach { set -> set.forEach { manager.unregisterListener(it) } }
        listeners.clear()
    }
}
