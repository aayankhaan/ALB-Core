package com.aayan.albcore.data

import com.aayan.albcore.database.Database
import com.aayan.albcore.database.DatabaseSettings
import com.aayan.albcore.database.DatabaseType
import org.bukkit.configuration.InvalidConfigurationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class PlayerDataStorageTest {

    @TempDir
    lateinit var folder: File

    private val logger = Logger.getLogger("ALBCore-Test")

    private fun roundTrip(storage: PlayerDataStorage) {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        assertNull(storage.load(a).get(10, TimeUnit.SECONDS))

        storage.save(a, "level: 1\n").get(10, TimeUnit.SECONDS)
        storage.save(a, "level: 2\n").get(10, TimeUnit.SECONDS)
        storage.save(b, "level: 9\n").get(10, TimeUnit.SECONDS)
        assertEquals("level: 2\n", storage.load(a).get(10, TimeUnit.SECONDS))
        assertEquals(setOf(a, b), storage.listAll().get(10, TimeUnit.SECONDS).toSet())

        storage.delete(a).get(10, TimeUnit.SECONDS)
        assertNull(storage.load(a).get(10, TimeUnit.SECONDS))
        assertEquals(listOf(b), storage.listAll().get(10, TimeUnit.SECONDS))
    }

    @Test
    fun `yaml storage keeps one file per player and leaves no temp files`() {
        val dir = File(folder, "playerdata")
        YamlPlayerDataStorage(dir, logger).use { storage ->
            roundTrip(storage)
            assertEquals(storage.type, PlayerDataStorageType.YAML)
        }
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
        assertEquals(1, dir.listFiles { f -> f.name.endsWith(".yml") }!!.size)
    }

    @Test
    fun `sql storage works on sqlite and creates its table once`() {
        val db = Database(DatabaseSettings(DatabaseType.SQLITE, File(folder, "pd.db")), logger)
        db.use {
            SqlPlayerDataStorage(db, ownsDatabase = false).use { roundTrip(it) }
            // A second storage on the same database must not fail on the existing table.
            SqlPlayerDataStorage(db, ownsDatabase = false).use { assertEquals(PlayerDataStorageType.SQLITE, it.type) }
            assertEquals(1, db.schemaVersion(SqlPlayerDataStorage.MIGRATION_OWNER).get())
            assertFalse(db.isClosed, "a storage that does not own the database must not close it")
        }
    }

    @Test
    fun `closing storages from their own worker threads does not hang`() {
        val yaml = YamlPlayerDataStorage(File(folder, "self-close"), logger)
        val uuid = UUID.randomUUID()
        yaml.save(uuid, "big: '" + "x".repeat(5_000_000) + "'\n") // keeps the worker busy
        val started = System.nanoTime()
        yaml.load(uuid).thenApply { yaml.close() }.get(10, TimeUnit.SECONDS)
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5), "YAML close waited for itself")

        val db = Database(DatabaseSettings(DatabaseType.SQLITE, File(folder, "self-close.db")), logger)
        val closeStarted = System.nanoTime()
        db.withConnection { db.close() }.get(10, TimeUnit.SECONDS) // runs close() on a DB worker thread
        assertTrue(System.nanoTime() - closeStarted < TimeUnit.SECONDS.toNanos(5), "Database close waited for itself")
        assertTrue(db.isClosed)
    }

    @Test
    fun `import from yaml into sqlite finishes`() {
        val yaml = YamlPlayerDataStorage(File(folder, "import-src"), logger)
        val players = (1..20).map { UUID.randomUUID() }
        players.forEachIndexed { i, uuid -> yaml.save(uuid, "level: $i\n").get(10, TimeUnit.SECONDS) }

        val db = Database(DatabaseSettings(DatabaseType.SQLITE, File(folder, "import.db")), logger)
        db.use {
            val service = PlayerDataService(SqlPlayerDataStorage(db, ownsDatabase = false), logger)
            val result = service.importFrom(yaml).whenComplete { _, _ -> yaml.close() }.get(10, TimeUnit.SECONDS)
            assertEquals(20, result.imported)
            assertEquals(7, service.fetch(players[7]).get(10, TimeUnit.SECONDS).getInt("level"))
        }
    }

    @Test
    fun `holder reads, writes and serialises values`() {
        val holder = PlayerDataHolder.parse(UUID.randomUUID(), "level: 4\nstats:\n  kills: 10\ntags:\n- a\n- b\n")
        assertEquals(4, holder.getInt("level"))
        assertEquals(10L, holder.getLong("stats.kills"))
        assertEquals(listOf("a", "b"), holder.getStringList("tags"))
        assertEquals("none", holder.getString("missing", "none"))
        assertEquals(setOf("level", "stats", "tags"), holder.keys())
        assertEquals(setOf("kills"), holder.keys("stats"))
        assertFalse(holder.isDirty)
        assertNull(holder.snapshotIfDirty())

        assertEquals(11L, holder.increment("stats.kills"))
        assertEquals(1.5, holder.increment("coins", 1.5))
        holder.remove("tags")
        assertTrue(holder.isDirty)

        val snapshot = holder.snapshotIfDirty()!!
        assertFalse(holder.isDirty)
        val copy = PlayerDataHolder.parse(holder.uuid, snapshot)
        assertEquals(11, copy.getInt("stats.kills"))
        assertFalse(copy.contains("tags"))
    }

    @Test
    fun `corrupt stored data is reported, not silently replaced`() {
        assertThrows<InvalidConfigurationException> { PlayerDataHolder.parse(UUID.randomUUID(), "level: [unclosed") }
    }

    @Test
    fun `storage type names parse from config`() {
        assertEquals(PlayerDataStorageType.MYSQL, PlayerDataStorageType.parse("MySQL"))
        assertEquals(PlayerDataStorageType.YAML, PlayerDataStorageType.parse(" yaml "))
        assertNull(PlayerDataStorageType.parse("json"))
        assertEquals(DatabaseType.SQLITE, PlayerDataStorageType.SQLITE.databaseType)
        assertNull(PlayerDataStorageType.YAML.databaseType)
    }
}
