package com.aayan.albcore.config

import com.aayan.albcore.data.PlayerDataStorageType
import com.aayan.albcore.database.DatabaseSettings
import com.aayan.albcore.database.DatabaseType
import com.aayan.albcore.database.MySqlSettings
import com.aayan.albcore.database.PoolSettings
import org.bukkit.configuration.ConfigurationSection
import java.io.File

/** Player data settings from the `player-data` section. */
data class PlayerDataSettings(
    val storage: PlayerDataStorageType,
    val autosaveIntervalSeconds: Int,
    val loadTimeoutSeconds: Long,
    val loadFailedMessage: String,
)

/** One named Discord webhook from the `discord.webhooks` section. */
data class WebhookSettings(val url: String, val username: String, val avatarUrl: String?) {
    /** Never prints the URL (it is a secret). */
    override fun toString(): String = "WebhookSettings(url=***, username=$username)"
}

/** A menu sound: a sound key with volume and pitch. A blank [key] plays nothing. */
data class GuiSound(val key: String, val volume: Float = 1f, val pitch: Float = 1f) {
    companion object {
        /** "minecraft:ui.button.click 0.4 1.2" (volume and pitch optional), or "" / "none" for no sound. Null if invalid. */
        fun parse(raw: String): GuiSound? {
            val parts = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isEmpty() || parts[0].equals("none", ignoreCase = true)) return GuiSound("")
            if (parts.size > 3 || !Regex("[a-z0-9_.-]+(:[a-z0-9_./-]+)?").matches(parts[0])) return null
            val volume = parts.getOrNull(1)?.toFloatOrNull() ?: if (parts.size > 1) return null else 1f
            val pitch = parts.getOrNull(2)?.toFloatOrNull() ?: if (parts.size > 2) return null else 1f
            return GuiSound(parts[0], volume, pitch)
        }
    }
}

/** Menu settings from the `gui` section. */
data class GuiSettings(
    val clickCooldownMs: Long = 100,
    val click: GuiSound = GuiSound("minecraft:ui.button.click", 0.4f, 1.2f),
    val toggleOn: GuiSound = GuiSound("minecraft:block.note_block.pling", 0.5f, 1.7f),
    val toggleOff: GuiSound = GuiSound("minecraft:block.note_block.pling", 0.5f, 0.9f),
    val denied: GuiSound = GuiSound("minecraft:entity.villager.no", 0.5f, 1.1f),
    val success: GuiSound = GuiSound("minecraft:entity.player.levelup", 0.5f, 1.6f),
)

