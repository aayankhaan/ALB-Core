package com.aayan.albcore.menu

import com.aayan.albcore.gui.GuiFill
import com.aayan.albcore.gui.ListLayout
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.logging.Logger

class MenuDefTest {

    @TempDir
    lateinit var dir: File

    private val logger = Logger.getLogger("MenuDefTest")

    private fun def(yaml: String): MenuDef {
        val def = MenuDef("homes", File(dir, "homes.yml"), null, logger)
        def.read(YamlConfiguration().apply { loadFromString(yaml) })
        return def
    }

    @Test
    fun `reads items, locked looks and the list area`() {
        val def = def(
            """
            version: 2
            title: "&8Homes"
            rows: 6
            fill: all
            items:
              back:
                slot: 49
                item: ARROW
                name: "&#7DD3FC← Back"
                actions: ["[back]"]
              vip:
                slot: 50
                item: GOLD_INGOT
                name: "VIP"
                actions: ["[function] vip-homes"]
                permission: homes.vip
                locked:
                  hide: false
                  item: GRAY_DYE
                  name: "&8VIP only"
                  actions: ["[sound] entity.villager.no"]
            list:
              slots: "10-16,19-25"
              layout: centered
              look:
                entry:
                  name: "&f{name}"
                  lore: ["&7{world}"]
            """.trimIndent()
        )
        assertEquals("&8Homes", def.title)
        assertEquals(6, def.rows)
        assertEquals(GuiFill.ALL, def.fill)
        assertEquals(Material.ARROW, def.items.getValue("back").look.material)
        assertEquals(listOf("[back]"), def.items.getValue("back").actions)
        val vip = def.items.getValue("vip")
        assertEquals("homes.vip", vip.permission)
        assertFalse(vip.hideLocked)
        assertEquals(Material.GRAY_DYE, vip.locked.material)
        assertEquals(listOf("vip-homes"), vip.args("function"))
        assertEquals(listOf("entity.villager.no"), vip.args("sound", locked = true))
        assertEquals((10..16).toList() + (19..25).toList(), def.listSlots)
        assertEquals(ListLayout.CENTERED, def.listLayout)
        assertEquals("&f{name}", def.templates.getValue("entry").name)
        assertEquals(vip, def.itemAt(50))
        assertNull(def.itemAt(0))
    }

    @Test
    fun `bad values fall back`() {
        val def = def(
            """
            rows: 9
            fill: sideways
            items:
              x:
                item: NOT_A_BLOCK
            list:
              layout: zigzag
            """.trimIndent()
        )
        assertEquals(6, def.rows)
        assertEquals(GuiFill.EDGES, def.fill)
        assertEquals(Material.PAPER, def.items.getValue("x").look.material)
        assertEquals(-1, def.items.getValue("x").slot)
        assertEquals(ListLayout.FILL, def.listLayout)
    }

    @Test
    fun `a custom menu without a file starts empty`() {
        val def = MenuDef("nothing", File(dir, "nothing.yml"), null, logger)
        def.load()
        assertFalse(def.builtIn)
        assertTrue(def.items.isEmpty())
    }

    @Test
    fun `saves and reads back the same menu`() {
        val def = def(
            """
            title: "Shop"
            rows: 4
            command: shop
            items:
              buy:
                slot: 13
                item: EMERALD
                name: "&aBuy"
                lore: ["&7{price}"]
                actions: ["[function] buy", "[close]"]
            list:
              slots: "19-25"
            """.trimIndent()
        )
        def.save()
        val again = MenuDef("homes", File(dir, "homes.yml"), null, logger).apply { load() }
        assertEquals("Shop", again.title)
        assertEquals(4, again.rows)
        assertEquals("shop", again.command)
        assertEquals(listOf("[function] buy", "[close]"), again.items.getValue("buy").actions)
        assertEquals(listOf("&7{price}"), again.items.getValue("buy").look.lore)
        assertEquals((19..25).toList(), again.listSlots)
    }

    @Test
    fun `slot lists`() {
        assertEquals(listOf(10, 11, 12, 20), MenuDef.parseSlots("10-12, 20"))
        assertEquals(listOf(1, 3), MenuDef.parseSlots("1,x,3,5-2"))
        assertEquals(emptyList<Int>(), MenuDef.parseSlots(""))
        assertEquals("10-12,20", MenuDef.formatSlots(listOf(20, 12, 10, 11)))
        assertEquals("", MenuDef.formatSlots(emptyList()))
    }

    @Test
    fun `placeholders and list lines`() {
        val placeholders = listOf("name" to "Base", "warps" to listOf("&7Spawn", "&7Shop"))
        assertEquals("Home Base", MenuDef.fill("Home {name}", placeholders))
        assertEquals(
            listOf("&fBase", "&7Spawn", "&7Shop", "a", "b"),
            MenuDef.fillLines(listOf("&f{name}", "{warps}", "a\nb"), placeholders),
        )
    }

    @Test
    fun `actions parse`() {
        assertEquals(MenuAction("open", "homes"), MenuAction.parse("[open] homes"))
        assertEquals(MenuAction("close", ""), MenuAction.parse("  [close]"))
        assertEquals(MenuAction("console", "give {player} diamond 1"), MenuAction.parse("[console]   give {player} diamond 1 "))
        assertNull(MenuAction.parse("[explode] now"))
        assertNull(MenuAction.parse("open homes"))
        assertEquals("[open] homes", MenuAction("open", "homes").toString())
        assertEquals("[close]", MenuAction("close", "").toString())
    }
}
