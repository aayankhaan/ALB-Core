package com.aayan.albcore.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.ResultSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger

/**
 * A pooled SQL database (SQLite or MySQL) with an async API.
 *
 * Every method that returns a [CompletableFuture] runs on ALBCore's database threads, never on the
 * server main thread. To touch the Bukkit API with the result, hop back with
 * [com.aayan.albcore.utils.thenSync]. Never call `join()`/`get()` on these futures from the main thread.
 *
 * Child plugins get the shared instance from `ALBCore.instance.database`, declare their tables with
 * [migrate] in `onEnable`, then use [query], [update], [transaction] and friends.
 *
 * Failed tasks are logged with their SQL and also complete the future exceptionally.
 */
class Database(
    val settings: DatabaseSettings,
    private val logger: Logger,
) : AutoCloseable {

    val type: DatabaseType = settings.type
    val dialect: SqlDialect = SqlDialect.of(type)
    val tablePrefix: String = settings.tablePrefix

    private val dataSource: HikariDataSource
    private val executor: ThreadPoolExecutor
    private val threadFactory = NamedThreadFactory("${settings.poolName}-DB")

    @Volatile
    var isClosed: Boolean = false
        private set

    init {
        require(TABLE_PREFIX.matches(tablePrefix)) {
            "Invalid table prefix '$tablePrefix': use only letters, digits and _ (max 32 chars)"
        }
        dataSource = try {
            HikariDataSource(buildHikariConfig(settings))
        } catch (e: Exception) {
            throw IllegalStateException("Could not connect to the ${type.configName} database: ${rootMessage(e)}", e)
        }
        // SQLite has one connection, so one worker keeps tasks in submit order.
        val threads = if (type == DatabaseType.SQLITE) 1 else settings.pool.maximumPoolSize.coerceAtLeast(1)
        executor = ThreadPoolExecutor(
            threads, threads, 60L, TimeUnit.SECONDS,
            LinkedBlockingQueue(),
            threadFactory,
        )
        try {
            dataSource.connection.use { connection ->
                connection.execute(
                    "CREATE TABLE IF NOT EXISTS ${schemaTable()} (" +
                        "owner VARCHAR(64) NOT NULL PRIMARY KEY, " +
                        "version INT NOT NULL, " +
                        "updated_at BIGINT NOT NULL)"
                )
            }
        } catch (e: Exception) {
            closeQuietly()
            throw IllegalStateException("Could not prepare the ${type.configName} database: ${rootMessage(e)}", e)
        }
    }

    /** Returns [name] with this database's table prefix, e.g. `table("levels")` gives `alb_levels`. */
    fun table(name: String): String {
        SqlDialect.requireIdentifier(tablePrefix + name)
        return tablePrefix + name
    }

    // ---------------------------------------------------------------- async API

    /** Runs [block] with a pooled connection on a database thread. The connection is returned afterwards. */
    fun <T> withConnection(block: (Connection) -> T): CompletableFuture<T> =
        submit("withConnection") { dataSource.connection.use(block) }

    /** Runs [block] in a transaction on a database thread: committed if it returns, rolled back if it throws. */
    fun <T> transaction(block: (Connection) -> T): CompletableFuture<T> =
        submit("transaction") { dataSource.connection.use { it.inTransaction(block) } }

    /** Runs an INSERT/UPDATE/DELETE and completes with the number of affected rows. */
    fun update(sql: String, vararg params: Any?): CompletableFuture<Int> =
        submit(sql) { dataSource.connection.use { it.update(sql, *params) } }

    /** Runs a SELECT and completes with every row mapped by [mapper]. */
    fun <T> query(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): CompletableFuture<List<T>> =
        submit(sql) { dataSource.connection.use { it.query(sql, *params, mapper = mapper) } }

    /** Runs a SELECT and completes with the first row mapped by [mapper], or null if there are no rows. */
    fun <T> queryFirst(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): CompletableFuture<T?> =
        submit(sql) { dataSource.connection.use { it.queryFirst(sql, *params, mapper = mapper) } }

    /** Runs one statement for each row of parameters, in a single transaction. */
    fun batch(sql: String, rows: List<List<Any?>>): CompletableFuture<IntArray> =
        submit(sql) { dataSource.connection.use { connection -> connection.inTransaction { it.batch(sql, rows) } } }

    /** Runs an INSERT and completes with the generated auto-increment key (or null). */
    fun insertAndGetKey(sql: String, vararg params: Any?): CompletableFuture<Long?> =
        submit(sql) { dataSource.connection.use { it.insertAndGetKey(sql, *params) } }

    /** Completes with the schema version stored for [owner] (0 if it never migrated). */
    fun schemaVersion(owner: String): CompletableFuture<Int> =
        submit("schemaVersion") { dataSource.connection.use { readVersion(it, owner) } }

    // ---------------------------------------------------------------- migrations (blocking)

    /**
     * Brings [owner]'s tables up to the newest version declared in [block] and returns that version.
     *
     * BLOCKING: call it from `onEnable` (startup), not during gameplay. Each version runs once, in
     * order, inside its own transaction, and the reached version is stored in `<prefix>schema_versions`.
     * On MySQL, DDL statements (CREATE/ALTER) commit implicitly, so keep one DDL statement per version.
     *
     * @param owner a stable id, normally your plugin name (letters, digits, `_ . -`; max 64 chars).
     * @throws MigrationException if a step fails. Disable your plugin in that case.
     */
    fun migrate(owner: String, block: MigrationPlan.() -> Unit): Int {
        require(OWNER.matches(owner)) { "Invalid migration owner '$owner'" }
        check(!isClosed) { "Database is closed" }
        val plan = MigrationPlan().apply(block)
        dataSource.connection.use { connection ->
            return withMigrationLock(connection, owner) {
                var current = readVersion(connection, owner)
                val latest = if (plan.migrations.isEmpty()) 0 else plan.migrations.lastKey()
                if (current > latest) {
                    logger.warning("[$owner] Database schema is at version $current, newer than this plugin knows ($latest). Was the plugin downgraded?")
                }
                for ((version, migration) in plan.migrations) {
                    if (version <= current) continue
                    try {
                        connection.inTransaction {
                            MigrationContext(connection, this).apply(migration.action)
                            writeVersion(connection, owner, version)
                        }
                    } catch (e: Exception) {
                        throw MigrationException(owner, version, migration.description, e)
                    }
                    current = version
                    val label = if (migration.description.isNotBlank()) ": ${migration.description}" else ""
                    logger.info("[$owner] Applied database migration $version$label")
                }
                current
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    /** Pool statistics, for `/albcore info`. */
    fun poolStats(): PoolStats {
        val bean = dataSource.hikariPoolMXBean ?: return PoolStats(0, 0, 0, 0, executor.queue.size)
        return PoolStats(
            active = bean.activeConnections,
            idle = bean.idleConnections,
            total = bean.totalConnections,
            waiting = bean.threadsAwaitingConnection,
            queuedTasks = executor.queue.size,
        )
    }

    /**
     * Stops accepting tasks, waits up to [timeoutSeconds] for queued tasks to finish, then closes the pool.
     * ALBCore calls this in its `onDisable`, after its dependents were disabled.
     */
    fun close(timeoutSeconds: Long) {
        if (isClosed) return
        isClosed = true
        executor.shutdown()
        if (threadFactory.isWorker(Thread.currentThread())) {
            // Called from a callback on one of our own threads, which cannot wait for itself.
            // Finish the shutdown on a separate thread instead.
            Thread({ awaitAndCloseDataSource(timeoutSeconds) }, "${settings.poolName}-DB-close").apply { isDaemon = true }.start()
            return
        }
        awaitAndCloseDataSource(timeoutSeconds)
    }

    private fun awaitAndCloseDataSource(timeoutSeconds: Long) {
        try {
            if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                logger.warning("Database tasks did not finish within ${timeoutSeconds}s; ${executor.queue.size} queued task(s) were dropped.")
                executor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        dataSource.close()
    }

    override fun close() = close(30)

    // ---------------------------------------------------------------- internals

    private fun <T> submit(label: String, task: () -> T): CompletableFuture<T> {
        if (isClosed) return CompletableFuture.failedFuture(IllegalStateException("Database is closed"))
        val future = CompletableFuture<T>()
        try {
            executor.execute {
                try {
                    future.complete(task())
                } catch (t: Throwable) {
                    logger.log(Level.WARNING, "Database task failed [${label.take(200)}]: ${t.message}", t)
                    future.completeExceptionally(t)
                }
            }
        } catch (e: RejectedExecutionException) {
            future.completeExceptionally(IllegalStateException("Database is closed", e))
        }
        return future
    }

    private fun schemaTable() = table("schema_versions")

    private fun readVersion(connection: Connection, owner: String): Int =
        connection.queryFirst("SELECT version FROM ${schemaTable()} WHERE owner = ?", owner) { it.getInt(1) } ?: 0

    private fun writeVersion(connection: Connection, owner: String, version: Int) {
        val sql = dialect.upsert(schemaTable(), listOf("owner"), listOf("version", "updated_at"))
        connection.update(sql, owner, version, System.currentTimeMillis())
    }

    /** On MySQL, serialises migrations across servers that share the database. SQLite needs no lock. */
    private fun <T> withMigrationLock(connection: Connection, owner: String, block: () -> T): T {
        if (type != DatabaseType.MYSQL) return block()
        val lockName = "${tablePrefix}migrate_$owner".take(64)
        val acquired = connection.queryFirst("SELECT GET_LOCK(?, 60)", lockName) { it.getInt(1) } == 1
        check(acquired) { "Timed out waiting for the migration lock '$lockName'" }
        try {
            return block()
        } finally {
            runCatching { connection.queryFirst("SELECT RELEASE_LOCK(?)", lockName) { it.getInt(1) } }
        }
    }

    private fun closeQuietly() {
        isClosed = true
        executor.shutdownNow()
        runCatching { dataSource.close() }
    }

    data class PoolStats(val active: Int, val idle: Int, val total: Int, val waiting: Int, val queuedTasks: Int)

    private class NamedThreadFactory(private val prefix: String) : ThreadFactory {
        private val counter = AtomicInteger()
        private val workers: MutableSet<Thread> = ConcurrentHashMap.newKeySet()

        override fun newThread(runnable: Runnable): Thread {
            lateinit var thread: Thread
            thread = Thread({
                try {
                    runnable.run()
                } finally {
                    workers.remove(thread)
                }
            }, "$prefix-${counter.incrementAndGet()}")
            thread.isDaemon = true
            workers += thread
            return thread
        }

        fun isWorker(thread: Thread): Boolean = thread in workers
    }

    companion object {
        private val TABLE_PREFIX = Regex("[A-Za-z0-9_]{0,32}")
        private val OWNER = Regex("[A-Za-z0-9_.-]{1,64}")

        private fun rootMessage(e: Throwable): String {
            var cause: Throwable = e
            while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
            return cause.message ?: cause.javaClass.simpleName
        }

        internal fun buildHikariConfig(settings: DatabaseSettings): HikariConfig {
            val pool = settings.pool
            val config = HikariConfig()
            config.poolName = "${settings.poolName}-${settings.type.configName}"
            config.connectionTimeout = pool.connectionTimeoutMs
            config.idleTimeout = pool.idleTimeoutMs
            config.maxLifetime = pool.maxLifetimeMs
            if (pool.keepaliveTimeMs > 0) config.keepaliveTime = pool.keepaliveTimeMs

            when (settings.type) {
                DatabaseType.SQLITE -> {
                    val file = settings.sqliteFile.absoluteFile
                    file.parentFile?.mkdirs()
                    config.driverClassName = "org.sqlite.JDBC"
                    config.jdbcUrl = "jdbc:sqlite:${file.path}"
                    // One connection: SQLite allows a single writer, so a bigger pool only adds lock errors.
                    config.maximumPoolSize = 1
                    config.minimumIdle = 1
                    config.addDataSourceProperty("journal_mode", "WAL")
                    config.addDataSourceProperty("synchronous", "NORMAL")
                    config.addDataSourceProperty("foreign_keys", "true")
                    config.addDataSourceProperty("busy_timeout", "10000")
                }

                DatabaseType.MYSQL -> {
                    val mysql = settings.mysql
                    config.driverClassName = "com.mysql.cj.jdbc.Driver"
                    config.jdbcUrl = "jdbc:mysql://${mysql.host}:${mysql.port}/${mysql.database}"
                    config.username = mysql.username
                    config.password = mysql.password
                    config.maximumPoolSize = pool.maximumPoolSize.coerceAtLeast(1)
                    config.minimumIdle = pool.minimumIdle.coerceIn(0, config.maximumPoolSize)
                    val defaults = linkedMapOf(
                        "sslMode" to if (mysql.useSsl) "REQUIRED" else "DISABLED",
                        // Needed for caching_sha2_password logins when SSL is off.
                        "allowPublicKeyRetrieval" to (!mysql.useSsl).toString(),
                        "characterEncoding" to "UTF-8",
                        "useUnicode" to "true",
                        "cachePrepStmts" to "true",
                        "prepStmtCacheSize" to "250",
                        "prepStmtCacheSqlLimit" to "2048",
                        "useServerPrepStmts" to "true",
                        "useLocalSessionState" to "true",
                        "rewriteBatchedStatements" to "true",
                        "cacheResultSetMetadata" to "true",
                        "cacheServerConfiguration" to "true",
                        "elideSetAutoCommits" to "true",
                        "maintainTimeStats" to "false",
                    )
                    (defaults + mysql.properties).forEach { (key, value) -> config.addDataSourceProperty(key, value) }
                }
            }
            return config
        }
    }
}
