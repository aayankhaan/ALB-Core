package com.aayan.albcore.gui

import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Per-player click cooldown shared by every GuiBuilder (one fast double tap must not run an action twice). */
internal object GuiClicks {

    private val lastAccepted = ConcurrentHashMap<UUID, Long>()

    /** False (ignore the click) when [player]'s last accepted click was less than [cooldownMillis] ago. */
    fun accept(player: Player, cooldownMillis: Long, now: Long = System.currentTimeMillis()): Boolean {
        if (cooldownMillis <= 0) return true
        val last = lastAccepted[player.uniqueId] ?: 0L
        if (now - last < cooldownMillis) return false
        lastAccepted[player.uniqueId] = now
        return true
    }

    fun forget(uuid: UUID) {
        lastAccepted.remove(uuid)
    }
}
