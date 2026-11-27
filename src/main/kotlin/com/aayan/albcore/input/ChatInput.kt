package com.aayan.albcore.input

import com.aayan.albcore.ALBCore
import com.aayan.albcore.utils.MessageUtil
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * "Type it in chat" input. Works for Bedrock players too (unlike anvil or sign input).
 *
 * While a question is open, the player's chat messages go to the handler instead of chat (the chat
 * event is cancelled at LOWEST priority, so chat plugins that ignore cancelled events never see them).
 *
 * ```
 * ChatInput.ask(plugin, player, prompt = "&7Type a name for your home.", onCancel = { reopenMenu(it) }) { text ->
 *     if (text.length > 16) { MessageUtil.send(player, "&cAt most 16 letters. Try again."); false }   // keep asking
 *     else { createHome(player, text); reopenMenu(player); true }                                        // done
 * }
 * ```
 *
 * - The handler runs on the main thread with the plain text (trimmed). Return true when finished,
 *   false to keep listening (e.g. to ask again after a wrong answer).
 * - Typing a cancel word (ALBCore lang.yml `chat-input.cancel-words`, default "cancel") or waiting
 *   longer than the timeout ends the question and runs [onCancel] (e.g. to reopen the menu).
 * - The text is what the player typed: escape it with `ColorUtil.escape` before showing it to others.
 * - A new question replaces an open one. Questions end without callbacks when the player quits or the
 *   asking plugin disables.
 */
object ChatInput {

    private class Question(
        val plugin: Plugin,
        val handler: (String) -> Boolean,
        val onCancel: ((Player) -> Unit)?,
        val cancelWords: List<String>?,
    ) {
        var timeout: BukkitTask? = null
    }

    private val open = ConcurrentHashMap<UUID, Question>()

    /**
     * Asks [player] to type something. [prompt] is sent first (with ALBCore's cancel hint when [hint]
     * is true). The menu the player has open is closed when [closeInventory] is true, so they can type.
     * [timeoutSeconds] <= 0 waits until the player answers, cancels or quits. [cancelWords] replaces
     * ALBCore's cancel words for this question; an empty list gives every message to the handler.
     */
    fun ask(
        plugin: Plugin,
        player: Player,
        prompt: String? = null,
        timeoutSeconds: Long = 60,
        closeInventory: Boolean = true,
        hint: Boolean = true,
        onCancel: ((Player) -> Unit)? = null,
        cancelWords: List<String>? = null,
        handler: (String) -> Boolean,
    ) {
        val question = Question(plugin, handler, onCancel, cancelWords)
        val words = cancelWords ?: cancelWords()
        open.put(player.uniqueId, question)?.timeout?.cancel()
        if (closeInventory) player.closeInventory()
        if (!prompt.isNullOrBlank()) MessageUtil.sendWithPAPI(player, prompt)
        if (hint && words.isNotEmpty()) lang()?.send(player, "chat-input.hint", "word" to words.first())
        if (timeoutSeconds > 0) {
            question.timeout = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (!open.remove(player.uniqueId, question)) return@Runnable
                if (!player.isOnline) return@Runnable
                lang()?.send(player, "chat-input.timed-out")
                question.onCancel?.invoke(player)
            }, timeoutSeconds * 20)
        }
    }

    /** True while [player] has an open question. */
    fun isWaiting(player: Player): Boolean = open.containsKey(player.uniqueId)

    /** Ends [player]'s question as if they typed a cancel word (runs its onCancel). */
    fun cancel(player: Player) {
        val question = open.remove(player.uniqueId) ?: return
        question.timeout?.cancel()
        question.onCancel?.invoke(player)
    }

    /** Ends [player]'s question silently (no message, no onCancel). */
    fun close(player: Player) {
        open.remove(player.uniqueId)?.timeout?.cancel()
    }

    /** True if [text] is one of the cancel words (case is ignored). */
    fun isCancelWord(text: String, words: List<String> = cancelWords()): Boolean =
        words.any { it.isNotBlank() && it.trim().equals(text.trim(), ignoreCase = true) }

    internal fun unregisterAll(plugin: Plugin) {
        open.entries.removeIf { (_, question) ->
            (question.plugin == plugin).also { if (it) question.timeout?.cancel() }
        }
    }

    internal fun clear() {
        open.values.forEach { it.timeout?.cancel() }
        open.clear()
    }

    private fun lang() = runCatching { ALBCore.instance.lang }.getOrNull()

    private fun cancelWords(): List<String> =
        lang()?.lines("chat-input.cancel-words")?.filter { it.isNotBlank() }?.ifEmpty { null } ?: listOf("cancel")

    internal class ChatListener : Listener {

        @EventHandler(priority = EventPriority.LOWEST)
        fun onChat(event: AsyncChatEvent) {
            val player = event.player
            val question = open[player.uniqueId] ?: return
            event.isCancelled = true
            val text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim()
            if (!question.plugin.isEnabled) return
            Bukkit.getScheduler().runTask(question.plugin, Runnable {
                if (open[player.uniqueId] !== question) return@Runnable
                if (isCancelWord(text, question.cancelWords ?: cancelWords())) {
                    open.remove(player.uniqueId, question)
                    question.timeout?.cancel()
                    lang()?.send(player, "chat-input.cancelled")
                    question.onCancel?.invoke(player)
                    return@Runnable
                }
                if (question.handler(text) && open.remove(player.uniqueId, question)) question.timeout?.cancel()
            })
        }

        @EventHandler
        fun onQuit(event: PlayerQuitEvent) {
            close(event.player)
        }
    }
}
