package com.aayan.albcore.database

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SqlDialectTest {

    @Test
    fun `sqlite upsert uses ON CONFLICT`() {
        assertEquals(
            "INSERT INTO t (id, a, b) VALUES (?, ?, ?) ON CONFLICT(id) DO UPDATE SET a = excluded.a, b = excluded.b",
            SqlDialect.SQLITE.upsert("t", listOf("id"), listOf("a", "b")),
        )
    }

    @Test
    fun `mysql upsert uses ON DUPLICATE KEY`() {
        assertEquals(
            "INSERT INTO t (id, a) VALUES (?, ?) ON DUPLICATE KEY UPDATE a = VALUES(a)",
            SqlDialect.MYSQL.upsert("t", listOf("id"), listOf("a")),
        )
    }

    @Test
    fun `upsert without value columns ignores duplicates`() {
        assertEquals("INSERT INTO t (id) VALUES (?) ON CONFLICT(id) DO NOTHING", SqlDialect.SQLITE.upsert("t", listOf("id"), emptyList()))
        assertEquals("INSERT IGNORE INTO t (id) VALUES (?)", SqlDialect.MYSQL.upsert("t", listOf("id"), emptyList()))
        assertEquals("INSERT OR IGNORE INTO t (id) VALUES (?)", SqlDialect.SQLITE.insertIgnore("t", listOf("id")))
    }

    @Test
    fun `rejects identifiers that could inject SQL`() {
        assertTrue(SqlDialect.isIdentifier("alb_levels"))
        assertFalse(SqlDialect.isIdentifier("levels; DROP TABLE x"))
        assertFalse(SqlDialect.isIdentifier("1abc"))
        assertThrows<IllegalArgumentException> { SqlDialect.SQLITE.upsert("t", listOf("id"), listOf("a = 1 --")) }
        assertThrows<IllegalArgumentException> { SqlDialect.MYSQL.upsert("bad table", listOf("id"), listOf("a")) }
    }

    @Test
    fun `dialect matches database type`() {
        assertEquals(SqlDialect.SQLITE, SqlDialect.of(DatabaseType.SQLITE))
        assertEquals(SqlDialect.MYSQL, SqlDialect.of(DatabaseType.MYSQL))
        assertEquals(DatabaseType.MYSQL, DatabaseType.parse(" MySQL "))
        assertEquals(null, DatabaseType.parse("postgres"))
    }
}
