package com.aayan.albcore.data

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Loads, caches and saves player data on top of a [PlayerDataStorage].
 *
 * Every operation for the same player runs in order (a per-player queue), so a save on quit always
 * finishes before the next load on rejoin, even on MySQL with many worker threads.
 * Most code should use the [PlayerData] facade instead of this class.
 */
class PlayerDataService(
    val storage: PlayerDataStorage,
    private val logger: Logger,
) : AutoCloseable {

    private val loaded = ConcurrentHashMap<UUID, PlayerDataHolder>()
    private val queues = ConcurrentHashMap<UUID, CompletableFuture<*>>()

    val storageType: PlayerDataStorageType get() = storage.type

    /** Cached data of a loaded (normally online) player, or null. */
    fun get(uuid: UUID): PlayerDataHolder? = loaded[uuid]

    fun isLoaded(uuid: UUID): Boolean = loaded.containsKey(uuid)

    fun loadedPlayers(): Set<UUID> = HashSet(loaded.keys)

    /** Loads the player's data into the cache (or returns the cached copy). ALBCore does this on login. */
    fun load(uuid: UUID): CompletableFuture<PlayerDataHolder> = enqueue(uuid) {
        loaded[uuid]?.let { return@enqueue CompletableFuture.completedFuture(it) }
        storage.load(uuid).thenApply { text ->
            PlayerDataHolder.parse(uuid, text).also { loaded[uuid] = it }
        }
    }

    /** Saves the player's cached data if it changed. */
    fun save(uuid: UUID): CompletableFuture<Void?> = enqueue(uuid) { saveHolder(loaded[uuid]) }

    /** Saves every cached player that has changes. */
    fun saveAll(): CompletableFuture<Void?> =
        CompletableFuture.allOf(*loaded.keys.map { save(it) }.toTypedArray()).thenApply { null }

    /** Saves the player's data and removes it from the cache. ALBCore does this on quit. */
    fun unload(uuid: UUID): CompletableFuture<Void?> = enqueue(uuid) {
        val holder = loaded.remove(uuid) ?: return@enqueue CompletableFuture.completedFuture(null)
        saveHolder(holder).whenComplete { _, error ->
            // Keep unsaved data in memory so the next autosave retries it.
            if (error != null) loaded.putIfAbsent(uuid, holder)
        }
    }

    /** Drops the cached data without saving (e.g. the login was denied after loading). */
    fun discard(uuid: UUID): CompletableFuture<Void?> = enqueue(uuid) {
        loaded.remove(uuid)
        CompletableFuture.completedFuture(null)
    }

    /**
     * Reads any player's data, online or offline. Online players get their live cached copy.
     * For offline players the result is a detached copy: changes to it are NOT saved (use [edit]).
     */
    fun fetch(uuid: UUID): CompletableFuture<PlayerDataHolder> = enqueue(uuid) {
        loaded[uuid]?.let { return@enqueue CompletableFuture.completedFuture(it) }
        storage.load(uuid).thenApply { PlayerDataHolder.parse(uuid, it) }
    }

    /**
     * Changes any player's data, online or offline, and saves offline changes right away.
     * [action] runs on a background thread: do not touch the Bukkit world API inside it.
     */
    fun edit(uuid: UUID, action: (PlayerDataHolder) -> Unit): CompletableFuture<Void?> = enqueue(uuid) {
        val live = loaded[uuid]
        if (live != null) {
            action(live)
            return@enqueue CompletableFuture.completedFuture(null)
        }
        storage.load(uuid).thenCompose { text ->
            val holder = PlayerDataHolder.parse(uuid, text)
            action(holder)
            storage.save(uuid, holder.toYamlString())
        }
    }

    /**
     * Copies every player's data from [source] into this service's storage.
     * Players that are loaded right now are skipped, because their live data would overwrite the import.
     */
    fun importFrom(source: PlayerDataStorage): CompletableFuture<ImportResult> =
        source.listAll().thenCompose { uuids ->
            var skipped = 0
            val copies = uuids.mapNotNull { uuid ->
                if (isLoaded(uuid)) {
                    skipped++
                    null
                } else {
                    enqueue(uuid) {
                        source.load(uuid).thenCompose { text ->
                            if (text == null) CompletableFuture.completedFuture(false)
                            else storage.save(uuid, text).thenApply { true }
                        }
                    }
                }
            }
            CompletableFuture.allOf(*copies.toTypedArray()).thenApply {
                ImportResult(imported = copies.count { it.join() }, skippedOnline = skipped)
            }
        }

    /** Unloads players that are no longer online, [graceMillis] after they were loaded. */
    fun unloadOffline(isOnline: (UUID) -> Boolean, graceMillis: Long = 60_000) {
        val now = System.currentTimeMillis()
        loaded.forEach { (uuid, holder) ->
            if (!isOnline(uuid) && now - holder.loadedAt > graceMillis) unload(uuid)
        }
    }

    /** Saves everything, waits up to [timeoutSeconds], then closes the storage. */
    fun close(timeoutSeconds: Long) {
        try {
            saveAll().get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Could not save all player data on shutdown: ${unwrap(e).message}", unwrap(e))
        }
        storage.close()
    }

    override fun close() = close(30)

    private fun saveHolder(holder: PlayerDataHolder?): CompletableFuture<Void?> {
        val snapshot = holder?.snapshotIfDirty() ?: return CompletableFuture.completedFuture(null)
        return storage.save(holder.uuid, snapshot).whenComplete { _, error ->
            if (error != null) {
                holder.markDirty()
                logger.log(Level.SEVERE, "Could not save player data of ${holder.uuid}: ${unwrap(error).message}", unwrap(error))
            }
        }
    }

    /** Runs [operation] after every earlier operation for [uuid] has finished. */
    private fun <T> enqueue(uuid: UUID, operation: () -> CompletableFuture<T>): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        val previous = queues.put(uuid, result)
        val start: CompletableFuture<*> = previous?.handle { _, _ -> null } ?: CompletableFuture.completedFuture(null)
        start.thenCompose {
            try {
                operation()
            } catch (t: Throwable) {
                CompletableFuture.failedFuture(t)
            }
        }.whenComplete { value, error ->
            if (error != null) result.completeExceptionally(unwrap(error)) else result.complete(value)
        }
        result.whenComplete { _, _ -> queues.remove(uuid, result) }
        return result
    }

    data class ImportResult(val imported: Int, val skippedOnline: Int)

    companion object {
        internal fun unwrap(error: Throwable): Throwable =
            if ((error is CompletionException || error is ExecutionException) && error.cause != null) error.cause!! else error
    }
}
