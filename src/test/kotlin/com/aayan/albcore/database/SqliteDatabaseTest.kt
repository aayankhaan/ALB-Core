package com.aayan.albcore.database

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SqliteDatabaseTest : DatabaseContractTest() {

    @TempDir
    lateinit var folder: File

    override fun openDatabase(): Database =
        Database(DatabaseSettings(DatabaseType.SQLITE, File(folder, "nested/test.db"), tablePrefix = "t_"), logger)

    @Test
    fun `creates the database file and folders`() {
        assertTrue(File(folder, "nested/test.db").isFile)
        assertEquals(DatabaseType.SQLITE, db.type)
        assertEquals(SqlDialect.SQLITE, db.dialect)
    }

    @Test
    fun `data survives reopening`() {
        createItems()
        db.update("INSERT INTO ${db.table("items")} (id, name) VALUES (?, ?)", "a", "kept").await()
        db.close()
        db = openDatabase()
        assertEquals("kept", db.queryFirst("SELECT name FROM ${db.table("items")} WHERE id = ?", "a") { it.getString(1) }.await())
        assertEquals(1, db.schemaVersion("Test").await())
    }

    @Test
    fun `rejects an unsafe table prefix`() {
        assertThrows<IllegalArgumentException> {
            Database(DatabaseSettings(DatabaseType.SQLITE, File(folder, "x.db"), tablePrefix = "x; DROP"), logger)
        }
    }
}
