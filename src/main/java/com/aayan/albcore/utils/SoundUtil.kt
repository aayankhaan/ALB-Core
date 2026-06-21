package com.aayan.albcore.utils

import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit
import org.bukkit.entity.Player


object SoundUtil {

    fun play(player: Player, soundKey: String, volume: Float = 1f, pitch: Float = 1f) {
        val sound = Sound.sound(Key.key(soundKey), Sound.Source.MASTER, volume, pitch)
        player.playSound(sound)
    }

    fun broadcast(soundKey: String, volume: Float = 1f, pitch: Float = 1f) {
        Bukkit.getOnlinePlayers().forEach { play(it, soundKey, volume, pitch) }
    }
}