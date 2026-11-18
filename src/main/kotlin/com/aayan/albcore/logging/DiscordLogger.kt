package com.aayan.albcore.logging

import com.google.gson.Gson
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Sends messages and embeds to Discord webhooks, asynchronously.
 *
 * Webhooks are registered by name, either in ALBCore's config.yml (`discord.webhooks`) or in code:
 * ```
 * DiscordLogger.register("staff", url, username = "Skyabyss Logs")
 * DiscordLogger.on("staff").warning("Suspicious trade", "$player traded 64 diamonds")
 * ```
 * Webhook URLs are secrets: load them from config, never hard-code or commit them.
 * Mentions (@everyone, roles, users) are disabled in every payload, so logged player text cannot ping.
 */
object DiscordLogger {

    /** A registered webhook. */
    data class Webhook(val url: String, val username: String, val avatarUrl: String?) {
        override fun toString(): String = "Webhook(url=***, username=$username)"
    }

    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val gson = Gson()
    private val webhooks = ConcurrentHashMap<String, Webhook>()

    @Volatile
    internal var logger: Logger = Logger.getLogger("ALBCore")

    /** Registers (or replaces) the webhook called [name]. */
    fun register(name: String, url: String, username: String = "ALBCore Logger", avatarUrl: String? = null) {
        require(url.startsWith("https://")) { "Webhook URL must start with https://" }
        webhooks[name] = Webhook(url, username, avatarUrl)
    }

    fun unregister(name: String) {
        webhooks.remove(name)
    }

    internal fun clear() = webhooks.clear()

    fun isRegistered(name: String): Boolean = webhooks.containsKey(name)

    fun registeredNames(): Set<String> = webhooks.keys.toSortedSet()

    /** A sender for the webhook called [name]. Sending to an unknown name logs a warning and does nothing. */
    fun on(name: String): WebhookSender {
        val webhook = webhooks[name]
        if (webhook == null) logger.warning("[DiscordLogger] No webhook registered with name '$name'")
        return WebhookSender(name, webhook, client, gson)
    }
}

/** Sends to one webhook. Every method returns immediately; the HTTP request runs in the background. */
class WebhookSender internal constructor(
    private val name: String,
    private val webhook: DiscordLogger.Webhook?,
    private val client: HttpClient,
    private val gson: Gson,
) {
    /** Sends plain text (cut to Discord's 2000 character limit). */
    fun send(message: String): CompletableFuture<Boolean> = dispatch(textPayload(message))

    /** Sends one embed built with [block]. */
    fun embed(block: EmbedBuilder.() -> Unit): CompletableFuture<Boolean> = dispatch(embedPayload(EmbedBuilder().apply(block)))

    fun info(title: String, message: String) = colored(title, message, DiscordColor.BLUE)
    fun success(title: String, message: String) = colored(title, message, DiscordColor.GREEN)
    fun warning(title: String, message: String) = colored(title, message, DiscordColor.YELLOW)
    fun error(title: String, message: String) = colored(title, message, DiscordColor.RED)

    private fun colored(title: String, message: String, color: Int) = embed {
        this.title = title
        description = message
        this.color = color
        timestamp = true
    }

    internal fun textPayload(message: String): Map<String, Any> =
        basePayload() + ("content" to message.take(2000))

    internal fun embedPayload(builder: EmbedBuilder): Map<String, Any> =
        basePayload() + ("embeds" to listOf(builder.toMap()))

    private fun basePayload(): Map<String, Any> {
        val payload = linkedMapOf<String, Any>(
            "username" to (webhook?.username ?: "ALBCore Logger"),
            "allowed_mentions" to mapOf("parse" to emptyList<String>()),
        )
        webhook?.avatarUrl?.let { payload["avatar_url"] = it }
        return payload
    }

    private fun dispatch(payload: Map<String, Any>): CompletableFuture<Boolean> {
        val target = webhook ?: return CompletableFuture.completedFuture(false)
        val request = HttpRequest.newBuilder()
            .uri(URI.create(target.url))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
            .build()
        return client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
            .thenApply { response ->
                val ok = response.statusCode() in 200..299
                if (!ok) DiscordLogger.logger.warning("[DiscordLogger] Webhook '$name' answered HTTP ${response.statusCode()}")
                ok
            }
            .exceptionally { error ->
                DiscordLogger.logger.warning("[DiscordLogger] Could not reach webhook '$name': ${error.message}")
                false
            }
    }
}

/** Builds a Discord embed. Texts are cut to Discord's limits so the request is never rejected. */
class EmbedBuilder {
    var title: String? = null
    var description: String? = null
    var color: Int? = null
    var url: String? = null
    var footer: String? = null
    var thumbnail: String? = null
    var image: String? = null
    var timestamp: Boolean = false

    private val fields = mutableListOf<Map<String, Any>>()

    fun field(name: String, value: String, inline: Boolean = false) {
        if (fields.size < 25) fields += mapOf("name" to name.take(256), "value" to value.take(1024), "inline" to inline)
    }

    internal fun toMap(): Map<String, Any> {
        val embed = linkedMapOf<String, Any>()
        title?.let { embed["title"] = it.take(256) }
        description?.let { embed["description"] = it.take(4096) }
        color?.let { embed["color"] = it }
        url?.let { embed["url"] = it }
        if (fields.isNotEmpty()) embed["fields"] = fields.toList()
        if (timestamp) embed["timestamp"] = Instant.now().toString()
        footer?.let { embed["footer"] = mapOf("text" to it.take(2048)) }
        thumbnail?.let { embed["thumbnail"] = mapOf("url" to it) }
        image?.let { embed["image"] = mapOf("url" to it) }
        return embed
    }
}

/** Common embed colours. */
object DiscordColor {
    const val RED = 15158332
    const val GREEN = 3066993
    const val BLUE = 3447003
    const val YELLOW = 16776960
    const val ORANGE = 15105570
    const val PURPLE = 10181046
    const val CYAN = 1752220
    const val WHITE = 16777215
    const val BLACK = 2303786
    const val GRAY = 9807270
}
