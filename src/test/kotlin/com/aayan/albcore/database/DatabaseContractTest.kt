package com.aayan.albcore.database

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/** The same behaviour checks for every backend. Subclasses only say how to open the database. */
abstract class DatabaseContractTest {

    protected lateinit var db: Database
    protected val logger: Logger = Logger.getLogger("ALBCore-Test")

    /** A fresh, empty database with a unique table prefix. */
    abstract fun openDatabase(): Database

    /** Removes the tables a test created (SQLite tests use a temp file instead). */
    open fun dropTables(db: Database) {}

    @BeforeEach
    fun open() {
        db = openDatabase()
    }

    @AfterEach
    fun close() {
        if (!db.isClosed) {
            dropTables(db)
            db.close()
        }
    }

    protected fun <T> CompletableFuture<T>.await(): T = get(20, TimeUnit.SECONDS)

    @Test
    fun `migrate applies each version once, in order`() {
        val order = mutableListOf<Int>()
        val plan: MigrationPlan.() -> Unit = {
            version(2, "add column") {
                order += 2
                execute("ALTER TABLE ${table("items")} ADD COLUMN amount INT NOT NULL DEFAULT 0")
            }
            version(1, "create table") {
                order += 1
                execute("CREATE TABLE ${table("items")} (id VARCHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(64) NOT NULL)")
            }
        }
        assertEquals(2, db.migrate("Test", plan))
        assertEquals(listOf(1, 2), order)

        assertEquals(2, db.migrate("Test", plan))
        assertEquals(listOf(1, 2), order, "a second run must not repeat versions")
        assertEquals(2, db.schemaVersion("Test").await())
        assertEquals(0, db.schemaVersion("SomeoneElse").await())
    }

    @Test
    fun `new versions run on top of the stored version`() {
        db.migrate("Test") {
            version(1) { execute("CREATE TABLE ${table("items")} (id VARCHAR(36) NOT NULL PRIMARY KEY)") }
        }
        var ran = false
        val version = db.migrate("Test") {
            version(1) { error("version 1 must not run again") }
            version(2) {
                ran = true
                execute("ALTER TABLE ${table("items")} ADD COLUMN note VARCHAR(32)")
            }
        }
        assertEquals(2, version)
        assertTrue(ran)
    }

    @Test
    fun `failed migration throws and keeps the old version`() {
        db.migrate("Test") {
            version(1) { execute("CREATE TABLE ${table("items")} (id VARCHAR(36) NOT NULL PRIMARY KEY)") }
        }
        val error = assertThrows<MigrationException> {
            db.migrate("Test") {
                version(1) {}
                version(2, "broken") { execute("THIS IS NOT SQL") }
            }
        }
        assertEquals(2, error.version)
        assertEquals("Test", error.owner)
        assertEquals(1, db.schemaVersion("Test").await())
    }

    @Test
    fun `migration plans reject bad versions`() {
        assertThrows<IllegalArgumentException> { db.migrate("Test") { version(0) {} } }
        assertThrows<IllegalArgumentException> {
            db.migrate("Test") {
                version(1) {}
                version(1) {}
            }
        }
        assertThrows<IllegalArgumentException> { db.migrate("bad owner!") {} }
    }

    @Test
    fun `query, update, upsert and uuid binding`() {
        createItems()
        val id = UUID.randomUUID()
        assertEquals(1, db.update("INSERT INTO ${db.table("items")} (id, name, amount) VALUES (?, ?, ?)", id, "Sword", 3).await())

        val upsert = db.dialect.upsert(db.table("items"), listOf("id"), listOf("name", "amount"))
        db.update(upsert, id, "Sword+1", 5).await()
        db.update(upsert, UUID.randomUUID(), "Shield", 1).await()

        val rows = db.query("SELECT id, name, amount FROM ${db.table("items")} ORDER BY name") {
            Triple(it.getUuid("id"), it.getString("name"), it.getInt("amount"))
        }.await()
        assertEquals(2, rows.size)
        assertEquals(Triple(id, "Sword+1", 5), rows[1])

        val amount = db.queryFirst("SELECT amount FROM ${db.table("items")} WHERE id = ?", id) { it.getInt(1) }.await()
        assertEquals(5, amount)
        assertNull(db.queryFirst("SELECT amount FROM ${db.table("items")} WHERE id = ?", UUID.randomUUID()) { it.getInt(1) }.await())
    }

