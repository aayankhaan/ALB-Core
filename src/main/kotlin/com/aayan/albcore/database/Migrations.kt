package com.aayan.albcore.database

import java.sql.Connection
import java.util.SortedMap
import java.util.TreeMap

/**
 * The list of schema versions for one owner (usually one plugin), built inside [Database.migrate].
 *
 * ```
 * db.migrate("ALBLevel") {
 *     version(1, "Create levels table") {
 *         execute("CREATE TABLE ${table("levels")} (uuid CHAR(36) PRIMARY KEY, level INT NOT NULL)")
 *     }
 *     version(2, "Add xp column") {
 *         execute("ALTER TABLE ${table("levels")} ADD COLUMN xp BIGINT NOT NULL DEFAULT 0")
 *     }
 * }
 * ```
 *
 * Rules: never edit or renumber a version that has shipped; add a new version instead.
 */
class MigrationPlan internal constructor() {

    internal val migrations: SortedMap<Int, Migration> = TreeMap()

    /** Declares schema [version] (1, 2, 3...). [action] runs once, the first time the database is below it. */
    fun version(version: Int, description: String = "", action: MigrationContext.() -> Unit) {
        require(version > 0) { "Migration versions start at 1 (got $version)" }
        require(version !in migrations) { "Migration version $version is declared twice" }
        migrations[version] = Migration(version, description, action)
    }
}

/** One schema step. */
class Migration internal constructor(
    val version: Int,
    val description: String,
    internal val action: MigrationContext.() -> Unit,
)

/** What a migration step can use. Everything runs on one connection inside one transaction. */
class MigrationContext internal constructor(
    /** The raw connection, for anything the helpers below do not cover. */
    val connection: Connection,
    private val database: Database,
) {
    val type: DatabaseType get() = database.type
    val dialect: SqlDialect get() = database.dialect

    /** Prefixed table name, see [Database.table]. */
    fun table(name: String): String = database.table(name)

    /** Runs a statement on every backend. */
    fun execute(sql: String) = connection.execute(sql)

    /** Runs a statement only on SQLite. */
    fun sqlite(sql: String) {
        if (type == DatabaseType.SQLITE) connection.execute(sql)
    }

    /** Runs a statement only on MySQL. */
    fun mysql(sql: String) {
        if (type == DatabaseType.MYSQL) connection.execute(sql)
    }

    /** Runs a parameterised statement (for data fixes inside a migration). */
    fun update(sql: String, vararg params: Any?): Int = connection.update(sql, *params)
}

/** Thrown by [Database.migrate] when a step fails. The failed step's changes were rolled back where the backend allows it. */
class MigrationException(
    val owner: String,
    val version: Int,
    description: String,
    cause: Throwable,
) : RuntimeException(
    "Database migration $version${if (description.isNotBlank()) " ($description)" else ""} for '$owner' failed: ${cause.message}",
    cause,
)
