package com.aayan.albcore.hooks

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer

/**
 * Vault economy access. Works with any economy plugin that registers with Vault (or VaultUnlocked).
 *
 * The economy is looked up on every call, so it works even if the economy plugin enables after ALBCore.
 * Without Vault or an economy, reads return 0/false and writes return false.
 * Call from the main thread unless your economy plugin documents async support.
 */
object VaultHook {

    @Volatile
    private var vaultPresent = false

    internal fun setup(): Boolean {
        vaultPresent = Bukkit.getPluginManager().isPluginEnabled("Vault")
        return vaultPresent && VaultEconomy.provider() != null
    }

    /** True if Vault is installed and an economy plugin is registered right now. */
    fun isEnabled(): Boolean = vaultPresent && VaultEconomy.provider() != null

    fun getBalance(player: OfflinePlayer): Double =
        if (vaultPresent) VaultEconomy.provider()?.getBalance(player) ?: 0.0 else 0.0

    fun has(player: OfflinePlayer, amount: Double): Boolean =
        vaultPresent && VaultEconomy.provider()?.has(player, amount) == true

    /** Adds [amount]; returns true if the economy accepted it. */
    fun deposit(player: OfflinePlayer, amount: Double): Boolean {
        require(amount >= 0) { "amount must not be negative" }
        if (!vaultPresent) return false
        return VaultEconomy.provider()?.depositPlayer(player, amount)?.transactionSuccess() == true
    }

    /** Removes [amount]; returns true if the economy accepted it (false e.g. when the balance is too low). */
    fun withdraw(player: OfflinePlayer, amount: Double): Boolean {
        require(amount >= 0) { "amount must not be negative" }
        if (!vaultPresent) return false
        return VaultEconomy.provider()?.withdrawPlayer(player, amount)?.transactionSuccess() == true
    }

    /** The economy's own formatting of [amount] (e.g. "$1,000.00"), or the plain number without an economy. */
    fun format(amount: Double): String =
        (if (vaultPresent) VaultEconomy.provider()?.format(amount) else null) ?: amount.toString()
}

/** Kept separate so Vault classes are only loaded when Vault is installed. */
private object VaultEconomy {
    fun provider(): Economy? = Bukkit.getServicesManager().load(Economy::class.java)
}
