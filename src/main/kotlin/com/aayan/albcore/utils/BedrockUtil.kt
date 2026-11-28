package com.aayan.albcore.utils

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Tells Bedrock players (joined through Geyser/Floodgate) apart from Java players, so plugins can send
 * them what Bedrock can show (no custom font glyphs, no hover text, no text displays).
 *
 * Uses Floodgate's API when Floodgate runs, else Geyser's; both are looked up by reflection, so neither
 * is a dependency. Without them every player counts as Java. Safe from any thread.
 */
object BedrockUtil {

    @Volatile
    private var check: ((UUID) -> Boolean)? = null

    fun isBedrock(player: Player): Boolean = isBedrock(player.uniqueId)

    fun isBedrock(uuid: UUID): Boolean {
        val test = check ?: lookup()?.also { check = it } ?: return false
        return runCatching { test(uuid) }.getOrDefault(false)
    }

    /** True when Floodgate or Geyser was found (so [isBedrock] can answer true at all). */
    fun isAvailable(): Boolean = (check ?: lookup()?.also { check = it }) != null

    private fun lookup(): ((UUID) -> Boolean)? = floodgate() ?: geyser()

    private fun floodgate(): ((UUID) -> Boolean)? = runCatching {
        val plugin = Bukkit.getPluginManager().getPlugin("floodgate")?.takeIf { it.isEnabled } ?: return null
        val api = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true, plugin.javaClass.classLoader)
        val instance = api.getMethod("getInstance").invoke(null)
        val method = api.getMethod("isFloodgatePlayer", UUID::class.java)
        val test: (UUID) -> Boolean = { uuid -> method.invoke(instance, uuid) as Boolean }
        test
    }.getOrNull()

    private fun geyser(): ((UUID) -> Boolean)? = runCatching {
        val plugin = Bukkit.getPluginManager().getPlugin("Geyser-Spigot")?.takeIf { it.isEnabled } ?: return null
        val api = Class.forName("org.geysermc.geyser.api.GeyserApi", true, plugin.javaClass.classLoader)
        val instance = api.getMethod("api").invoke(null)
        val method = api.getMethod("isBedrockPlayer", UUID::class.java)
        val test: (UUID) -> Boolean = { uuid -> method.invoke(instance, uuid) as Boolean }
        test
    }.getOrNull()
}
