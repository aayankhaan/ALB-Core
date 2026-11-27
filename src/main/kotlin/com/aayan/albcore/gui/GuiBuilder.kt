package com.aayan.albcore.gui

import com.aayan.albcore.ALBCore
import com.aayan.albcore.utils.ColorUtil
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
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
 *
 * Themed menus (see [GuiTheme], [GuiLayout]):
 * ```
 * GuiBuilder("&8ʜᴏᴍᴇꜱ", 6)
 *     .frame()
 *     .header(headerItem)
 *     .list(GuiLayout.inner(6), entries = { homesOf(player) }) { _, home ->
 *         GuiItem.button(homeIcon(home)) { click -> if (click.isRight) manage(click.player, home) else teleport(click.player, home) }
 *     }
 *     .pageButtons()
 *     .backOrClose(back)
 *     .pagedTitle()
 *     .open(player)
 * ```
 *
 * Every click action is protected by a per-player cooldown (ALBCore config `gui.click-cooldown-ms`,
 * see [clickCooldown]) and ignores the second half of a double-click.
 */
class GuiBuilder(private val title: String, val rows: Int) : InventoryHolder {

    init {
        require(rows in 1..6) { "A chest GUI has 1 to 6 rows (got $rows)" }
    }

    /** Number of slots (rows * 9). */
    val size: Int get() = rows * 9

    private val actions = mutableMapOf<Int, (Player) -> Unit>()
    private val dynamicItems = mutableMapOf<Int, (Player) -> ItemStack>()
    private val background = mutableMapOf<Int, (Player) -> ItemStack>()
    private val pages = mutableListOf<PageScope>()
    private var globalScope: PageScope? = null
    private val pageActions = mutableMapOf<Int, (Player, PageContext) -> Unit>()
    private var listArea: ListArea? = null
    private var previousSlot = -1
    private var nextSlot = -1
    private var showPageInTitle = false

    internal val interactiveSlots = linkedSetOf<Int>()
    private val slotFilters = mutableMapOf<Int, (ItemStack) -> Boolean>()

    private var refreshIntervalTicks = 0L
    private var refreshTask: BukkitTask? = null
    private var onOpenAction: ((Player) -> Unit)? = null
    private var onCloseAction: ((Player) -> Unit)? = null
    private var cooldownMillis: Long? = null

    /** True while the GUI re-opens itself for a new title (page change): open/close hooks are skipped. */
    private var switching = false

    private var guiInventory: Inventory? = null

    /** The player this GUI was last opened for. */
    var viewer: Player? = null
        private set

    /** Index of the page being shown (0-based). */
    var currentPage: Int = 0
        private set

    /** Pages added with [page], or the pages of the [list] area, whichever is more. */
    val totalPages: Int get() = maxOf(pages.size, listArea?.pageCount ?: 0)

    /** The click being handled right now: set while a click action runs, null otherwise. */
    var click: GuiClick? = null
        private set

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

    /**
     * A button: [item] rebuilt for the viewer on every refresh; a click plays the theme click sound
     * (unless [sound] is false) and runs [action] with the whole [GuiClick].
     */
    fun button(slot: Int, item: (Player) -> ItemStack, sound: Boolean = true, action: (GuiClick) -> Unit) =
        setItem(slot, item) { _ ->
            val current = click ?: return@setItem
            if (sound) GuiSounds.click(current.player)
            action(current)
        }

    /** A button with a fixed item. */
    fun button(slot: Int, item: ItemStack, sound: Boolean = true, action: (GuiClick) -> Unit) =
        button(slot, { item }, sound, action)

    /** A decoration (no click action) in the header slot (top centre). */
    fun header(item: (Player) -> ItemStack) = setItem(GuiLayout.HEADER, item)

    fun header(item: ItemStack) = header { item }

    /**
     * Fills the edge ([GuiFill.EDGES], the default) or every slot ([GuiFill.ALL]) with the theme's frame
     * panes. They sit underneath everything else, so buttons and list entries replace them.
     */
    fun frame(fill: GuiFill = GuiFill.EDGES) = apply {
        background.clear()
        for (slot in 0 until size) {
            if (GuiLayout.isFilled(fill, slot, rows)) background[slot] = { GuiTheme.filler(slot, rows) }
        }
    }

