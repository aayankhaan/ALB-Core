package com.aayan.albcore.hooks

import com.aayan.albcore.ALBCore
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.entity.Player

object VaultHook {

    private var economy: Economy? = null

    fun setup(plugin: ALBCore): Boolean {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            plugin.logger.warning("Vault not found! Economy features disabled.")
            return false
        }

        val rsp = Bukkit.getServicesManager().getRegistration(Economy::class.java)

        if (rsp == null) {
            plugin.logger.warning("No economy provider found! Economy features disabled.")
            return false
        }

        economy = rsp.provider
        plugin.logger.info("Vault hooked into ${economy!!.name}")
        return true
    }

    fun isLoaded(): Boolean = economy != null

    private fun getEconomy(): Economy? {
        if (economy == null) {
            Bukkit.getLogger().warning(
                "[ALBCore] Attempted to use VaultHook but Vault is not loaded."
            )
        }
        return economy
    }

    fun getMoney(player: Player): Double {
        return getEconomy()?.getBalance(player) ?: 0.0
    }

    fun hasMoney(player: Player, amount: Double): Boolean {
        return getEconomy()?.has(player, amount) ?: false
    }

    fun giveMoney(player: Player, amount: Double): Boolean {
        val response = getEconomy()?.depositPlayer(player, amount)
            ?: return false

        return response.transactionSuccess()
    }

    fun takeMoney(player: Player, amount: Double): Boolean {
        val response = getEconomy()?.withdrawPlayer(player, amount)
            ?: return false

        return response.transactionSuccess()
    }
}