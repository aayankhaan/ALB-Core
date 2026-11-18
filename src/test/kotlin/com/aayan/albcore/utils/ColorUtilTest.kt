package com.aayan.albcore.utils

import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ColorUtilTest {

    @Test
    fun `converts legacy colour and format codes`() {
        assertEquals("<red>Hi <bold>there", ColorUtil.legacyToMiniMessage("&cHi &lthere"))
        assertEquals("<green>upper", ColorUtil.legacyToMiniMessage("&Aupper"))
        assertEquals("<reset>x", ColorUtil.legacyToMiniMessage("&rx"))
    }

    @Test
    fun `converts both hex formats`() {
        assertEquals("<#ff8800>a", ColorUtil.legacyToMiniMessage("&#ff8800a"))
        assertEquals("<#ff8800>b", ColorUtil.legacyToMiniMessage("&x&f&f&8&8&0&0b"))
    }

    @Test
    fun `leaves other ampersands and MiniMessage alone`() {
        assertEquals("Tom & Jerry", ColorUtil.legacyToMiniMessage("Tom & Jerry"))
        assertEquals("<gradient:red:blue>x</gradient>", ColorUtil.legacyToMiniMessage("<gradient:red:blue>x</gradient>"))
    }

    @Test
    fun `parse produces coloured components`() {
        val component = ColorUtil.parse("&cHello")
        assertEquals("Hello", ColorUtil.toPlainText("&cHello"))
        assertEquals(NamedTextColor.RED, component.children().firstOrNull()?.color() ?: component.color())
        val hex = ColorUtil.parse("&#123456X")
        assertEquals(TextColor.color(0x123456), hex.children().firstOrNull()?.color() ?: hex.color())
    }

    @Test
    fun `escape keeps player text literal`() {
        val input = "<click:run_command:/op me>click"
        assertEquals(input, ColorUtil.toPlainText(ColorUtil.escape(input)))
    }
}
