package com.aayan.albcore.utils

import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack


class ItemBuilder(material: Material, amount: Int = 1) {

    private val item = ItemStack(material, amount)
    private val meta = item.itemMeta ?: throw IllegalArgumentException("Item has no meta: $material")

    fun name(name: String) = apply {
        meta.displayName(ColorUtil.parse(name).decoration(TextDecoration.ITALIC, false))
    }

    fun lore(vararg lines: String) = apply {
        meta.lore(lines.map { ColorUtil.parse(it).decoration(TextDecoration.ITALIC, false) })
    }

    fun enchant(enchantment: Enchantment, level: Int) = apply {
        meta.addEnchant(enchantment, level, true)
    }

    fun flag(vararg flags: ItemFlag) = apply {
        meta.addItemFlags(*flags)
    }

    fun glow() = apply {
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        meta.addEnchant(Enchantment.UNBREAKING,1,true)
    }

    fun build(): ItemStack {
        item.itemMeta = meta
        return item
    }
}
