package com.aayan.albcore.commands

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.command.CommandSender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy

class CommandBuilderTest {

    /** A console-like sender that records messages and has the given permissions. */
    private class TestSender(vararg permissions: String) {
        val messages = mutableListOf<String>()
        private val granted = permissions.toSet()

        val sender: CommandSender = Proxy.newProxyInstance(
            CommandSender::class.java.classLoader,
            arrayOf(CommandSender::class.java),
        ) { _, method, args ->
            when (method.name) {
                "sendMessage" -> {
                    val message = args?.firstOrNull()
                    if (message is Component) messages += PlainTextComponentSerializer.plainText().serialize(message)
                    else if (message is String) messages += message
                    null
                }
                "hasPermission" -> (args?.firstOrNull() as? String)?.let { it in granted } ?: false
                "getName" -> "tester"
                "isOp" -> false
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                "toString" -> "TestSender"
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    Integer.TYPE -> 0
                    else -> null
                }
            }
        } as CommandSender
    }

    private fun command(block: CommandBuilder.() -> Unit) = CommandBuilder().apply(block)

    @Test
    fun `parses typed arguments`() {
        var result: List<Any?> = emptyList()
        val cmd = command {
            arg("name", ArgType.STRING)
            arg("count", ArgType.INT)
            arg("price", ArgType.DOUBLE)
            arg("enabled", ArgType.BOOLEAN)
            action { _, args -> result = listOf(args.string("name"), args.int("count"), args.double("price"), args.boolean("enabled")) }
        }
        cmd.run(TestSender().sender, arrayOf("sword", "3", "2.5", "yes"))
        assertEquals(listOf("sword", 3, 2.5, true), result)
    }

    @Test
    fun `reports invalid and missing arguments`() {
        val sender = TestSender()
        var ran = false
        val cmd = command {
            arg("count", ArgType.INT)
            arg("note", ArgType.STRING)
            action { _, _ -> ran = true }
        }
        cmd.run(sender.sender, arrayOf("abc"))
        cmd.run(sender.sender, arrayOf("5"))
        assertEquals(false, ran)
        assertEquals("Invalid whole number for 'count': abc", sender.messages[0])
        assertEquals("Missing arguments. Usage: <note>", sender.messages[1])
    }

    @Test
    fun `optional and greedy arguments`() {
        var seen: Pair<String?, String?> = null to null
        val cmd = command {
            arg("target", ArgType.STRING) { optional = true }
            arg("message", ArgType.TEXT) { optional = true }
            action { _, args -> seen = args.stringOrNull("target") to args.stringOrNull("message") }
        }
        cmd.run(TestSender().sender, emptyArray())
        assertEquals(null to null, seen)
        cmd.run(TestSender().sender, arrayOf("bob", "hello", "there", "friend"))
        assertEquals("bob" to "hello there friend", seen)
    }

    @Test
    fun `argument order rules are enforced`() {
        assertThrows<IllegalStateException> {
            command {
                arg("a", ArgType.STRING) { optional = true }
                arg("b", ArgType.STRING)
            }
        }
        assertThrows<IllegalStateException> {
            command {
                arg("a", ArgType.TEXT)
                arg("b", ArgType.STRING)
            }
        }
        assertThrows<IllegalArgumentException> {
            command {
                arg("a", ArgType.STRING)
                arg("a", ArgType.INT)
            }
        }
    }

    @Test
    fun `validators stop the action`() {
        val sender = TestSender()
        var level: Int? = null
        val cmd = command {
            arg("level", ArgType.INT) {
                validate({ _, value -> value in 1..100 }) { it.sendMessage("1-100 only") }
            }
            action { _, args -> level = args.int("level") }
        }
        cmd.run(sender.sender, arrayOf("500"))
        assertNull(level)
        assertEquals(listOf("1-100 only"), sender.messages)
        cmd.run(sender.sender, arrayOf("50"))
        assertEquals(50, level)
    }

    @Test
    fun `subcommands, aliases and permissions`() {
        val calls = mutableListOf<String>()
        val cmd = command {
            action { _, _ -> calls += "root" }
            subcommand("invite") {
                aliases = mutableListOf("inv")
                arg("player", ArgType.STRING)
                action { _, args -> calls += "invite:" + args.string("player") }
            }
            subcommand("delete") {
                permission = "island.delete"
                action { _, _ -> calls += "delete" }
            }
        }
        val member = TestSender()
        val admin = TestSender("island.delete")

        cmd.run(member.sender, emptyArray())
        cmd.run(member.sender, arrayOf("INV", "alex"))
        cmd.run(member.sender, arrayOf("delete"))
        cmd.run(admin.sender, arrayOf("delete"))
        cmd.run(member.sender, arrayOf("nope"))

        assertEquals(listOf("root", "invite:alex", "delete"), calls)
        assertEquals("You don't have permission to use this command.", member.messages[0])
        assertEquals("Usage: invite", member.messages[1], "hidden subcommands are not listed")
    }

    @Test
    fun `command without root action shows usage`() {
        val sender = TestSender()
        val cmd = command {
            subcommand("a") { action { _, _ -> } }
            subcommand("b") { action { _, _ -> } }
        }
        cmd.run(sender.sender, emptyArray())
        assertEquals(listOf("Usage: a/b"), sender.messages)
    }

    @Test
    fun `tab completion follows arguments and permissions`() {
        val cmd = command {
            subcommand("give") {
                arg("item", ArgType.STRING) { suggest { listOf("sword", "shield") } }
                arg("confirm", ArgType.BOOLEAN)
            }
            subcommand("admin") {
                permission = "x.admin"
                subcommand("reset") {}
            }
        }
        val member = TestSender().sender
        val admin = TestSender("x.admin").sender
        assertEquals(listOf("give"), cmd.tabComplete(member, arrayOf("")))
        assertEquals(listOf("give", "admin"), cmd.tabComplete(admin, arrayOf("")))
        assertEquals(listOf("sword", "shield"), cmd.tabComplete(member, arrayOf("give", "")))
        assertEquals(listOf("true", "false"), cmd.tabComplete(member, arrayOf("give", "sword", "")))
        assertEquals(emptyList<String>(), cmd.tabComplete(member, arrayOf("admin", "")))
        assertEquals(listOf("reset"), cmd.tabComplete(admin, arrayOf("admin", "")))
        assertTrue(cmd.tabComplete(member, emptyArray()).contains("give"))
    }
}
