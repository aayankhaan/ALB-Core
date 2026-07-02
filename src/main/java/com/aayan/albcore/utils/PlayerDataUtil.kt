package com.aayan.albcore.utils

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File

object PlayerDataUtil {

    private val folder = File("plugins/ALBCore/playerdata")

    private val cache = mutableMapOf<java.util.UUID, YamlConfiguration>()

    fun load(player: Player) {
        val file = getFile(player)
        cache[player.uniqueId] = YamlConfiguration.loadConfiguration(file)
    }

    fun save(player: Player) {
        val config = cache[player.uniqueId] ?: return
        config.save(getFile(player))
    }

    fun unload(player: Player) {
        save(player)
        cache.remove(player.uniqueId)
    }


    fun get(player: Player, path: String): Any? = getConfig(player).get(path)

    fun getString(player: Player, path: String, default: String = ""): String =
        getConfig(player).getString(path, default) ?: default

    fun getDouble(player: Player, path: String, default: Double = 0.0): Double =
        getConfig(player).getDouble(path, default)

    fun getInt(player: Player, path: String, default: Int = 0): Int =
        getConfig(player).getInt(path, default)

    fun getLong(player: Player, path: String, default: Long = 0L): Long =
        getConfig(player).getLong(path, default)

    fun getBoolean(player: Player, path: String, default: Boolean = false): Boolean =
        getConfig(player).getBoolean(path, default)

    fun getStringList(player: Player, path: String): List<String> =
        getConfig(player).getStringList(path)


    fun set(player: Player, path: String, value: Any?) {
        getConfig(player).set(path, value)
        save(player)
    }

    private fun getConfig(player: Player): YamlConfiguration {
        return cache.getOrPut(player.uniqueId) {
            YamlConfiguration.loadConfiguration(getFile(player))
        }
    }

    private fun getFile(player: Player): File {
        if (!folder.exists()) folder.mkdirs()
        val file = File(folder, "${player.uniqueId}.yml")
        if (!file.exists()) file.createNewFile()
        return file
    }

    fun saveAll() {
        cache.keys.forEach { uuid ->
            val file = File(folder, "$uuid.yml")
            cache[uuid]?.save(file)
        }
    }
}