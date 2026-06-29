package com.aayan.albcore.utils

import com.aayan.albcore.ALBCore
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToLong

object AnimationUtil {

    enum class Easing {
        LINEAR,
        EASE_IN,
        EASE_OUT,
        EASE_IN_OUT
    }

    private val tasks = mutableMapOf<UUID, BukkitRunnable>()

    fun animateTitleNumber(player: Player, title: String, subtitle: String = "", prefix: String = "", suffix: String = "", from: Long = 0, to: Long, duration: Long = 1000, fadeIn: Long = 0, stay: Long = 1200, fadeOut: Long = 300, easing: Easing = Easing.EASE_OUT
    ) {

        tasks.remove(player.uniqueId)?.cancel()

        val ticks = (duration / 50).coerceAtLeast(1)
        var currentTick = 0L

        val task = object : BukkitRunnable() {

            override fun run() {

                val progress = (currentTick.toDouble() / ticks.toDouble())
                    .coerceIn(0.0, 1.0)

                val eased = when (easing) {
                    Easing.LINEAR -> progress
                    Easing.EASE_IN -> progress * progress
                    Easing.EASE_OUT -> 1 - (1 - progress).pow(2)
                    Easing.EASE_IN_OUT ->
                        if (progress < 0.5)
                            2 * progress * progress
                        else
                            1 - (-2 * progress + 2).pow(2) / 2
                }

                val value = (from + ((to - from) * eased)).roundToLong()

                val amount = "$prefix${NumberUtil.formatNumber(value)}$suffix"

                MessageUtil.sendTitle(
                    player,
                    title.replace("%amount%", amount),
                    subtitle.replace("%amount%", amount),
                    fadeIn,
                    stay,
                    fadeOut
                )

                if (currentTick >= ticks) {

                    MessageUtil.sendTitle(
                        player,
                        title.replace("%amount%", amount),
                        subtitle.replace("%amount%", amount),
                        fadeIn,
                        stay,
                        fadeOut
                    )

                    tasks.remove(player.uniqueId)
                    cancel()
                    return
                }

                currentTick++
            }
        }

        tasks[player.uniqueId] = task
        task.runTaskTimer(ALBCore.instance, 0L, 1L)
    }
}