    /** The theme's back button at [slot] (default bottom centre), or its close button when [back] is null. */
    fun backOrClose(back: ((Player) -> Unit)?, slot: Int = GuiLayout.bottom(rows)) = apply {
        if (back != null) button(slot, { GuiTheme.button("back") }) { back(it.player) }
        else button(slot, { GuiTheme.button("close") }) { it.player.closeInventory() }
    }

    /**
     * The theme's previous / next page buttons (default: either side of the bottom centre). Each one
     * only shows when there is a page to go to; otherwise the slot shows the frame.
     */
    fun pageButtons(previous: Int = GuiLayout.bottom(rows) - 1, next: Int = GuiLayout.bottom(rows) + 1) = apply {
        checkSlot(previous)
        checkSlot(next)
        previousSlot = previous
        nextSlot = next
    }

    /**
     * A list area: [entries] spread over [slots], with as many pages as needed (switch with
     * [pageButtons], [goToPage] or [PageContext]). [entries] is read again on every [refresh], so a
     * changed list shows at once. [render] builds the item of one entry for the viewer.
     */
    fun <T> list(
        slots: List<Int>,
        layout: ListLayout = ListLayout.FILL,
        entries: () -> List<T>,
        render: (Player, T) -> GuiItem,
    ) = apply {
        slots.forEach(::checkSlot)
        @Suppress("UNCHECKED_CAST")
        val renderAny = render as (Player, Any?) -> GuiItem
        listArea = ListArea(slots, layout, entries, renderAny)
    }

    /** A list area with a fixed list of entries. */
    fun <T> list(slots: List<Int>, entries: List<T>, layout: ListLayout = ListLayout.FILL, render: (Player, T) -> GuiItem) =
        list(slots, layout, { entries }, render)

