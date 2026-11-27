package com.aayan.albcore.gui

/** How a list area places its entries: in slot order, or spread out and centred (few entries look tidy). */
enum class ListLayout { FILL, CENTERED }

/** Which slots get the theme's frame panes: none, the edge of the menu, or every slot. */
enum class GuiFill { NONE, EDGES, ALL }

/**
 * Slot maths for chest menus (9 slots per row, slot 0 = top left). Layout every ALB menu follows:
 *  - slot 4 (top centre): what the menu is about (header).
 *  - bottom centre ([bottom]): back, or close when there is nothing to go back to.
 *  - one slot left / right of the bottom centre: previous / next page.
 *  - the inner slots ([inner]) hold the content; the edge is the frame.
 */
object GuiLayout {

    /** Top-centre slot (the header). */
    const val HEADER = 4

    /** Bottom-centre slot of a menu with [rows] rows (back / close). */
    fun bottom(rows: Int): Int = rows * 9 - 5

    /** Inner slots (not on the edge) of a menu with [rows] rows, row by row. Empty for 1 and 2 rows. */
    fun inner(rows: Int): List<Int> = (1 until rows - 1).flatMap { row -> (1..7).map { col -> row * 9 + col } }

    /** True if [slot] is on the edge (top row, bottom row, left or right column) of a [rows]-row menu. */
    fun isEdge(slot: Int, rows: Int): Boolean {
        val size = rows * 9
        return slot < 9 || slot >= size - 9 || slot % 9 == 0 || slot % 9 == 8
    }

    /** The four corner slots: top left, top right, bottom left, bottom right. */
    fun corners(rows: Int): List<Int> = listOf(0, 8, rows * 9 - 9, rows * 9 - 1)

    /** True if [slot] is a bottom corner of a [rows]-row menu. */
    fun isBottomCorner(slot: Int, rows: Int): Boolean = slot == rows * 9 - 9 || slot == rows * 9 - 1

    /** True if [fill] puts a frame pane in [slot]. */
    fun isFilled(fill: GuiFill, slot: Int, rows: Int): Boolean = when (fill) {
        GuiFill.NONE -> false
        GuiFill.ALL -> true
        GuiFill.EDGES -> isEdge(slot, rows)
    }

    /** Number of pages for [count] entries shown [perPage] at a time (at least 1). */
    fun pageCount(count: Int, perPage: Int): Int = maxOf(1, (count + maxOf(1, perPage) - 1) / maxOf(1, perPage))

    /**
     * [count] entries spread over as few rows of [slots] as needed: the rows are centred top to bottom,
     * entries are balanced between rows and centred in each, with gaps when there are few. Returns the
     * slots in entry order (the unused slots follow, so the result always has every slot).
     */
    fun centered(slots: List<Int>, count: Int): List<Int> {
        val rows = slots.groupBy { it / 9 }.toSortedMap().values.map { it.sorted() }
        if (rows.isEmpty() || count >= slots.size) return slots
        val width = rows.maxOf { it.size }
        val used = ((count + width - 1) / width).coerceIn(1, rows.size)
        val first = (rows.size - used) / 2
        val out = ArrayList<Int>()
        for (r in 0 until used) {
            val row = rows[first + r]
            val inRow = count / used + if (r < count % used) 1 else 0
            out += spread(row.size, inRow, compact = used > 1).map { row[it] }
        }
        return out + slots.filter { it !in out }
    }

    /**
     * Which of [width] places [n] entries take. One row: spaced out when they fit. Several rows
     * ([compact]): side by side and centred, with a gap in the middle when [n] and [width] differ in
     * evenness, so the rows line up.
     */
    private fun spread(width: Int, n: Int, compact: Boolean): List<Int> = when {
        n <= 0 -> emptyList()
        n >= width -> (0 until width).toList()
        !compact && 2 * n - 1 <= width -> (0 until n).map { (width - (2 * n - 1)) / 2 + it * 2 }
        (width - n) % 2 == 0 -> (0 until n).map { (width - n) / 2 + it }
        else -> {
            val start = (width - n - 1) / 2
            (start until start + n + 1).filter { it != width / 2 }
        }
    }
}
