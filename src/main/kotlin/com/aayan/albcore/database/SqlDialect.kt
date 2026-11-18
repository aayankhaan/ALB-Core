package com.aayan.albcore.database

/**
 * SQL that differs between SQLite and MySQL. Use [Database.dialect] to pick the right one at runtime,
 * so the same plugin code works on both backends.
 */
enum class SqlDialect {

    SQLITE {
        override val autoIncrementPrimaryKey = "INTEGER PRIMARY KEY AUTOINCREMENT"
        override val longTextType = "TEXT"
        override val blobType = "BLOB"

        override fun upsert(table: String, keyColumns: List<String>, valueColumns: List<String>): String {
            val insert = insertSql(table, keyColumns + valueColumns)
            val conflict = keyColumns.joinToString(", ")
            if (valueColumns.isEmpty()) return "$insert ON CONFLICT($conflict) DO NOTHING"
            val updates = valueColumns.joinToString(", ") { "$it = excluded.$it" }
            return "$insert ON CONFLICT($conflict) DO UPDATE SET $updates"
        }

        override fun insertIgnore(table: String, columns: List<String>): String =
            insertSql(table, columns).replaceFirst("INSERT INTO", "INSERT OR IGNORE INTO")
    },

    MYSQL {
        override val autoIncrementPrimaryKey = "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY"
        override val longTextType = "MEDIUMTEXT"
        override val blobType = "MEDIUMBLOB"

        override fun upsert(table: String, keyColumns: List<String>, valueColumns: List<String>): String {
            if (valueColumns.isEmpty()) return insertIgnore(table, keyColumns)
            val insert = insertSql(table, keyColumns + valueColumns)
            // VALUES(col) works on both MySQL 8+/9 and MariaDB (the "AS new" alias form is MySQL-only).
            val updates = valueColumns.joinToString(", ") { "$it = VALUES($it)" }
            return "$insert ON DUPLICATE KEY UPDATE $updates"
        }

        override fun insertIgnore(table: String, columns: List<String>): String =
            insertSql(table, columns).replaceFirst("INSERT INTO", "INSERT IGNORE INTO")
    };

    /** Column definition for an auto-increment integer primary key. */
    abstract val autoIncrementPrimaryKey: String

    /** Text type that holds large values (SQLite `TEXT`, MySQL `MEDIUMTEXT`, up to 16 MB). */
    abstract val longTextType: String

    /** Binary type for large values (SQLite `BLOB`, MySQL `MEDIUMBLOB`). */
    abstract val blobType: String

    /**
     * Builds an "insert or update" statement with one `?` per column, keys first, then values.
     * [keyColumns] must form a primary key or unique index.
     *
     * Example: `upsert("alb_levels", listOf("uuid"), listOf("level", "xp"))`, then bind `uuid, level, xp`.
     */
    abstract fun upsert(table: String, keyColumns: List<String>, valueColumns: List<String>): String

    /** Builds an insert that silently skips rows whose key already exists. */
    abstract fun insertIgnore(table: String, columns: List<String>): String

    protected fun insertSql(table: String, columns: List<String>): String {
        requireIdentifier(table)
        require(columns.isNotEmpty()) { "At least one column is required" }
        columns.forEach(::requireIdentifier)
        val placeholders = columns.joinToString(", ") { "?" }
        return "INSERT INTO $table (${columns.joinToString(", ")}) VALUES ($placeholders)"
    }

    companion object {
        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")

        /** The dialect for a [DatabaseType]. */
        fun of(type: DatabaseType): SqlDialect = when (type) {
            DatabaseType.SQLITE -> SQLITE
            DatabaseType.MYSQL -> MYSQL
        }

        /** True if [name] is a safe, unquoted SQL identifier (letters, digits, `_`; max 64 chars). */
        fun isIdentifier(name: String): Boolean = IDENTIFIER.matches(name)

        /** Throws if [name] is not a safe SQL identifier. Guards against SQL injection through names. */
        fun requireIdentifier(name: String) {
            require(isIdentifier(name)) { "Invalid SQL identifier: '$name'" }
        }
    }
}
