package com.aayan.albcore.utils

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap

/**
 * A shared list of item prices that several plugins can contribute to (e.g. a shop and a sell wand),
 * so others can read one price or the average. Replaces the old `AveragePrice`.
 * Entries are removed automatically when the plugin that added them disables.
 */
object PriceRegistry {

    private val registry = ConcurrentHashMap<Material, ConcurrentHashMap<String, (Player) -> Double>>()

    /** Sets [plugin]'s fixed price for [material]. */
    fun set(plugin: Plugin, material: Material, price: Double) = set(plugin, material) { price }

    /** Sets [plugin]'s price for [material], computed per player (e.g. with a rank multiplier). */
    fun set(plugin: Plugin, material: Material, provider: (Player) -> Double) {
        registry.computeIfAbsent(material) { ConcurrentHashMap() }[plugin.name] = provider
    }

    fun remove(plugin: Plugin, material: Material) {
        registry[material]?.remove(plugin.name)
    }

    /** Removes every price [plugin] set. Called automatically on plugin disable. */
    fun unregisterAll(plugin: Plugin) {
        registry.values.forEach { it.remove(plugin.name) }
        registry.values.removeIf { it.isEmpty() }
    }

    /** Every plugin's price for [material] and [player], keyed by plugin name. */
    fun getPrices(material: Material, player: Player): Map<String, Double> =
        registry[material]?.mapValues { (_, provider) -> provider(player) } ?: emptyMap()

    /** The average of all prices for [material], or null if no plugin set one. */
    fun getAverage(material: Material, player: Player): Double? =
        getPrices(material, player).values.takeIf { it.isNotEmpty() }?.average()

    fun hasPrice(material: Material): Boolean = registry[material]?.isNotEmpty() == true
}
