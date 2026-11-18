package com.aayan.albcore.utils

import com.aayan.albcore.ALBCore
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToLong

/** Small client-side animations. Main thread only. */
object AnimationUtil {

    enum class Easing {
        LINEAR,
        EASE_IN,
        EASE_OUT,
        EASE_IN_OUT;

        fun apply(progress: Double): Double = when (this) {
            LINEAR -> progress
            EASE_IN -> progress * progress
            EASE_OUT -> 1 - (1 - progress).pow(2)
            EASE_IN_OUT -> if (progress < 0.5) 2 * progress * progress else 1 - (-2 * progress + 2).pow(2) / 2
        }
    }

    private val tasks = mutableMapOf<UUID, BukkitRunnable>()

    /**
     * Shows a title whose `%amount%` counts from [from] to [to] over [duration] ms
     * (e.g. "+%amount% coins"). Starting a new animation for the same player replaces the old one.
     * Times are in milliseconds.
     */
    fun animateTitleNumber(
        player: Player,
        title: String,
        subtitle: String = "",
        prefix: String = "",
        suffix: String = "",
        from: Long = 0,
        to: Long,
        duration: Long = 1000,
        fadeIn: Long = 0,
        stay: Long = 1200,
        fadeOut: Long = 300,
        easing: Easing = Easing.EASE_OUT,
    ) {
        tasks.remove(player.uniqueId)?.cancel()

        val totalTicks = (duration / 50).coerceAtLeast(1)
        val task = object : BukkitRunnable() {
            var tick = 0L

            override fun run() {
                if (!player.isOnline) {
                    finish()
                    return
                }
                val progress = (tick.toDouble() / totalTicks).coerceIn(0.0, 1.0)
                val value = (from + (to - from) * easing.apply(progress)).roundToLong()
                val amount = "$prefix${NumberUtil.formatNumber(value)}$suffix"
                MessageUtil.sendTitle(
                    player,
                    title.replace("%amount%", amount),
                    subtitle.replace("%amount%", amount),
                    fadeIn, stay, fadeOut,
                )
                if (tick >= totalTicks) finish() else tick++
            }

            private fun finish() {
                tasks.remove(player.uniqueId, this)
                cancel()
            }
        }
        tasks[player.uniqueId] = task
        task.runTaskTimer(ALBCore.instance, 0L, 1L)
    }
}
