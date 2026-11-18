package com.aayan.albcore.utils

import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

/** Plays sounds by key, e.g. `"minecraft:entity.experience_orb.pickup"` or a Nexo/resource-pack sound. */
object SoundUtil {

    private val warnedKeys = ConcurrentHashMap.newKeySet<String>()

    fun play(
        player: Player,
        soundKey: String,
        volume: Float = 1f,
        pitch: Float = 1f,
        source: Sound.Source = Sound.Source.MASTER,
    ) {
        val sound = sound(soundKey, volume, pitch, source) ?: return
        player.playSound(sound)
    }

    fun broadcast(soundKey: String, volume: Float = 1f, pitch: Float = 1f, source: Sound.Source = Sound.Source.MASTER) {
        val sound = sound(soundKey, volume, pitch, source) ?: return
        Bukkit.getOnlinePlayers().forEach { it.playSound(sound) }
    }

    /** Builds the sound, or logs one warning per bad key and returns null. */
    private fun sound(soundKey: String, volume: Float, pitch: Float, source: Sound.Source): Sound? =
        try {
            Sound.sound(Key.key(soundKey), source, volume, pitch)
        } catch (e: RuntimeException) {
            if (warnedKeys.add(soundKey)) Bukkit.getLogger().warning("[ALBCore] Invalid sound key '$soundKey': ${e.message}")
            null
        }
}