/** ALBCore's config.yml, parsed and validated. Invalid values fall back to defaults with a warning. */
data class CoreConfig(
    val database: DatabaseSettings,
    val playerData: PlayerDataSettings,
    val webhooks: Map<String, WebhookSettings>,
    val gui: GuiSettings = GuiSettings(),
) {
    companion object {
        const val CURRENT_VERSION = 1

        /**
         * Reads [config] (normally `plugin.config`, which falls back to the bundled defaults).
         * [dataFolder] resolves relative file paths; [warn] receives a message for every invalid value.
         */
        fun load(config: ConfigurationSection, dataFolder: File, warn: (String) -> Unit): CoreConfig {
            val version = config.getInt("config-version", CURRENT_VERSION)
            if (version > CURRENT_VERSION) {
                warn("config.yml has config-version $version, newer than this ALBCore supports ($CURRENT_VERSION).")
            }
            return CoreConfig(
                database = loadDatabase(config, dataFolder, warn),
                playerData = loadPlayerData(config, warn),
                webhooks = loadWebhooks(config, warn),
                gui = loadGui(config, warn),
            )
        }

        private fun loadGui(config: ConfigurationSection, warn: (String) -> Unit): GuiSettings {
            val defaults = GuiSettings()
            var cooldown = config.getLong("gui.click-cooldown-ms", defaults.clickCooldownMs)
            if (cooldown < 0) {
                warn("gui.click-cooldown-ms must be 0 or more (got $cooldown). Using ${defaults.clickCooldownMs}.")
                cooldown = defaults.clickCooldownMs
            }
            fun sound(name: String, default: GuiSound): GuiSound {
                val raw = config.getString("gui.sounds.$name") ?: return default
                return GuiSound.parse(raw) ?: default.also {
                    warn("gui.sounds.$name '$raw' is not valid (use \"<sound key> [volume] [pitch]\" or \"\"). Using the default.")
                }
            }
            return GuiSettings(
                clickCooldownMs = cooldown,
                click = sound("click", defaults.click),
                toggleOn = sound("toggle-on", defaults.toggleOn),
                toggleOff = sound("toggle-off", defaults.toggleOff),
                denied = sound("denied", defaults.denied),
                success = sound("success", defaults.success),
            )
        }

        private fun loadDatabase(config: ConfigurationSection, dataFolder: File, warn: (String) -> Unit): DatabaseSettings {
            val typeName = config.getString("database.type", "sqlite")
            val type = DatabaseType.parse(typeName) ?: DatabaseType.SQLITE.also {
                warn("database.type '$typeName' is not valid (use sqlite or mysql). Using sqlite.")
            }

            var prefix = config.getString("database.table-prefix", "alb_") ?: "alb_"
            if (!Regex("[A-Za-z0-9_]{0,32}").matches(prefix)) {
                warn("database.table-prefix '$prefix' may only contain letters, digits and _. Using alb_.")
                prefix = "alb_"
            }

            val sqliteFile = File(config.getString("database.sqlite.file", "database.db") ?: "database.db")
            val propertiesSection = config.getConfigurationSection("database.mysql.properties")
            val properties = propertiesSection?.getKeys(false)
                ?.associateWith { propertiesSection.getString(it).orEmpty() }
                .orEmpty()

            return DatabaseSettings(
                type = type,
                sqliteFile = if (sqliteFile.isAbsolute) sqliteFile else File(dataFolder, sqliteFile.path),
                mysql = MySqlSettings(
                    host = config.getString("database.mysql.host", "localhost") ?: "localhost",
                    port = positive(config, "database.mysql.port", 3306, warn),
                    database = config.getString("database.mysql.database", "minecraft") ?: "minecraft",
                    username = config.getString("database.mysql.username", "root") ?: "root",
                    password = config.getString("database.mysql.password", "") ?: "",
                    useSsl = config.getBoolean("database.mysql.use-ssl", false),
                    properties = properties,
                ),
                pool = PoolSettings(
                    maximumPoolSize = positive(config, "database.pool.maximum-pool-size", 10, warn),
                    minimumIdle = config.getInt("database.pool.minimum-idle", 2).coerceAtLeast(0),
                    connectionTimeoutMs = positiveLong(config, "database.pool.connection-timeout-ms", 10_000, warn),
                    idleTimeoutMs = positiveLong(config, "database.pool.idle-timeout-ms", 600_000, warn),
                    maxLifetimeMs = positiveLong(config, "database.pool.max-lifetime-ms", 1_800_000, warn),
                    keepaliveTimeMs = config.getLong("database.pool.keepalive-time-ms", 0).coerceAtLeast(0),
                ),
                tablePrefix = prefix,
            )
        }

        private fun loadPlayerData(config: ConfigurationSection, warn: (String) -> Unit): PlayerDataSettings {
            val storageName = config.getString("player-data.storage", "yaml")
            val storage = PlayerDataStorageType.parse(storageName) ?: PlayerDataStorageType.YAML.also {
                warn("player-data.storage '$storageName' is not valid (use yaml, sqlite or mysql). Using yaml.")
            }
            return PlayerDataSettings(
                storage = storage,
                autosaveIntervalSeconds = positive(config, "player-data.autosave-interval-seconds", 300, warn),
                loadTimeoutSeconds = positiveLong(config, "player-data.load-timeout-seconds", 10, warn),
                loadFailedMessage = config.getString(
                    "player-data.load-failed-message",
                    "&cYour player data could not be loaded. Please try again in a moment.",
                )!!,
            )
        }

        private fun loadWebhooks(config: ConfigurationSection, warn: (String) -> Unit): Map<String, WebhookSettings> {
            val section = config.getConfigurationSection("discord.webhooks") ?: return emptyMap()
            val result = LinkedHashMap<String, WebhookSettings>()
            for (name in section.getKeys(false)) {
                val url = section.getString("$name.url").orEmpty()
                if (!url.startsWith("https://")) {
                    warn("discord.webhooks.$name.url is missing or not an https URL; webhook skipped.")
                    continue
                }
                result[name] = WebhookSettings(
                    url = url,
                    username = section.getString("$name.username", "ALBCore Logger") ?: "ALBCore Logger",
                    avatarUrl = section.getString("$name.avatar-url")?.takeIf { it.isNotBlank() },
                )
            }
            return result
        }

        private fun positive(config: ConfigurationSection, path: String, default: Int, warn: (String) -> Unit): Int {
            val value = config.getInt(path, default)
            if (value > 0) return value
            warn("$path must be greater than 0 (got $value). Using $default.")
            return default
        }

        private fun positiveLong(config: ConfigurationSection, path: String, default: Long, warn: (String) -> Unit): Long {
            val value = config.getLong(path, default)
            if (value > 0) return value
            warn("$path must be greater than 0 (got $value). Using $default.")
            return default
        }
    }
}
