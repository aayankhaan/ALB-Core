package com.aayan.albcore.database

import java.io.File

/** The SQL backends ALBCore supports. */
enum class DatabaseType(val configName: String) {
    SQLITE("sqlite"),
    MYSQL("mysql");

    companion object {
        /** Parses a config value such as `"mysql"` (case-insensitive). Returns null for unknown values. */
        fun parse(value: String?): DatabaseType? =
            entries.firstOrNull { it.configName.equals(value?.trim(), ignoreCase = true) }
    }
}

/** Connection settings for a MySQL (or MariaDB) server. */
data class MySqlSettings(
    val host: String = "localhost",
    val port: Int = 3306,
    val database: String = "minecraft",
    val username: String = "root",
    val password: String = "",
    val useSsl: Boolean = false,
    /** Extra JDBC properties. They override ALBCore's defaults. */
    val properties: Map<String, String> = emptyMap(),
) {
    /** Never prints the password, so settings can be logged safely. */
    override fun toString(): String =
        "MySqlSettings(host=$host, port=$port, database=$database, username=$username, password=***, useSsl=$useSsl)"
}

/** HikariCP pool settings. SQLite always uses a single connection, whatever these say. */
data class PoolSettings(
    val maximumPoolSize: Int = 10,
    val minimumIdle: Int = 2,
    val connectionTimeoutMs: Long = 10_000,
    val idleTimeoutMs: Long = 600_000,
    val maxLifetimeMs: Long = 1_800_000,
    /** 0 disables keepalive pings. */
    val keepaliveTimeMs: Long = 0,
)

/** Everything needed to open a [Database]. */
data class DatabaseSettings(
    val type: DatabaseType,
    /** SQLite database file. Ignored for MySQL. */
    val sqliteFile: File,
    val mysql: MySqlSettings = MySqlSettings(),
    val pool: PoolSettings = PoolSettings(),
    /** Prepended to every table name returned by [Database.table]. Letters, digits and `_` only. */
    val tablePrefix: String = "alb_",
    /** Name used for the pool and its worker threads (shows up in thread dumps). */
    val poolName: String = "ALBCore",
)
