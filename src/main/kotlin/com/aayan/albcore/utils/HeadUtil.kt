package com.aayan.albcore.utils

import com.destroystokyo.paper.profile.ProfileProperty
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Player heads from textures or players. */
object HeadUtil {

    /**
     * A head with a custom skin. [base64] is the "Value" texture string from sites like minecraft-heads.com.
     * The same texture always gets the same profile id, so heads made from it stack.
     */
    fun fromBase64(base64: String): ItemStack {
        val head = ItemStack(Material.PLAYER_HEAD)
        head.editMeta(SkullMeta::class.java) { meta ->
            val profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(base64.toByteArray(StandardCharsets.UTF_8)))
            profile.setProperty(ProfileProperty("textures", base64))
            meta.playerProfile = profile
        }
        return head
    }

    /** The head of the player called [name] (the skin is resolved by the server/client). */
    fun fromPlayerName(name: String): ItemStack {
        val head = ItemStack(Material.PLAYER_HEAD)
        head.editMeta(SkullMeta::class.java) { meta -> meta.playerProfile = Bukkit.createProfile(name) }
        return head
    }

    /** The head of [player]. */
    fun fromPlayer(player: OfflinePlayer): ItemStack {
        val head = ItemStack(Material.PLAYER_HEAD)
        head.editMeta(SkullMeta::class.java) { meta -> meta.owningPlayer = player }
        return head
    }
}
