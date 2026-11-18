package com.aayan.albcore.utils

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Number formatting for players: `1500` gives `"1.5K"`, `"2.5m"` parses to `2500000`.
 * Output never depends on the server's locale.
 */
object NumberUtil {

    private val suffixes = arrayOf("", "K", "M", "B", "T", "Q")
    private val thousand = BigDecimal(1000)
    private val multipliers: Map<Char, BigDecimal> =
        suffixes.drop(1).mapIndexed { index, suffix -> suffix[0] to thousand.pow(index + 1) }.toMap()

    /** Short form with at most one decimal: 999, 1.5K, 12.3M, 1B, -2.5K. */
    fun formatNumber(n: Long): String = compact(BigDecimal.valueOf(n), 1)

    /** Short form with at most two decimals: 12.5, 1.25K, 3.33M. */
    fun formatNumber(n: Double): String {
        if (n.isNaN() || n.isInfinite()) return n.toString()
        return compact(BigDecimal.valueOf(n), 2)
    }

    /** Thousands separators: 1234567 gives "1,234,567". */
    fun formatComma(n: Long): String = String.format(Locale.ROOT, "%,d", n)

    /** Thousands separators with up to [decimals] decimals: 1234.5 gives "1,234.5". */
    fun formatComma(n: Double, decimals: Int = 2): String {
        if (n.isNaN() || n.isInfinite()) return n.toString()
        val rounded = BigDecimal.valueOf(n).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros()
        val scale = rounded.scale().coerceAtLeast(0)
        return String.format(Locale.ROOT, "%,.${scale}f", rounded)
    }

    /**
     * Reads player input such as `"1500"`, `"1.5k"`, `"2M"`, `"1,000"` or `"3b"`.
     * Decimals are cut off (`"1.9"` gives 1). Returns null for invalid or out-of-range input.
     */
    fun parseNumber(input: String): Long? {
        val value = input.trim().replace(",", "").replace("_", "").uppercase(Locale.ROOT)
        if (value.isEmpty()) return null
        val multiplier = multipliers[value.last()]
        val digits = if (multiplier != null) value.dropLast(1) else value
        val number = digits.toBigDecimalOrNull() ?: return null
        val result = if (multiplier != null) number.multiply(multiplier) else number
        return try {
            result.setScale(0, RoundingMode.DOWN).longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun compact(value: BigDecimal, decimals: Int): String {
        var abs = value.abs()
        var tier = 0
        while (tier < suffixes.lastIndex && abs >= thousand) {
            abs = abs.divide(thousand)
            tier++
        }
        var rounded = abs.setScale(decimals, RoundingMode.HALF_UP)
        // 999.95K rounds to 1000.0K: show it as 1M instead.
        if (rounded >= thousand && tier < suffixes.lastIndex) {
            rounded = rounded.divide(thousand).setScale(decimals, RoundingMode.HALF_UP)
            tier++
        }
        val sign = if (value.signum() < 0 && rounded.signum() != 0) "-" else ""
        return sign + rounded.stripTrailingZeros().toPlainString() + suffixes[tier]
    }
}