    /** Shows "title (page/pages)" (lang `gui.paged-title`) when there is more than one page. */
    fun pagedTitle(enabled: Boolean = true) = apply { showPageInTitle = enabled }

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
     * Ignores clicks of the same player that come less than [millis] after their last accepted click
     * (0 = no cooldown). Default: ALBCore config `gui.click-cooldown-ms` (100).
     */
    fun clickCooldown(millis: Long) = apply { cooldownMillis = millis.coerceAtLeast(0) }

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
        listArea?.update()
        currentPage = currentPage.coerceIn(0, maxOf(0, totalPages - 1))
        guiInventory = Bukkit.createInventory(this, size, ColorUtil.parseWithPAPI(player, renderedTitle()))
        viewer = player
        player.openInventory(guiInventory!!)
    }

    /** Opens the GUI for [player] at [page] (0-based; clamped to the pages that exist). */
    fun open(player: Player, page: Int) = apply {
        currentPage = page.coerceAtLeast(0)
        open(player)
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

    /**
     * Shows page [index] (ignored when it does not exist). When the title shows the page number, the
     * GUI re-opens with the new title (items in input slots are kept; open/close hooks do not run).
     */
    fun goToPage(index: Int) {
        if (index < 0 || index >= totalPages) return
        val oldTitle = renderedTitle()
        currentPage = index
        val player = viewer
        if (player != null && isOpen() && renderedTitle() != oldTitle) reopen(player) else refresh()
    }

    /** Page information for the current page. */
    fun pageContext(): PageContext = PageContext(currentPage, totalPages, this)

    /** Re-renders every item for the viewer. Items in item-input slots are kept. */
    fun refresh() = apply {
        val player = viewer ?: return@apply
        val inv = inventory
        val kept = interactiveSlots.associateWith { inv.getItem(it) }
        inv.clear()
        kept.forEach { (slot, item) -> if (item != null) inv.setItem(slot, item) }

        // Actions are rebuilt on every refresh, so a page never keeps the previous page's buttons.
        pageActions.clear()
        listArea?.update()
        if (totalPages > 0 && currentPage >= totalPages) currentPage = totalPages - 1
        val context = pageContext()
        background.forEach { (slot, item) -> if (slot !in interactiveSlots) inv.setItem(slot, item(player)) }
        globalScope?.let { render(it, player, context, inv) }
        pages.getOrNull(currentPage)?.let { render(it, player, context, inv) }
        dynamicItems.forEach { (slot, item) -> inv.setItem(slot, item(player)) }
        listArea?.render(player, context, inv, pageActions)
        renderPageButtons(context, inv)
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

    companion object {
        /**
         * Redraws every open ALBCore GUI whose viewer matches [filter], e.g. every team member after a team
         * setting changed, so two players looking at the same menu see the same. Main thread only.
         */
        fun refreshOpen(filter: (Player) -> Boolean) {
            for (player in Bukkit.getOnlinePlayers()) {
                val gui = player.openInventory.topInventory.getHolder(false) as? GuiBuilder ?: continue
                if (filter(player)) runCatching { gui.refresh() }
            }
        }
    }

    // ---------------------------------------------------------------- listener hooks

    internal fun handleOpen(player: Player) {
        viewer = player
        refresh()
        if (!switching) onOpenAction?.invoke(player)
        startRefreshTask()
    }

    internal fun handleClose(player: Player) {
        if (switching) return
        stopRefreshTask()
        onCloseAction?.invoke(player)
    }

    internal fun handleClick(slot: Int, player: Player, type: ClickType) {
        // The second half of a double-click arrives as its own click: never act on it.
        if (type == ClickType.DOUBLE_CLICK) return
        // Take both actions first: an action may refresh the GUI, which rebuilds the page actions.
        val action = actions[slot]
        val pageAction = pageActions[slot]
        if (action == null && pageAction == null) return
        if (!GuiClicks.accept(player, cooldown())) return
        click = GuiClick(player, type, slot, this)
        try {
            action?.invoke(player)
            pageAction?.invoke(player, pageContext())
        } finally {
            click = null
        }
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

    private fun cooldown(): Long =
        cooldownMillis ?: runCatching { ALBCore.instance.coreConfig.gui.clickCooldownMs }.getOrDefault(100L)

    /** The title with {page} / {pages} filled, plus the page number when [pagedTitle] is on. */
    private fun renderedTitle(): String {
        val pageCount = maxOf(1, totalPages)
        val text = title.replace("{page}", "${currentPage + 1}").replace("{pages}", "$pageCount")
        return if (showPageInTitle) GuiTheme.pagedTitle(text, currentPage, pageCount) else text
    }

    /** Opens a new inventory with the current title, keeping input-slot items and skipping open/close hooks. */
    private fun reopen(player: Player) {
        val old = guiInventory
        val fresh = Bukkit.createInventory(this, size, ColorUtil.parseWithPAPI(player, renderedTitle()))
        if (old != null) {
            for (slot in interactiveSlots) {
                fresh.setItem(slot, old.getItem(slot))
                old.setItem(slot, null)
            }
        }
        guiInventory = fresh
        switching = true
        try {
            player.openInventory(fresh)
        } finally {
            switching = false
        }
    }

    private fun renderPageButtons(context: PageContext, inv: Inventory) {
        if (previousSlot >= 0 && context.hasPreviousPage) {
            inv.setItem(previousSlot, GuiTheme.button("previous", "page" to currentPage, "pages" to totalPages))
            pageActions[previousSlot] = { player, c -> GuiSounds.click(player); c.previousPage() }
        }
        if (nextSlot >= 0 && context.hasNextPage) {
            inv.setItem(nextSlot, GuiTheme.button("next", "page" to currentPage + 2, "pages" to totalPages))
            pageActions[nextSlot] = { player, c -> GuiSounds.click(player); c.nextPage() }
        }
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

    /** The entries of [list], cut into pages of [slots]. */
    private class ListArea(
        val slots: List<Int>,
        val layout: ListLayout,
        val entries: () -> List<Any?>,
        val renderEntry: (Player, Any?) -> GuiItem,
    ) {
        private var snapshot: List<Any?> = emptyList()

        val pageCount: Int get() = GuiLayout.pageCount(snapshot.size, slots.size)

        fun update() {
            snapshot = entries()
        }

        fun render(player: Player, context: PageContext, inv: Inventory, actions: MutableMap<Int, (Player, PageContext) -> Unit>) {
            if (slots.isEmpty()) return
            val shown = snapshot.drop(context.currentPage * slots.size).take(slots.size)
            val at = if (layout == ListLayout.CENTERED) GuiLayout.centered(slots, shown.size) else slots
            shown.forEachIndexed { i, entry ->
                val item = renderEntry(player, entry)
                inv.setItem(at[i], item.item)
                item.action?.let { actions[at[i]] = it }
            }
        }
    }
}
