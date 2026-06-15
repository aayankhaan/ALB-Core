package com.aayan.albcore

import org.bukkit.plugin.java.JavaPlugin

class ALBCore : JavaPlugin() {

    override fun onEnable() {
        logger.info("ALB-Core has been enabled!")
    }

    override fun onDisable() {
        logger.info("ALB-Core has been disabled!")
    }
}