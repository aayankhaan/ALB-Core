package com.aayan.albcore.data

import com.aayan.albcore.database.Database
import com.aayan.albcore.database.DatabaseType
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/** Where player data lives. Chosen with `player-data.storage` in ALBCore's config.yml. */
enum class PlayerDataStorageType(val configName: String) {
    YAML("yaml"),
    SQLITE("sqlite"),
    MYSQL("mysql");

    /** The SQL backend for this storage type, or null for YAML. */
    val databaseType: DatabaseType?
        get() = when (this) {
            YAML -> null
            SQLITE -> DatabaseType.SQLITE
            MYSQL -> DatabaseType.MYSQL
        }

    companion object {
        fun parse(value: String?): PlayerDataStorageType? =
            entries.firstOrNull { it.configName.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * Raw storage of one YAML document per player. All methods are async and never block the caller.
 * Implementations: [YamlPlayerDataStorage] (one file per player) and [SqlPlayerDataStorage] (one row per player).
 */
interface PlayerDataStorage : AutoCloseable {
    val type: PlayerDataStorageType

    /** Completes with the stored document, or null if the player has none yet. */
    fun load(uuid: UUID): CompletableFuture<String?>

    /** Stores [data], replacing what was there. */
    fun save(uuid: UUID, data: String): CompletableFuture<Void?>

    /** Removes the player's document. */
    fun delete(uuid: UUID): CompletableFuture<Void?>

    /** Completes with every player that has stored data (used by `/albcore playerdata import`). */
    fun listAll(): CompletableFuture<List<UUID>>
}

/** One `<uuid>.yml` file per player. Writes go to a temp file first, then replace the old file. */
class YamlPlayerDataStorage(
    private val folder: File,
    private val logger: Logger,
) : PlayerDataStorage {

    override val type = PlayerDataStorageType.YAML

    @Volatile
    private var worker: Thread? = null

    // One thread: file operations for the same player never overlap.
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ALBCore-PlayerData-YAML").apply {
            isDaemon = true
            worker = this
        }
    }

    init {
        folder.mkdirs()
    }

    private fun fileOf(uuid: UUID) = File(folder, "$uuid.yml")

    override fun load(uuid: UUID): CompletableFuture<String?> = async {
        val file = fileOf(uuid)
        if (file.isFile) file.readText(StandardCharsets.UTF_8) else null
    }

    override fun save(uuid: UUID, data: String): CompletableFuture<Void?> = async<Void?> {
        val target = fileOf(uuid).toPath()
        val temp = File(folder, "$uuid.yml.tmp").toPath()
        Files.writeString(temp, data, StandardCharsets.UTF_8)
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
        null
    }

    override fun delete(uuid: UUID): CompletableFuture<Void?> = async<Void?> {
        Files.deleteIfExists(fileOf(uuid).toPath())
        null
    }

    override fun listAll(): CompletableFuture<List<UUID>> = async {
        folder.listFiles { file -> file.isFile && file.name.endsWith(".yml") }
            .orEmpty()
            .mapNotNull { runCatching { UUID.fromString(it.name.removeSuffix(".yml")) }.getOrNull() }
    }

    private fun <T> async(block: () -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync(block, executor).whenComplete { _, error ->
            if (error != null) logger.log(Level.WARNING, "YAML player data operation failed: ${error.message}", error)
        }

    override fun close() {
        executor.shutdown()
        // Called from a callback running on our own worker: it cannot wait for itself. Queued writes still finish.
        if (Thread.currentThread() === worker) return
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) executor.shutdownNow()
    }
}

/**
 * One row per player in `<prefix>player_data`. Works on SQLite and MySQL.
 * If [ownsDatabase] is true, [close] also closes [database] (used when player data has its own pool).
 */
class SqlPlayerDataStorage(
    private val database: Database,
    private val ownsDatabase: Boolean,
) : PlayerDataStorage {

    override val type: PlayerDataStorageType = when (database.type) {
        DatabaseType.SQLITE -> PlayerDataStorageType.SQLITE
        DatabaseType.MYSQL -> PlayerDataStorageType.MYSQL
    }

    private val table = database.table("player_data")
    private val upsertSql = database.dialect.upsert(table, listOf("uuid"), listOf("data", "updated_at"))

    init {
        database.migrate(MIGRATION_OWNER) {
            version(1, "Create player_data table") {
                execute(
                    "CREATE TABLE IF NOT EXISTS $table (" +
                        "uuid CHAR(36) NOT NULL PRIMARY KEY, " +
                        "data ${dialect.longTextType} NOT NULL, " +
                        "updated_at BIGINT NOT NULL)"
                )
            }
        }
    }

    override fun load(uuid: UUID): CompletableFuture<String?> =
        database.queryFirst("SELECT data FROM $table WHERE uuid = ?", uuid) { it.getString(1) }

    override fun save(uuid: UUID, data: String): CompletableFuture<Void?> =
        database.update(upsertSql, uuid, data, System.currentTimeMillis()).thenApply { null }

    override fun delete(uuid: UUID): CompletableFuture<Void?> =
        database.update("DELETE FROM $table WHERE uuid = ?", uuid).thenApply { null }

    override fun listAll(): CompletableFuture<List<UUID>> =
        database.query("SELECT uuid FROM $table") { UUID.fromString(it.getString(1)) }

    override fun close() {
        if (ownsDatabase) database.close()
    }

    companion object {
        const val MIGRATION_OWNER = "ALBCore-player-data"
    }
}
