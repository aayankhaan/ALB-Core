package com.aayan.albcore.utils

import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.time.Duration

object MessageUtil {

    fun send(player: Player, text: String) {
        player.sendMessage(ColorUtil.parse(text))
    }

    fun sendTitle(player: Player, title: String, subtitle: String, fadeIn: Long = 500, Stay: Long = 3000, fadeOut: Long = 500) {
        player.showTitle(Title.title(ColorUtil.parse(title), ColorUtil.parse(subtitle), Title.Times.times(Duration.ofMillis(fadeIn),Duration.ofMillis(Stay),Duration.ofMillis(fadeOut))))
    }

    fun broadcast(text: String) {
        Bukkit.broadcast(ColorUtil.parse(text))
    }
}