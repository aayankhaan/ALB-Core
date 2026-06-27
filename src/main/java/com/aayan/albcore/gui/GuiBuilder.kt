package com.aayan.albcore.gui

import com.aayan.albcore.ALBCore
import com.aayan.albcore.utils.ColorUtil
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask

class GuiBuilder(private val title: String, private val rows: Int) : InventoryHolder {

    internal val actions = mutableMapOf<Int, (Player) -> Unit>()
    private val dynamicItem = mutableMapOf<Int, (Player) -> ItemStack>()

    internal val pages = mutableListOf<PageScope>()
    internal var globalScope: PageScope? = null
    internal var currentPage: Int = 0

    internal val pageActions = mutableMapOf<Int, (Player, PageContext) -> Unit>()

    internal var refreshTask: BukkitTask? = null
    internal var onOpenAction: ((Player) -> Unit)? = null
    internal var onCloseAction: ((Player) -> Unit)? = null

    private var guiInventory: Inventory = Bukkit.createInventory(this, rows * 9)

    override fun getInventory(): Inventory = guiInventory

    private val guiOwner: Player?
        get() = guiInventory.viewers.firstOrNull() as? Player

    fun setItem(slot: Int, item: (Player) -> ItemStack, action: ((Player) -> Unit)? = null) = apply {
        dynamicItem[slot] = item
        if (action != null) actions[slot] = action
    }

    fun setItems(vararg slots: Int, item: (Player) -> ItemStack, action: ((Player) -> Unit)? = null) = apply {
        slots.forEach { slot -> setItem(slot, item, action) }
    }

    fun global(block: PageScope.() -> Unit) = apply {
        val scope = PageScope()
        scope.block()
        globalScope = scope
    }

    fun page(block: PageScope.() -> Unit) = apply {
        val scope = PageScope()
        scope.block()
        pages.add(scope)
    }

    fun goToPage(index: Int) {
        if (index < 0 || index >= pages.size) return
        currentPage = index
        refresh()
    }

    fun onOpen(action: (Player) -> Unit) = apply { onOpenAction = action }
    fun onClose(action: (Player) -> Unit) = apply { onCloseAction = action }

    fun refresh() = apply {
        val player = guiOwner ?: return@apply
        guiInventory.clear()
        pageActions.clear()

        val ctx = PageContext(currentPage, pages.size, this)

        globalScope?.itemScopes?.forEach { scope ->
            scope.slots.forEach { slot ->
                val guiItem = scope.item(player, ctx)
                guiInventory.setItem(slot, guiItem.item)
                guiItem.action?.let { pageActions[slot] = it }
            }
        }

        if (pages.isNotEmpty()) {
            pages[currentPage].itemScopes.forEach { scope ->
                scope.slots.forEach { slot ->
                    val guiItem = scope.item(player, ctx)
                    guiInventory.setItem(slot, guiItem.item)
                    guiItem.action?.let { pageActions[slot] = it }
                }
            }
        }

        dynamicItem.forEach { (slot, item) ->
            guiInventory.setItem(slot, item(player))
        }
    }

    fun refreshEvery(ticks: Long) = apply {
        refreshTask = Bukkit.getScheduler().runTaskTimer(ALBCore.instance, Runnable {
            refresh()
        }, 0L, ticks)
    }

    fun open(player: Player) = apply {
        val parsedTitle = ColorUtil.parseWithPAPI(player, title)
        guiInventory = Bukkit.createInventory(this, rows * 9, parsedTitle)
        refresh()
        player.openInventory(guiInventory)
    }
}