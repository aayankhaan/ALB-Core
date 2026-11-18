package com.aayan.albcore.gui

import com.aayan.albcore.ALBCore
import com.aayan.albcore.utils.ColorUtil
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask

/**
 * A chest GUI (1-6 rows) with per-player items, pages, click actions and optional item-input slots.
 * Chest GUIs work for Bedrock players too (Geyser shows them as a chest).
 *
 * One GuiBuilder is one player's view: build a new one each time you open a menu.
 * Use it from the main thread only.
 *
 * ```
 * GuiBuilder("&8Shop", 3)
 *     .global { item(18) { _, ctx -> GuiItem(prevButton) { _, c -> c.previousPage() } } }
 *     .page { item(10) { player, _ -> GuiItem(ItemBuilder(Material.DIAMOND).name("&bDiamond").build()) { p, _ -> buy(p) } } }
 *     .open(player)
 * ```
 */
class GuiBuilder(private val title: String, private val rows: Int) : InventoryHolder {

    init {
        require(rows in 1..6) { "A chest GUI has 1 to 6 rows (got $rows)" }
    }

    /** Number of slots (rows * 9). */
    val size: Int get() = rows * 9

    private val actions = mutableMapOf<Int, (Player) -> Unit>()
    private val dynamicItems = mutableMapOf<Int, (Player) -> ItemStack>()
    private val pages = mutableListOf<PageScope>()
    private var globalScope: PageScope? = null
    private val pageActions = mutableMapOf<Int, (Player, PageContext) -> Unit>()

    internal val interactiveSlots = linkedSetOf<Int>()
    private val slotFilters = mutableMapOf<Int, (ItemStack) -> Boolean>()

    private var refreshIntervalTicks = 0L
    private var refreshTask: BukkitTask? = null
    private var onOpenAction: ((Player) -> Unit)? = null
    private var onCloseAction: ((Player) -> Unit)? = null

    private var guiInventory: Inventory? = null

    /** The player this GUI was last opened for. */
    var viewer: Player? = null
        private set

    /** Index of the page being shown (0-based). */
    var currentPage: Int = 0
        private set

    val totalPages: Int get() = pages.size

    override fun getInventory(): Inventory =
        guiInventory ?: Bukkit.createInventory(this, size).also { guiInventory = it }

    // ---------------------------------------------------------------- building

    /** A fixed slot with an item rebuilt for the viewer on every refresh, and an optional click action. */
    fun setItem(slot: Int, item: (Player) -> ItemStack, action: ((Player) -> Unit)? = null) = apply {
        checkSlot(slot)
        dynamicItems[slot] = item
        if (action != null) actions[slot] = action else actions.remove(slot)
    }

    fun setItems(vararg slots: Int, item: (Player) -> ItemStack, action: ((Player) -> Unit)? = null) = apply {
        slots.forEach { slot -> setItem(slot, item, action) }
    }

    /** Items shown on every page (navigation bars, borders). */
    fun global(block: PageScope.() -> Unit) = apply {
        globalScope = PageScope().apply(block)
    }

    /** Adds a page. Pages are shown one at a time; switch with [goToPage] or [PageContext]. */
    fun page(block: PageScope.() -> Unit) = apply {
        pages += PageScope().apply(block)
    }

    fun onOpen(action: (Player) -> Unit) = apply { onOpenAction = action }

    fun onClose(action: (Player) -> Unit) = apply { onCloseAction = action }

    /**
     * Lets the player put items into and take items out of [slots]. With [filter], only items it accepts
     * can be placed. Everything else in the GUI stays locked.
     */
    fun allowItemPlacement(vararg slots: Int, filter: ((ItemStack) -> Boolean)? = null) = apply {
        slots.forEach { slot ->
            checkSlot(slot)
            interactiveSlots += slot
            if (filter != null) slotFilters[slot] = filter
        }
    }

    fun allowItemPlacement(slots: IntRange, filter: ((ItemStack) -> Boolean)? = null) =
        allowItemPlacement(*slots.toList().toIntArray(), filter = filter)

    /** Refreshes the GUI every [ticks] ticks while it is open (20 ticks = 1 second). */
    fun refreshEvery(ticks: Long) = apply {
        require(ticks > 0) { "ticks must be greater than 0" }
        refreshIntervalTicks = ticks
        if (isOpen()) startRefreshTask()
    }

    // ---------------------------------------------------------------- runtime

    /** Opens the GUI for [player], rendering the current page. */
    fun open(player: Player) = apply {
        val previous = viewer
        if (previous != null && previous != player && isOpen()) {
            ALBCore.instance.logger.warning(
                "A GuiBuilder ('$title') was opened for ${player.name} while ${previous.name} still had it open. " +
                    "Create one GuiBuilder per player."
            )
        }
        guiInventory = Bukkit.createInventory(this, size, ColorUtil.parseWithPAPI(player, title))
        viewer = player
        player.openInventory(guiInventory!!)
    }

    /** Closes the GUI if its viewer still has it open. */
    fun close() {
        val player = viewer ?: return
        if (isOpen()) player.closeInventory()
    }

