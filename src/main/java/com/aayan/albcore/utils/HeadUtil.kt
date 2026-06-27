package com.aayan.albcore.utils

import com.destroystokyo.paper.profile.ProfileProperty
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.util.*
import kotlin.io.encoding.Base64

object HeadUtil {

    fun fromBase64(base64: String): ItemStack {
        val head = ItemStack(Material.PLAYER_HEAD)
        val meta = head.itemMeta as? SkullMeta ?: return  head

        val profile = Bukkit.createProfile(UUID.randomUUID())
        profile.setProperty(ProfileProperty("texture", base64))

        meta.playerProfile = profile
        head.itemMeta = meta
        return  head
    }

    fun fromPlayerName(name: String): ItemStack {
        val head = ItemStack(Material.PLAYER_HEAD)
        val meta = head.itemMeta as? SkullMeta ?: return head

        meta.setOwner(name)

        head.itemMeta = meta
        return head
    }
}