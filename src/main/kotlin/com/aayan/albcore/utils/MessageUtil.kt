package com.aayan.albcore.utils

import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.time.Duration

/** Sends formatted text (see [ColorUtil]) to players and the console. */
object MessageUtil {

    /** Sends [text] to any sender (player or console). No placeholders. */
    fun send(sender: CommandSender, text: String) {
        sender.sendMessage(ColorUtil.parse(text))
    }

    /** Sends [text] to [player] with PlaceholderAPI placeholders replaced. */
    fun sendWithPAPI(player: Player, text: String) {
        player.sendMessage(ColorUtil.parseWithPAPI(player, text))
    }

    /** Shows [text] above the hotbar. */
    fun actionBar(player: Player, text: String) {
        player.sendActionBar(ColorUtil.parseWithPAPI(player, text))
    }

    /** Shows a title. Times are in milliseconds. */
    fun sendTitle(
        player: Player,
        title: String,
        subtitle: String = "",
        fadeIn: Long = 500,
        stay: Long = 3000,
        fadeOut: Long = 500,
    ) {
        val times = Title.Times.times(Duration.ofMillis(fadeIn), Duration.ofMillis(stay), Duration.ofMillis(fadeOut))
        player.showTitle(
            Title.title(ColorUtil.parseWithPAPI(player, title), ColorUtil.parseWithPAPI(player, subtitle), times)
        )
    }

    /** Sends [text] to every online player (placeholders per player), optionally only those with [permission]. */
    fun broadcast(text: String, permission: String? = null) {
        Bukkit.getOnlinePlayers().forEach { player ->
            if (permission == null || player.hasPermission(permission)) sendWithPAPI(player, text)
        }
    }
}
