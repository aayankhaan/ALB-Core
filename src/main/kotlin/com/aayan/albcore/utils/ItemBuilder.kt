package com.aayan.albcore.utils

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType

/**
 * Fluent item creation. Names and lore use [ColorUtil] formatting and are not italic by default.
 * Pass a [Player] to replace PlaceholderAPI placeholders for that player.
 *
 * ```
 * val item = ItemBuilder(Material.DIAMOND_SWORD)
 *     .name("&bStarter Sword")
 *     .lore("&7Damage: &c5", "&7Tier: &aE")
 *     .glow()
 *     .build()
 * ```
 *
 * The builder works on a copy, so the ItemStack passed in is never changed.
 */
class ItemBuilder(item: ItemStack) {

    constructor(material: Material, amount: Int = 1) : this(ItemStack(material, amount))

    private val item: ItemStack = item.clone()
    private val meta: ItemMeta = this.item.itemMeta
        ?: throw IllegalArgumentException("Item has no meta: ${item.type}")

    fun name(text: String): ItemBuilder = name(null, text)

    fun name(player: Player?, text: String): ItemBuilder = apply {
        meta.displayName(format(player, text))
    }

    fun lore(vararg lines: String): ItemBuilder = lore(lines.toList(), null)

    fun lore(player: Player?, vararg lines: String): ItemBuilder = lore(lines.toList(), player)

    /** Replaces the lore. */
    fun lore(lines: List<String>, player: Player? = null): ItemBuilder = apply {
        meta.lore(lines.map { format(player, it) })
    }

    /** Appends lines below the existing lore. */
    fun addLore(lines: List<String>, player: Player? = null) = apply {
        meta.lore((meta.lore() ?: emptyList()) + lines.map { format(player, it) })
    }

    fun amount(amount: Int) = apply {
        item.amount = amount.coerceIn(1, item.maxStackSize)
    }

    fun enchant(enchantment: Enchantment, level: Int) = apply {
        meta.addEnchant(enchantment, level, true)
    }

    fun flag(vararg flags: ItemFlag) = apply {
        meta.addItemFlags(*flags)
    }

    /** Adds the enchantment shimmer without a fake enchantment. */
    fun glow(glow: Boolean = true) = apply {
        meta.setEnchantmentGlintOverride(glow)
    }

    fun unbreakable(unbreakable: Boolean = true) = apply {
        meta.isUnbreakable = unbreakable
    }

    /** Stores a value in the item's persistent data container. */
    fun <P : Any, C : Any> data(key: NamespacedKey, type: PersistentDataType<P, C>, value: C) = apply {
        meta.persistentDataContainer.set(key, type, value)
    }

    /** Marks the item so the ALBLore plugin leaves its lore alone (key `alblore:no-lore`). */
    fun ignoreAlbLore() = apply {
        meta.persistentDataContainer.set(NO_LORE_KEY, PersistentDataType.BOOLEAN, true)
    }

    /** Direct access to the meta for anything the builder does not cover. */
    fun meta(block: ItemMeta.() -> Unit) = apply {
        meta.block()
    }

    fun build(): ItemStack {
        item.itemMeta = meta
        return item.clone()
    }

    private fun format(player: Player?, text: String): Component {
        val component = if (player != null) ColorUtil.parseWithPAPI(player, text) else ColorUtil.parse(text)
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
    }

    companion object {
        /** Key checked by ALBLore: items with it keep their own lore. */
        val NO_LORE_KEY: NamespacedKey = NamespacedKey.fromString("alblore:no-lore")!!
    }
}
