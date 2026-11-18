package com.aayan.albcore.hooks

import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy

/** Registry behaviour without a server (PlaceholderAPI is not installed in tests). */
class PAPIHookTest {

    private inline fun <reified T> stub(crossinline answer: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            when (method.name) {
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "stub"
                else -> answer(method.name)
            }
        } as T

    private fun plugin(name: String): Plugin = stub { if (it == "getName") name else null }

    private val player: Player = stub { if (it == "getName") "Steve" else null }
    private val online: OfflinePlayer = stub { if (it == "getPlayer") player else null }
    private val offline: OfflinePlayer = stub { null }

    private val skills = plugin("ALBSkills")
    private val chat = plugin("ALBChat")

    @AfterEach
    fun cleanup() {
        PAPIHook.unregisterAll(skills)
        PAPIHook.unregisterAll(chat)
    }

    @Test
    fun `shared and per-plugin identifiers`() {
        PAPIHook.register(skills, "level") { "12" }
        PAPIHook.register(skills, "albskills", "total") { p -> "${p.name}:90" }

        assertEquals("12", PAPIHook.resolve("alb", online, "level"))
        assertEquals("Steve:90", PAPIHook.resolve("albskills", online, "TOTAL"))
        assertNull(PAPIHook.resolve("albskills", online, "missing"))
        assertNull(PAPIHook.resolve("albskills", offline, "total"), "offline players get no value")
        assertTrue(PAPIHook.registeredPlaceholders().containsAll(setOf("alb_level", "albskills_total")))
    }

    @Test
    fun `unregisterAll only removes that plugin's placeholders`() {
        PAPIHook.register(skills, "albskills", "total") { "1" }
        PAPIHook.register(chat, "albchat", "muted") { "no" }
        PAPIHook.unregisterAll(skills)
        assertNull(PAPIHook.resolve("albskills", online, "total"))
        assertEquals("no", PAPIHook.resolve("albchat", online, "muted"))
        assertFalse(PAPIHook.registeredPlaceholders().contains("albskills_total"))
    }

    @Test
    fun `rejects identifiers PlaceholderAPI cannot route`() {
        assertThrows<IllegalArgumentException> { PAPIHook.register(skills, "alb_skills", "total") { "" } }
        assertThrows<IllegalArgumentException> { PAPIHook.register(skills, "albskills", "has space") { "" } }
        assertThrows<IllegalArgumentException> { PAPIHook.register(skills, "", "x") { "" } }
    }

    @Test
    fun `parse is a no-op without PlaceholderAPI`() {
        assertEquals("Hi %player_name%", PAPIHook.parse(online, "Hi %player_name%"))
    }
}
