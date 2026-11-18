package com.aayan.albcore.config

import com.aayan.albcore.data.PlayerDataStorageType
import com.aayan.albcore.database.DatabaseType
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.InputStreamReader

class CoreConfigTest {

    private val dataFolder = File("plugins/ALBCore")

    private fun load(yaml: String, withDefaults: Boolean = true): Pair<CoreConfig, List<String>> {
        val config = YamlConfiguration()
        config.loadFromString(yaml)
        if (withDefaults) {
            val defaults = javaClass.classLoader.getResourceAsStream("config.yml")!!.use {
                YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8))
            }
            config.setDefaults(defaults)
        }
        val warnings = mutableListOf<String>()
        return CoreConfig.load(config, dataFolder) { warnings += it } to warnings
    }

    @Test
    fun `bundled defaults are valid`() {
        val (config, warnings) = load("")
        assertEquals(emptyList<String>(), warnings)
        assertEquals(DatabaseType.SQLITE, config.database.type)
        assertEquals("alb_", config.database.tablePrefix)
        assertEquals(File(dataFolder, "database.db"), config.database.sqliteFile)
        assertEquals(PlayerDataStorageType.YAML, config.playerData.storage)
        assertEquals(300, config.playerData.autosaveIntervalSeconds)
        assertTrue(config.webhooks.isEmpty())
    }

    @Test
    fun `reads mysql settings`() {
        val (config, warnings) = load(
            """
            database:
              type: mysql
              table-prefix: sky_
              mysql:
                host: db.example.net
                port: 3307
                database: skyabyss
                username: sky
                password: hunter2
                use-ssl: true
                properties:
                  serverTimezone: UTC
              pool:
                maximum-pool-size: 6
            player-data:
              storage: mysql
            """.trimIndent()
        )
        assertEquals(emptyList<String>(), warnings)
        val db = config.database
        assertEquals(DatabaseType.MYSQL, db.type)
        assertEquals("sky_", db.tablePrefix)
        assertEquals("db.example.net", db.mysql.host)
        assertEquals(3307, db.mysql.port)
        assertEquals("skyabyss", db.mysql.database)
        assertEquals("hunter2", db.mysql.password)
        assertTrue(db.mysql.useSsl)
        assertEquals(mapOf("serverTimezone" to "UTC"), db.mysql.properties)
        assertEquals(6, db.pool.maximumPoolSize)
        assertEquals(PlayerDataStorageType.MYSQL, config.playerData.storage)
    }

    @Test
    fun `secrets never appear in toString`() {
        val (config, _) = load(
            """
            database:
              mysql:
                password: hunter2
            discord:
              webhooks:
                staff:
                  url: https://discord.com/api/webhooks/123/secret-token
            """.trimIndent()
        )
        val text = config.toString()
        assertFalse(text.contains("hunter2"), text)
        assertFalse(text.contains("secret-token"), text)
    }

    @Test
    fun `invalid values fall back to defaults with warnings`() {
        val (config, warnings) = load(
            """
            database:
              type: postgres
              table-prefix: "bad prefix;"
              pool:
                maximum-pool-size: 0
            player-data:
              storage: json
              autosave-interval-seconds: -5
            discord:
              webhooks:
                broken:
                  url: http://not-https.example
            """.trimIndent()
        )
        assertEquals(DatabaseType.SQLITE, config.database.type)
        assertEquals("alb_", config.database.tablePrefix)
        assertEquals(10, config.database.pool.maximumPoolSize)
        assertEquals(PlayerDataStorageType.YAML, config.playerData.storage)
        assertEquals(300, config.playerData.autosaveIntervalSeconds)
        assertTrue(config.webhooks.isEmpty())
        assertEquals(6, warnings.size, warnings.joinToString("\n"))
    }

    @Test
    fun `absolute sqlite paths are kept`() {
        val absolute = File(System.getProperty("java.io.tmpdir"), "shared.db").absoluteFile
        val (config, _) = load("database:\n  sqlite:\n    file: '${absolute.path.replace("\\", "/")}'\n")
        assertEquals(absolute.path.replace("\\", "/"), config.database.sqliteFile.path.replace("\\", "/"))
    }

    @Test
    fun `reads webhooks`() {
        val (config, _) = load(
            """
            discord:
              webhooks:
                staff:
                  url: https://discord.com/api/webhooks/1/abc
                  username: Logs
                  avatar-url: ""
            """.trimIndent()
        )
        val hook = config.webhooks.getValue("staff")
        assertEquals("Logs", hook.username)
        assertEquals(null, hook.avatarUrl)
    }
}
