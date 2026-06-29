package com.aayan.albcore.utils

import org.bukkit.Material
import org.bukkit.block.ShulkerBox
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta

object ShulkerUtil {

    data class ProcessResult(
        val sellItems: MutableList<ItemStack> = mutableListOf(),
        val returnItems: MutableList<ItemStack> = mutableListOf()
    )

    fun process(
        items: List<ItemStack>,
        canSell: (ItemStack) -> Boolean
    ): ProcessResult {

        val result = ProcessResult()

        items.forEach { item ->

            val meta = item.itemMeta

            if (meta is BlockStateMeta && meta.blockState is ShulkerBox) {

                val originalShulker = meta.blockState as ShulkerBox

                val remainingItems = mutableListOf<ItemStack>()
                var soldSomething = false

                originalShulker.inventory.contents
                    .filterNotNull()
                    .filter { it.type != Material.AIR }
                    .forEach { content ->

                        if (canSell(content)) {
                            result.sellItems += content.clone()
                            soldSomething = true
                        } else {
                            remainingItems += content.clone()
                        }
                    }

                if (!soldSomething && remainingItems.isEmpty()) {
                    if (canSell(item)) {
                        result.sellItems += item.clone()
                    } else {
                        result.returnItems += item.clone()
                    }
                    return@forEach
                }

                val shulkerClone = item.clone()
                val cloneMeta = shulkerClone.itemMeta as BlockStateMeta
                val cloneState = cloneMeta.blockState as ShulkerBox

                cloneState.inventory.clear()

                remainingItems.forEach {
                    cloneState.inventory.addItem(it)
                }

                cloneMeta.blockState = cloneState
                shulkerClone.itemMeta = cloneMeta

                result.returnItems += shulkerClone

            } else {

                if (canSell(item))
                    result.sellItems += item.clone()
                else
                    result.returnItems += item.clone()

            }
        }

        return result
    }
}