    /** True while the viewer is online and looking at this GUI. */
    fun isOpen(): Boolean {
        val player = viewer ?: return false
        val current = guiInventory ?: return false
        return player.isOnline && player.openInventory.topInventory === current
    }

    fun goToPage(index: Int) {
        if (index < 0 || index >= pages.size) return
        currentPage = index
        refresh()
    }

    /** Re-renders every item for the viewer. Items in item-input slots are kept. */
    fun refresh() = apply {
        val player = viewer ?: return@apply
        val inv = inventory
        val kept = interactiveSlots.associateWith { inv.getItem(it) }
        inv.clear()
        kept.forEach { (slot, item) -> if (item != null) inv.setItem(slot, item) }

        // Actions are rebuilt on every refresh, so a page never keeps the previous page's buttons.
        pageActions.clear()
        val context = PageContext(currentPage, pages.size, this)
        globalScope?.let { render(it, player, context, inv) }
        pages.getOrNull(currentPage)?.let { render(it, player, context, inv) }
        dynamicItems.forEach { (slot, item) -> inv.setItem(slot, item(player)) }
    }

    fun getItems(vararg slots: Int): List<ItemStack> = slots.toList().mapNotNull { inventory.getItem(it) }

    fun getItems(slots: IntRange): List<ItemStack> = slots.mapNotNull { inventory.getItem(it) }

    fun clearItems(range: IntRange) = apply {
        range.forEach { inventory.setItem(it, null) }
    }

    /** Puts [item] into the first empty slot of [range]. Returns false if there was no empty slot. */
    fun addItem(item: ItemStack, range: IntRange = 0 until size): Boolean {
        for (slot in range) {
            val current = inventory.getItem(slot)
            if (current == null || current.isEmpty) {
                inventory.setItem(slot, item)
                return true
            }
        }
        return false
    }

    /** Adds every item with [addItem]; returns the items that did not fit. */
    fun addItems(items: Collection<ItemStack>, range: IntRange = 0 until size): List<ItemStack> =
        items.filterNot { addItem(it, range) }

    // ---------------------------------------------------------------- listener hooks

    internal fun handleOpen(player: Player) {
        viewer = player
        refresh()
        onOpenAction?.invoke(player)
        startRefreshTask()
    }

    internal fun handleClose(player: Player) {
        stopRefreshTask()
        onCloseAction?.invoke(player)
    }

    internal fun handleClick(slot: Int, player: Player) {
        actions[slot]?.invoke(player)
        pageActions[slot]?.invoke(player, PageContext(currentPage, pages.size, this))
    }

    /** True if [item] may be placed into the item-input [slot]. */
    internal fun accepts(slot: Int, item: ItemStack): Boolean =
        slot in interactiveSlots && slotFilters[slot]?.invoke(item) != false

    /**
     * Moves as much of [item] as fits into the item-input slots (merging stacks first, then empty slots).
     * Returns what is left, or null if everything was moved.
     */
    internal fun insertIntoInteractiveSlots(item: ItemStack): ItemStack? {
        val inv = inventory
        val remaining = item.clone()
        val maxStack = minOf(remaining.maxStackSize, inv.maxStackSize)

        for (slot in interactiveSlots) {
            if (remaining.amount <= 0) break
            if (!accepts(slot, remaining)) continue
            val existing = inv.getItem(slot) ?: continue
            if (existing.isEmpty || !existing.isSimilar(remaining)) continue
            val moved = minOf(maxStack - existing.amount, remaining.amount)
            if (moved <= 0) continue
            existing.amount += moved
            inv.setItem(slot, existing)
            remaining.amount -= moved
        }
        for (slot in interactiveSlots) {
            if (remaining.amount <= 0) break
            if (!accepts(slot, remaining)) continue
            val existing = inv.getItem(slot)
            if (existing != null && !existing.isEmpty) continue
            val moved = minOf(maxStack, remaining.amount)
            inv.setItem(slot, remaining.clone().apply { amount = moved })
            remaining.amount -= moved
        }
        return remaining.takeIf { it.amount > 0 }
    }

    internal fun stopRefreshTask() {
        refreshTask?.cancel()
        refreshTask = null
    }

    private fun startRefreshTask() {
        stopRefreshTask()
        if (refreshIntervalTicks <= 0) return
        refreshTask = Bukkit.getScheduler().runTaskTimer(ALBCore.instance, Runnable {
            if (isOpen()) refresh() else stopRefreshTask()
        }, refreshIntervalTicks, refreshIntervalTicks)
    }

    private fun render(scope: PageScope, player: Player, context: PageContext, inv: Inventory) {
        scope.itemScopes.forEach { itemScope ->
            itemScope.slots.forEach { slot ->
                val guiItem = itemScope.item(player, context)
                inv.setItem(slot, guiItem.item)
                guiItem.action?.let { pageActions[slot] = it }
            }
        }
    }

    private fun checkSlot(slot: Int) {
        require(slot in 0 until size) { "Slot $slot is outside this ${rows}-row GUI (0..${size - 1})" }
    }
}
