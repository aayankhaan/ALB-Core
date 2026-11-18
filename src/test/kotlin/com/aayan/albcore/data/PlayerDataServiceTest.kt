package com.aayan.albcore.data

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

class PlayerDataServiceTest {

    /** In-memory storage running on several threads, like MySQL. Saves can be slowed down or made to fail. */
    private class FakeStorage(private val saveDelayMs: Long = 0) : PlayerDataStorage {
        override val type = PlayerDataStorageType.MYSQL
        val data = ConcurrentHashMap<UUID, String>()
        val saves = AtomicInteger()
        val failSaves = AtomicBoolean(false)
        private val executor = Executors.newFixedThreadPool(4)

        override fun load(uuid: UUID): CompletableFuture<String?> = CompletableFuture.supplyAsync({ data[uuid] }, executor)

        override fun save(uuid: UUID, data: String): CompletableFuture<Void?> = CompletableFuture.supplyAsync({
            Thread.sleep(saveDelayMs)
            if (failSaves.get()) throw IllegalStateException("disk full")
            this.data[uuid] = data
            saves.incrementAndGet()
            null
        }, executor)

        override fun delete(uuid: UUID): CompletableFuture<Void?> =
            CompletableFuture.supplyAsync({ data.remove(uuid); null }, executor)

        override fun listAll(): CompletableFuture<List<UUID>> = CompletableFuture.completedFuture(data.keys.toList())

        override fun close() {
            executor.shutdown()
        }
    }

    private val logger = Logger.getLogger("ALBCore-Test")
    private val storages = mutableListOf<FakeStorage>()

    private fun storage(saveDelayMs: Long = 0) = FakeStorage(saveDelayMs).also { storages += it }

    private fun <T> CompletableFuture<T>.await(): T = get(10, TimeUnit.SECONDS)

    @AfterEach
    fun cleanup() = storages.forEach { it.close() }

    @Test
    fun `new players start empty and changes are saved`() {
        val storage = storage()
        val service = PlayerDataService(storage, logger)
        val uuid = UUID.randomUUID()

        val holder = service.load(uuid).await()
        assertEquals(0, holder.getInt("level"))
        holder.set("level", 5)
        holder.set("stats.kills", 12)
        service.save(uuid).await()

        val reloaded = PlayerDataService(storage, logger).load(uuid).await()
        assertEquals(5, reloaded.getInt("level"))
        assertEquals(12, reloaded.getInt("stats.kills"))
    }

    @Test
    fun `load returns the cached holder`() {
        val service = PlayerDataService(storage(), logger)
        val uuid = UUID.randomUUID()
        assertSame(service.load(uuid).await(), service.load(uuid).await())
        assertTrue(service.isLoaded(uuid))
    }

    @Test
    fun `saving without changes writes nothing`() {
        val storage = storage()
        val service = PlayerDataService(storage, logger)
        val uuid = UUID.randomUUID()
        service.load(uuid).await()
        service.save(uuid).await()
        assertEquals(0, storage.saves.get())
        service.get(uuid)!!.set("a", 1)
        service.save(uuid).await()
        service.save(uuid).await()
        assertEquals(1, storage.saves.get())
    }

    @Test
    fun `rejoin waits for the quit save, even when saving is slow`() {
        val storage = storage(saveDelayMs = 300)
        val service = PlayerDataService(storage, logger)
        val uuid = UUID.randomUUID()

        service.load(uuid).await().set("coins", 100)
        val unload = service.unload(uuid) // quit: slow save starts
        val rejoin = service.load(uuid)   // rejoin right away
        assertEquals(100, rejoin.await().getInt("coins"))
        assertTrue(unload.isDone)
    }

    @Test
    fun `unload removes the player from the cache`() {
        val service = PlayerDataService(storage(), logger)
        val uuid = UUID.randomUUID()
        service.load(uuid).await()
        service.unload(uuid).await()
        assertFalse(service.isLoaded(uuid))
        assertNull(service.get(uuid))
    }

    @Test
    fun `a failed save keeps the changes for the next attempt`() {
        val storage = storage()
        val service = PlayerDataService(storage, logger)
        val uuid = UUID.randomUUID()
        service.load(uuid).await().set("gems", 7)

        storage.failSaves.set(true)
        assertThrows<ExecutionException> { service.unload(uuid).await() }
        assertTrue(service.isLoaded(uuid), "unsaved data must stay in memory")
        assertTrue(service.get(uuid)!!.isDirty)

        storage.failSaves.set(false)
        service.saveAll().await()
        assertTrue(storage.data[uuid]!!.contains("gems: 7"))
    }

    @Test
    fun `edit changes offline players in storage and online players in memory`() {
        val storage = storage()
        val service = PlayerDataService(storage, logger)
        val offline = UUID.randomUUID()

        service.edit(offline) { it.increment("visits") }.await()
        service.edit(offline) { it.increment("visits") }.await()
        assertEquals(2, service.fetch(offline).await().getInt("visits"))
        assertFalse(service.isLoaded(offline), "fetch/edit must not cache offline players")

        val online = UUID.randomUUID()
        val live = service.load(online).await()
        service.edit(online) { it.set("rank", "D") }.await()
        assertEquals("D", live.getString("rank"))
        assertSame(live, service.fetch(online).await())
    }

    @Test
    fun `import copies offline players and skips loaded ones`() {
        val source = storage()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val online = UUID.randomUUID()
        source.data[a] = "level: 3\n"
        source.data[b] = "level: 4\n"
        source.data[online] = "level: 99\n"

        val target = storage()
        val service = PlayerDataService(target, logger)
        service.load(online).await().set("level", 10)

        val result = service.importFrom(source).await()
        assertEquals(2, result.imported)
        assertEquals(1, result.skippedOnline)
        assertEquals(3, service.fetch(a).await().getInt("level"))
        assertEquals(4, service.fetch(b).await().getInt("level"))
        assertEquals(10, service.get(online)!!.getInt("level"))
    }

    @Test
    fun `unloadOffline drops players who are no longer online`() {
        val service = PlayerDataService(storage(), logger)
        val gone = UUID.randomUUID()
        val here = UUID.randomUUID()
        service.load(gone).await()
        service.load(here).await()
        service.unloadOffline({ it == here }, graceMillis = -1)
        // unload is queued per player; wait for it by queueing a read behind it
        service.fetch(gone).await()
        assertFalse(service.isLoaded(gone))
        assertNotNull(service.get(here))
    }

    @Test
    fun `close saves everything`() {
        val storage = storage(saveDelayMs = 50)
        val service = PlayerDataService(storage, logger)
        repeat(5) { service.load(UUID.randomUUID()).await().set("x", it) }
        service.close(10)
        assertEquals(5, storage.data.size)
    }
}
