package com.aayan.albcore.utils

import com.aayan.albcore.hooks.PAPIHook
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.time.Duration

object MessageUtil {

    fun send(sender: CommandSender, text: String) {
        sender.sendMessage(ColorUtil.parse(text))
    }

    fun sendWithPAPI(player: Player, text: String) {
        player.sendMessage(ColorUtil.parse(PAPIHook.parse(player,text)))
    }

    fun sendTitle(player: Player, title: String, subtitle: String, fadeIn: Long = 500, stay: Long = 3000, fadeOut: Long = 500) {
        player.showTitle(Title.title(ColorUtil.parse(PAPIHook.parse(player,title)), ColorUtil.parse(PAPIHook.parse(player, subtitle)), Title.Times.times(Duration.ofMillis(fadeIn),Duration.ofMillis(stay),Duration.ofMillis(fadeOut))))
    }

    fun broadcast(text: String) {
        Bukkit.getOnlinePlayers().forEach { player ->
            player.sendMessage(ColorUtil.parse(PAPIHook.parse(player, text)))
        }
    }
}