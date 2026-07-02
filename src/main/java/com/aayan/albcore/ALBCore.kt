package com.aayan.albcore

import com.aayan.albcore.commands.ArgType
import com.aayan.albcore.commands.CommandUtil
import com.aayan.albcore.gui.GuiBuilder
import com.aayan.albcore.gui.GuiItem
import com.aayan.albcore.gui.GuiListener
import com.aayan.albcore.hooks.PAPIExpansion
import com.aayan.albcore.hooks.PAPIHook
import com.aayan.albcore.hooks.VaultHook
import com.aayan.albcore.listeners.PlayerListener
import com.aayan.albcore.logging.DiscordColor
import com.aayan.albcore.logging.DiscordLogger
import com.aayan.albcore.utils.*
import org.bukkit.Bukkit

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

class ALBCore : JavaPlugin() {

    companion object {
        lateinit var instance: ALBCore
            private set
    }

    override fun onEnable() {
        instance = this
        VaultHook.setup(this)
        DiscordLogger.setup("shop",
            "https://discord.com/api/webhooks/1520225204331352074/hCAwE6vQCmr5FfhOvd5Va6-x9fMR09NpIl0w9aHyssx8NFBaDS-LtZwocbeVAbsTBjfO",
            "Server Logger"
            )
        if (PAPIHook.setup(this)) {
            PAPIExpansion().register()
            registerPlaceHolder()
        }
        server.pluginManager.registerEvents(GuiListener(), this)
        server.pluginManager.registerEvents(PlayerListener(), this)

        Bukkit.getScheduler().runTaskTimerAsynchronously(this, Runnable {
            PlayerDataUtil.saveAll()
        }, 6000L, 6000L)
    }

    override fun onDisable() {
        PlayerDataUtil.saveAll()
        logger.info("All player data saved successfully!")
    }


    private fun registerPlaceHolder() {
        logger.info("Alb Test PAPI has been registered")
        PAPIExpansion.registerPAPI("test") {player -> NumberUtil.formatNumber(VaultHook.getMoney(player).toLong())}
    }

}
