package com.aayan.albcore.logging

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

class DiscordLoggerTest {

    @AfterEach
    fun cleanup() = DiscordLogger.clear()

    @Test
    fun `payloads never allow mentions and respect Discord limits`() {
        DiscordLogger.register("test", "https://discord.com/api/webhooks/1/token", username = "Logs", avatarUrl = "https://x/a.png")
        val sender = DiscordLogger.on("test")

        val text = sender.textPayload("@everyone " + "x".repeat(3000))
        assertEquals(mapOf("parse" to emptyList<String>()), text["allowed_mentions"])
        assertEquals(2000, (text["content"] as String).length)
        assertEquals("Logs", text["username"])
        assertEquals("https://x/a.png", text["avatar_url"])

        val embed = sender.embedPayload(EmbedBuilder().apply {
            title = "t".repeat(300)
            description = "d"
            color = DiscordColor.RED
            repeat(30) { field("n$it", "v") }
        })
        @Suppress("UNCHECKED_CAST")
        val first = (embed["embeds"] as List<Map<String, Any>>).single()
        assertEquals(256, (first["title"] as String).length)
        assertEquals(DiscordColor.RED, first["color"])
        assertEquals(25, (first["fields"] as List<*>).size)
    }

    @Test
    fun `unknown webhook sends nothing`() {
        assertFalse(DiscordLogger.isRegistered("missing"))
        assertFalse(DiscordLogger.on("missing").send("hi").get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `only https webhooks are accepted`() {
        assertThrows<IllegalArgumentException> { DiscordLogger.register("x", "http://insecure") }
        DiscordLogger.register("ok", "https://discord.com/api/webhooks/2/t")
        assertTrue(DiscordLogger.isRegistered("ok"))
        assertFalse(DiscordLogger.registeredNames().isEmpty())
        assertFalse(DiscordLogger.on("ok").toString().contains("api/webhooks"))
    }
}
