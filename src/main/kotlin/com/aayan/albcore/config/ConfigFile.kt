package com.aayan.albcore.config

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * A YAML file in a plugin's data folder, for configs other than `config.yml` (e.g. `levels.yml`).
 *
 * On first use the file is copied from the plugin jar if the jar contains it. Keys missing from the
 * user's file fall back to the jar's version, so new options work without editing old files.
 *
 * ```
 * val levels = ConfigFile(this, "levels.yml")
 * val maxLevel = levels.config.getInt("max-level")
 * levels.reload()
 * ```
 *
 * Load and save on the main thread at startup or on admin commands only; they touch the disk.
 */
class ConfigFile(private val plugin: Plugin, val name: String) {

    val file = File(plugin.dataFolder, name)

    lateinit var config: YamlConfiguration
        private set

    init {
        reload()
    }

    /** Re-reads the file from disk (creating it from the jar first if needed) and returns it. */
    fun reload(): YamlConfiguration {
        if (!file.exists()) {
            if (plugin.getResource(name) != null) {
                plugin.saveResource(name, false)
            } else {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
        }
        val loaded = YamlConfiguration.loadConfiguration(file)
        plugin.getResource(name)?.use { stream ->
            loaded.setDefaults(YamlConfiguration.loadConfiguration(InputStreamReader(stream, StandardCharsets.UTF_8)))
        }
        config = loaded
        return loaded
    }

    /** Writes the current [config] back to the file. */
    fun save() {
        config.save(file)
    }
}
