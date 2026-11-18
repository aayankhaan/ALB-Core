@file:JvmName("Futures")

package com.aayan.albcore.utils

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Runs [action] on the server main thread once this future completes, and returns its result.
 * Use it to touch players, worlds or inventories with the result of a database query:
 *
 * ```
 * db.queryFirst("SELECT level FROM ${db.table("levels")} WHERE uuid = ?", uuid) { it.getInt(1) }
 *     .thenSync(plugin) { level -> player.sendMessage("Level ${level ?: 1}") }
 * ```
 *
 * If this future fails, [action] is skipped and the returned future fails too.
 * If [plugin] is disabled by then, [action] is skipped and the returned future is cancelled.
 */
fun <T, R> CompletableFuture<T>.thenSync(plugin: Plugin, action: (T) -> R): CompletableFuture<R> {
    val result = CompletableFuture<R>()
    whenComplete { value, error ->
        if (error != null) {
            result.completeExceptionally(error)
            return@whenComplete
        }
        val task = Runnable {
            try {
                result.complete(action(value))
            } catch (t: Throwable) {
                result.completeExceptionally(t)
            }
        }
        when {
            Bukkit.isPrimaryThread() -> task.run()
            plugin.isEnabled -> Bukkit.getScheduler().runTask(plugin, task)
            else -> result.cancel(false)
        }
    }
    return result
}

/** Logs [message] with the cause if this future fails. Returns the same future for chaining. */
fun <T> CompletableFuture<T>.logFailure(logger: Logger, message: String): CompletableFuture<T> =
    whenComplete { _, error ->
        if (error != null) {
            val cause = if (error is CompletionException && error.cause != null) error.cause!! else error
            logger.log(Level.WARNING, "$message: ${cause.message}", cause)
        }
    }
