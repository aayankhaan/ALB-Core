package com.aayan.albcore.utils

import org.bukkit.Material
import org.bukkit.entity.Player

import org.bukkit.plugin.Plugin

object AveragePrice {

    private val registry = mutableMapOf<Material, MutableMap<String, (Player) -> Double>>()


    fun set(material: Material, value: Double, plugin: Plugin) {
        registry.getOrPut(material) { mutableMapOf() }[plugin.name] = { value }
    }

    fun set(material: Material, plugin: Plugin, provider: (Player) -> Double) {
        registry.getOrPut(material) { mutableMapOf()}[plugin.name] = provider
    }

    fun getPrices(material: Material, player: Player): Map<String, Double> {
        return registry[material]
            ?.mapValues { (_, provider) -> provider(player) }
            ?: emptyMap()
    }

    fun getAverage(material: Material, player: Player): Double? {
        val prices = getPrices(material, player).values

        return if (prices.isEmpty()) {
            null
        } else {
            prices.average()
        }
    }

    fun hasPrice(material: Material): Boolean = registry.containsKey(material)
}