package com.aayan.albcore.lang

import com.aayan.albcore.input.ChatInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LangTextTest {

    @Test
    fun `mode tags`() {
        assertEquals(MessageMode.ACTIONBAR to "&cNo!", LangText.stripMode("[actionbar] &cNo!"))
        assertEquals(MessageMode.TITLE to "Hi", LangText.stripMode("  [TITLE]Hi"))
        assertEquals(MessageMode.CHAT to "plain", LangText.stripMode("plain"))
        assertEquals(MessageMode.OFF to "", LangText.stripMode("[off]"))
        assertEquals("[bossbar] x", LangText.withMode(MessageMode.BOSSBAR, "x"))
        assertEquals("x", LangText.withMode(MessageMode.CHAT, "x"))
        assertEquals(MessageMode.ACTIONBAR, MessageMode.CHAT.next())
        assertEquals(MessageMode.CHAT, MessageMode.OFF.next())
    }

    @Test
    fun `fill escapes values and keeps double braces`() {
        val text = LangText.fill("{prefix}Hi {player}, type {{name}}", "&7» ", arrayOf("player" to "<red>Bob"))
        assertEquals("&7» Hi \\<red>Bob, type {name}", text)
    }

    @Test
    fun `list placeholders become lines`() {
        val lines = LangText.fillLines(
            listOf("&7Needs:", "{needs}", "&8{count} left"),
            "",
            arrayOf("needs" to listOf("&f10 stone", "&f5 logs"), "count" to 2),
        )
        assertEquals(listOf("&7Needs:", "&f10 stone", "&f5 logs", "&82 left"), lines)
        assertEquals(listOf("a"), LangText.fillLines(listOf("a", "{none}"), "", arrayOf("none" to emptyList<String>())))
    }

    @Test
    fun `cancel words`() {
        assertTrue(ChatInput.isCancelWord(" Cancel ", listOf("cancel")))
        assertTrue(ChatInput.isCancelWord("stop", listOf("cancel", "stop")))
        assertFalse(ChatInput.isCancelWord("cancel please", listOf("cancel")))
        assertFalse(ChatInput.isCancelWord("", listOf("")))
    }
}
