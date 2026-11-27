package com.aayan.albcore.gui

import com.aayan.albcore.ALBCore
import com.aayan.albcore.config.GuiSound
import com.aayan.albcore.utils.SoundUtil
import org.bukkit.entity.Player

/**
 * The four menu sounds every ALB menu uses (ALBCore config.yml `gui.sounds`):
 *  - [click]: any button. [GuiBuilder.button] and [GuiItem.button] play it for you.
 *  - [toggled]: a setting switched on (high ping) or off (low ping).
 *  - [denied]: not allowed / not enough money / locked.
 *  - [success]: something finished (bought, created, upgraded).
 */
object GuiSounds {

    fun click(player: Player) = play(player, settings()?.click)

    fun toggled(player: Player, on: Boolean) = play(player, settings()?.let { if (on) it.toggleOn else it.toggleOff })

    fun denied(player: Player) = play(player, settings()?.denied)

    fun success(player: Player) = play(player, settings()?.success)

    private fun settings() = runCatching { ALBCore.instance.coreConfig.gui }.getOrNull()

    private fun play(player: Player, sound: GuiSound?) {
        if (sound == null || sound.key.isBlank()) return
        SoundUtil.play(player, sound.key, sound.volume, sound.pitch)
    }
}
