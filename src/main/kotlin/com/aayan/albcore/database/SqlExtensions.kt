@file:JvmName("SqlExtensions")

package com.aayan.albcore.database

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.util.UUID

/*
 * Blocking JDBC helpers. Use them inside Database.withConnection / Database.transaction /
 * migrations, which already run off the main thread. Never call them on the main thread.
 */

/**
 * Binds [params] to `?` placeholders in order.
 * [UUID]s are stored as their 36-char string, enums as their name, everything else via `setObject`.
 */
fun PreparedStatement.bind(params: Array<out Any?>) {
    params.forEachIndexed { index, value ->
        val position = index + 1
        when (value) {
            null -> setObject(position, null)
            is UUID -> setString(position, value.toString())
            is Enum<*> -> setString(position, value.name)
            is Boolean -> setBoolean(position, value)
            is Char -> setString(position, value.toString())
            else -> setObject(position, value)
        }
    }
}

/** Runs an INSERT/UPDATE/DELETE/DDL statement and returns the number of affected rows. */
fun Connection.update(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { statement ->
        statement.bind(params)
        statement.executeUpdate()
    }

/** Runs a SELECT and maps every row with [mapper]. The mapper must not call `next()`. */
fun <T> Connection.query(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        statement.bind(params)
        statement.executeQuery().use { rs ->
            val rows = ArrayList<T>()
            while (rs.next()) rows += mapper(rs)
            rows
        }
    }

/** Runs a SELECT and maps only the first row, or returns null if there is none. */
fun <T> Connection.queryFirst(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): T? =
    prepareStatement(sql).use { statement ->
        statement.bind(params)
        statement.maxRows = 1
        statement.executeQuery().use { rs -> if (rs.next()) mapper(rs) else null }
    }

/** Runs one statement many times, once per entry in [rows]. Returns the per-row update counts. */
fun Connection.batch(sql: String, rows: List<List<Any?>>): IntArray =
    prepareStatement(sql).use { statement ->
        for (row in rows) {
            statement.bind(row.toTypedArray())
            statement.addBatch()
        }
        if (rows.isEmpty()) IntArray(0) else statement.executeBatch()
    }

/** Runs an INSERT and returns the generated auto-increment key, or null if none was generated. */
fun Connection.insertAndGetKey(sql: String, vararg params: Any?): Long? =
    prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
        statement.bind(params)
        statement.executeUpdate()
        statement.generatedKeys.use { keys -> if (keys.next()) keys.getLong(1) else null }
    }

/** Runs a plain SQL statement without parameters (DDL such as CREATE TABLE). */
fun Connection.execute(sql: String) {
    createStatement().use { it.execute(sql) }
}

/**
 * Runs [block] in a transaction: commits if it returns, rolls back and rethrows if it throws.
 * Restores the previous auto-commit mode afterwards.
 */
fun <T> Connection.inTransaction(block: (Connection) -> T): T {
    val previousAutoCommit = autoCommit
    autoCommit = false
    try {
        val result = block(this)
        commit()
        return result
    } catch (t: Throwable) {
        runCatching { rollback() }.exceptionOrNull()?.let(t::addSuppressed)
        throw t
    } finally {
        runCatching { autoCommit = previousAutoCommit }
    }
}

/** Reads a UUID stored as a 36-char string. Returns null for SQL NULL. */
fun ResultSet.getUuid(column: String): UUID? = getString(column)?.let(UUID::fromString)

/** Reads a nullable integer column (JDBC returns 0 for NULL otherwise). */
fun ResultSet.getIntOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

/** Reads a nullable long column (JDBC returns 0 for NULL otherwise). */
fun ResultSet.getLongOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }
