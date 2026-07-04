package com.aayan.albcore.utils

import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class ItemBuilder(private val item: ItemStack) {

    constructor(material: Material, amount: Int = 1) : this(ItemStack(material, amount))

    private val meta = item.itemMeta ?: throw IllegalArgumentException("Item has no meta: ${item.type}")

    fun name(player: Player, text: String) = apply {
        meta.displayName(ColorUtil.parseWithPAPI(player, text).decoration(TextDecoration.ITALIC, false))
    }

    fun lore(player: Player, vararg lines: String) = apply {
        meta.lore(lines.map { ColorUtil.parseWithPAPI(player, it).decoration(TextDecoration.ITALIC, false) })
    }

    fun ignoreAlbLore() = apply {
        val key = NamespacedKey("alblore", "no-lore")
        meta.persistentDataContainer.set(key, PersistentDataType.BOOLEAN, true)
    }

    fun enchant(enchantment: Enchantment, level: Int) = apply {
        meta.addEnchant(enchantment, level, true)
    }

    fun flag(vararg flags: ItemFlag) = apply {
        meta.addItemFlags(*flags)
    }

    fun glow() = apply {
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        meta.addEnchant(Enchantment.UNBREAKING, 1, true)
    }

    fun build(): ItemStack {
        item.itemMeta = meta
        return item
    }
}
