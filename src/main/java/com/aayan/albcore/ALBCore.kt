package com.aayan.albcore

import com.aayan.albcore.gui.GuiListener
import org.bukkit.plugin.java.JavaPlugin

class ALBCore : JavaPlugin() {

    override fun onEnable() {
        logger.info("ALB-Core has been enabled!")



        server.pluginManager.registerEvents(GuiListener(), this)
    }

    override fun onDisable() {
        logger.info("ALB-Core has been disabled!")
    }
}