    @Test
    fun `batch inserts every row`() {
        createItems()
        val rows = (1..25).map { listOf<Any?>(UUID.randomUUID(), "item$it", it) }
        val counts = db.batch("INSERT INTO ${db.table("items")} (id, name, amount) VALUES (?, ?, ?)", rows).await()
        assertEquals(25, counts.size)
        val total = db.queryFirst("SELECT SUM(amount) FROM ${db.table("items")}") { it.getLong(1) }.await()
        assertEquals((1..25).sum().toLong(), total)
        assertArrayEquals(IntArray(0), db.batch("DELETE FROM ${db.table("items")} WHERE id = ?", emptyList()).await())
    }

    @Test
    fun `transaction rolls back when the block throws`() {
        createItems()
        val failed = db.transaction { connection ->
            connection.update("INSERT INTO ${db.table("items")} (id, name, amount) VALUES (?, ?, ?)", UUID.randomUUID(), "ghost", 1)
            error("boom")
        }
        assertThrows<ExecutionException> { failed.await() }
        assertEquals(0, db.queryFirst("SELECT COUNT(*) FROM ${db.table("items")}") { it.getInt(1) }.await())

        db.transaction { connection ->
            connection.update("INSERT INTO ${db.table("items")} (id, name, amount) VALUES (?, ?, ?)", UUID.randomUUID(), "real", 1)
        }.await()
        assertEquals(1, db.queryFirst("SELECT COUNT(*) FROM ${db.table("items")}") { it.getInt(1) }.await())
    }

    @Test
    fun `insertAndGetKey returns generated ids`() {
        db.migrate("Test") {
            version(1) { execute("CREATE TABLE ${table("logs")} (id ${dialect.autoIncrementPrimaryKey}, message VARCHAR(64) NOT NULL)") }
        }
        val first = db.insertAndGetKey("INSERT INTO ${db.table("logs")} (message) VALUES (?)", "a").await()
        val second = db.insertAndGetKey("INSERT INTO ${db.table("logs")} (message) VALUES (?)", "b").await()
        assertNotNull(first)
        assertNotEquals(first, second)
    }

    @Test
    fun `work runs off the calling thread`() {
        val caller = Thread.currentThread().name
        val worker = db.withConnection { Thread.currentThread().name }.await()
        assertNotEquals(caller, worker)
        assertTrue(worker.contains("-DB-"), "worker thread was '$worker'")
    }

    @Test
    fun `sql errors fail the future`() {
        assertThrows<ExecutionException> { db.update("DELETE FROM missing_table_xyz").await() }
    }

    @Test
    fun `closed database rejects new work`() {
        dropTables(db)
        db.close()
        assertTrue(db.isClosed)
        assertThrows<ExecutionException> { db.update("DELETE FROM x").await() }
        assertThrows<IllegalStateException> { db.migrate("Test") {} }
    }

    @Test
    fun `table names use the prefix and reject unsafe names`() {
        assertEquals(db.tablePrefix + "levels", db.table("levels"))
        assertThrows<IllegalArgumentException> { db.table("levels; DROP TABLE x") }
    }

    @Test
    fun `sqlite rolls back a failed migration's DDL`() {
        assumeTrue(db.type == DatabaseType.SQLITE, "MySQL commits DDL implicitly")
        assertThrows<MigrationException> {
            db.migrate("Test") {
                version(1) {
                    execute("CREATE TABLE ${table("half")} (id INT)")
                    execute("NOT SQL")
                }
            }
        }
        val exists = db.queryFirst(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
            db.table("half"),
        ) { it.getInt(1) }.await()
        assertEquals(0, exists)
    }

    protected fun createItems() {
        db.migrate("Test") {
            version(1) {
                execute("CREATE TABLE ${table("items")} (id VARCHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(64) NOT NULL, amount INT NOT NULL DEFAULT 0)")
            }
        }
    }
}
