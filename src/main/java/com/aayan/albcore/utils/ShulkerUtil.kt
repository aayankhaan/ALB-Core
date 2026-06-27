package com.aayan.albcore.utils

import org.bukkit.Material
import org.bukkit.block.ShulkerBox
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta

object ShulkerUtil {

    fun flatten(items: List<ItemStack>): List<ItemStack> {
        val result = mutableListOf<ItemStack>()

        items.forEach { item ->
            val meta = item.itemMeta
            if (meta is BlockStateMeta && meta.blockState is ShulkerBox) {
                val shulker = meta.blockState as ShulkerBox
                val contents = shulker.inventory.contents
                    .filterNotNull()
                    .filter { it.type != Material.AIR }

                if (contents.isEmpty()) {
                    result.add(item)
                } else {
                    contents.forEach { result.add(it) }
                }
            } else {
                result.add(item)
            }
        }

        return result
    }
}