package com.aayan.albcore

import com.aayan.albcore.gui.GuiListener
import com.aayan.albcore.hooks.PAPIExpansion
import com.aayan.albcore.hooks.PAPIHook
import com.aayan.albcore.hooks.PacketEventsHook
import com.aayan.albcore.hooks.VaultHook
import com.aayan.albcore.listeners.PlayerListener
import com.aayan.albcore.logging.DiscordLogger
import com.aayan.albcore.utils.*
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

class ALBCore : JavaPlugin() {

    companion object {
        lateinit var instance: ALBCore
            private set
    }

    override fun onEnable() {
        instance = this

        val g = "\u001B[32m"
        val r = "\u001B[31m"
        val x = "\u001B[0m"

        println()
        println("$g══════════════════════════════════════════════════════════════$x")
        println("$g    _      _       ____       ____    ___    ____    _____ $x")
        println("$g   / \\    | |     | __ )     / ___|  / _ \\  |  _ \\  | ____|$x")
        println("$g  / _ \\   | |     |  _ \\    | |     | | | | | |_) | |  _|  $x")
        println("$g / ___ \\  | |___  | |_) |   | |___  | |_| | |  _ <  | |___ $x")
        println("$g/_/   \\_\\ |_____| |____/     \\____|  \\___/  |_| \\_\\ |_____|$x")
        println("")
        println("$g  Version : ${description.version}$x")
        println("$g  Author  : Aayan$x")
        println("$g══════════════════════════════════════════════════════════════$x")

        val vaultLoaded = VaultHook.setup(this)
        val papiLoaded = PAPIHook.setup(this)
        val packetLoaded = PacketEventsHook.setup(this)

        if (papiLoaded) PAPIExpansion().register()

        println("$g════════════════════════════════════$x")
        println("  Hooks:")
        println("  ${if (vaultLoaded) "$g✔$x" else "$r✘$x"} Vault")
        println("  ${if (papiLoaded) "$g✔$x" else "$r✘$x"} PlaceholderAPI")
        println("  ${if (packetLoaded) "$g✔$x" else "$r✘$x"} PacketEvents")


        val dependents = server.pluginManager.plugins
            .filter { plugin ->
                plugin.description.depend.contains("ALBCore") ||
                        plugin.description.softDepend.contains("ALBCore")
            }
            .map { it.name }

        if (dependents.isNotEmpty()) {
            println("$g════════════════════════════════════$x")
            println("  Plugins using ALBCore:")
            dependents.forEach { println("  $g✔$x $it") }
        }

        println("$g════════════════════════════════════$x")
        println()

        server.pluginManager.registerEvents(GuiListener(), this)
        server.pluginManager.registerEvents(PlayerListener(), this)

        Bukkit.getScheduler().runTaskTimerAsynchronously(this, Runnable {
            PlayerDataUtil.saveAll()
        }, 6000L, 6000L)
    }

    override fun onDisable() {
        PlayerDataUtil.saveAll()
        PacketEventsHook.unregisterAll()
        logger.info("All player data saved successfully!")
    }

}
