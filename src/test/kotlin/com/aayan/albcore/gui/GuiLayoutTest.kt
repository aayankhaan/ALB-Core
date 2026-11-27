package com.aayan.albcore.gui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuiLayoutTest {

    @Test
    fun `bottom centre of every size`() {
        assertEquals(listOf(4, 13, 22, 31, 40, 49), (1..6).map { GuiLayout.bottom(it) })
    }

    @Test
    fun `inner slots skip the edge`() {
        assertEquals(emptyList<Int>(), GuiLayout.inner(2))
        assertEquals((10..16).toList(), GuiLayout.inner(3))
        assertEquals(28, GuiLayout.inner(6).size)
        assertTrue(GuiLayout.inner(6).none { GuiLayout.isEdge(it, 6) })
    }

    @Test
    fun `edges and corners`() {
        assertEquals(listOf(0, 8, 45, 53), GuiLayout.corners(6))
        assertTrue(GuiLayout.isEdge(9, 6))
        assertTrue(GuiLayout.isEdge(17, 6))
        assertFalse(GuiLayout.isEdge(10, 6))
        assertTrue(GuiLayout.isBottomCorner(45, 6))
        assertFalse(GuiLayout.isBottomCorner(0, 6))
        assertEquals(26, (0 until 54).count { GuiLayout.isFilled(GuiFill.EDGES, it, 6) })
        assertEquals(0, (0 until 54).count { GuiLayout.isFilled(GuiFill.NONE, it, 6) })
    }

    @Test
    fun `page count is at least one`() {
        assertEquals(1, GuiLayout.pageCount(0, 28))
        assertEquals(1, GuiLayout.pageCount(28, 28))
        assertEquals(2, GuiLayout.pageCount(29, 28))
        assertEquals(3, GuiLayout.pageCount(3, 0))
    }

    @Test
    fun `centered spreads few entries over the middle row`() {
        val slots = GuiLayout.inner(5) // rows 1-3, columns 1-7
        assertEquals(listOf(22), GuiLayout.centered(slots, 1).take(1))
        assertEquals(listOf(20, 22, 24), GuiLayout.centered(slots, 3).take(3))
        // Every slot comes back once, used ones first.
        assertEquals(slots.sorted(), GuiLayout.centered(slots, 3).sorted())
    }

    @Test
    fun `centered uses two rows when one is not enough`() {
        val slots = GuiLayout.inner(5)
        val used = GuiLayout.centered(slots, 10).take(10)
        assertEquals(setOf(1, 2), used.map { it / 9 }.toSet())
        assertEquals(5, used.count { it / 9 == 1 })
    }

    @Test
    fun `centered with a full list keeps the order`() {
        val slots = GuiLayout.inner(4)
        assertEquals(slots, GuiLayout.centered(slots, slots.size))
    }
}
