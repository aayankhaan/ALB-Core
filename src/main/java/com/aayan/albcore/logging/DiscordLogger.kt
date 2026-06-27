package com.aayan.albcore.logging

import com.aayan.albcore.ALBCore
import com.google.gson.Gson
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

object DiscordLogger {

    private val client = HttpClient.newHttpClient()
    private val gson = Gson()
    private val webhooks = mutableMapOf<String, String>()
    private var botUsername: String = "ALBCore Logger"
    private var botAvatarUrl: String? = null

    fun setup(name: String, url: String, username: String = "ALBCore Logger", avatarUrl: String? = null) {
        webhooks[name] = url
        botUsername = username
        botAvatarUrl = avatarUrl
    }

    fun isLoaded(name: String): Boolean = webhooks.containsKey(name)

    fun on(name: String): WebhookSender {
        val url = webhooks[name] ?: run {
            ALBCore.instance.logger.warning("[DiscordLogger] No webhook registered with name '$name'")
            return WebhookSender(null, client, gson, botUsername, botAvatarUrl)
        }
        return WebhookSender(url, client, gson, botUsername, botAvatarUrl)
    }
}

class WebhookSender(
    private val url: String?,
    private val client: HttpClient,
    private val gson: Gson,
    private val username: String,
    private val avatarUrl: String?
) {
    fun send(message: String) {
        val payload = mutableMapOf<String, Any>("content" to message, "username" to username)
        avatarUrl?.let { payload["avatar_url"] = it }
        dispatch(payload)
    }

    fun embed(block: EmbedBuilder.() -> Unit) {
        val builder = EmbedBuilder()
        builder.block()

        val embed = mutableMapOf<String, Any>()
        builder.title?.let { embed["title"] = it }
        builder.description?.let { embed["description"] = it }
        builder.color?.let { embed["color"] = it }
        builder.url?.let { embed["url"] = it }
        if (builder.fields.isNotEmpty()) embed["fields"] = builder.fields
        if (builder.timestamp) embed["timestamp"] = Instant.now().toString()
        builder.footer?.let { embed["footer"] = mapOf("text" to it) }
        builder.thumbnail?.let { embed["thumbnail"] = mapOf("url" to it) }
        builder.image?.let { embed["image"] = mapOf("url" to it) }

        val payload = mutableMapOf<String, Any>("embeds" to listOf(embed), "username" to username)
        avatarUrl?.let { payload["avatar_url"] = it }
        dispatch(payload)
    }

    fun info(title: String, message: String) = embed {
        this.title = title
        description = message
        color = DiscordColor.BLUE
        timestamp = true
    }

    fun success(title: String, message: String) = embed {
        this.title = title
        description = message
        color = DiscordColor.GREEN
        timestamp = true
    }

    fun warning(title: String, message: String) = embed {
        this.title = title
        description = message
        color = DiscordColor.YELLOW
        timestamp = true
    }

    fun error(title: String, message: String) = embed {
        this.title = title
        description = message
        color = DiscordColor.RED
        timestamp = true
    }

    private fun dispatch(payload: Map<String, Any>) {
        val webhookUrl = url ?: return

        val jsonBody = gson.toJson(payload)
        val request = HttpRequest.newBuilder()
            .uri(URI.create(webhookUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build()

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept { response ->
                if (response.statusCode() !in 200..299) {
                    ALBCore.instance.logger.warning("[DiscordLogger] Failed to send to '$webhookUrl'. HTTP: ${response.statusCode()}")
                }
            }
    }
}

class EmbedBuilder {
    var title: String? = null
    var description: String? = null
    var color: Int? = null
    var url: String? = null
    var footer: String? = null
    var thumbnail: String? = null
    var image: String? = null
    var timestamp: Boolean = false

    internal val fields = mutableListOf<Map<String, Any>>()

    fun field(name: String, value: String, inline: Boolean = false) {
        fields.add(mapOf("name" to name, "value" to value, "inline" to inline))
    }
}

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