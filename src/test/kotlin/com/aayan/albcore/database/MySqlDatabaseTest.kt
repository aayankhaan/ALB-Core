package com.aayan.albcore.database

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.util.UUID

/**
 * Runs the same checks against a real MySQL/MariaDB server. Skipped unless ALBCORE_TEST_MYSQL_HOST is set.
 * Optional: ALBCORE_TEST_MYSQL_PORT, ALBCORE_TEST_MYSQL_DATABASE, ALBCORE_TEST_MYSQL_USER, ALBCORE_TEST_MYSQL_PASSWORD.
 * Each test uses its own random table prefix and drops its tables afterwards.
 */
@EnabledIfEnvironmentVariable(named = "ALBCORE_TEST_MYSQL_HOST", matches = ".+")
class MySqlDatabaseTest : DatabaseContractTest() {

    override fun openDatabase(): Database {
        val env = System.getenv()
        val settings = DatabaseSettings(
            type = DatabaseType.MYSQL,
            sqliteFile = File("unused.db"),
            mysql = MySqlSettings(
                host = env.getValue("ALBCORE_TEST_MYSQL_HOST"),
                port = env["ALBCORE_TEST_MYSQL_PORT"]?.toInt() ?: 3306,
                database = env["ALBCORE_TEST_MYSQL_DATABASE"] ?: "albcore_test",
                username = env["ALBCORE_TEST_MYSQL_USER"] ?: "root",
                password = env["ALBCORE_TEST_MYSQL_PASSWORD"] ?: "",
            ),
            pool = PoolSettings(maximumPoolSize = 4, minimumIdle = 1),
            tablePrefix = "t" + UUID.randomUUID().toString().replace("-", "").take(8) + "_",
        )
        return Database(settings, logger)
    }

    override fun dropTables(db: Database) {
        if (db.isClosed) return
        val pattern = db.tablePrefix.replace("_", "\\_") + "%"
        val tables = db.query(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name LIKE ?",
            pattern,
        ) { it.getString(1) }.await()
        tables.forEach { db.update("DROP TABLE IF EXISTS $it").await() }
    }
}
