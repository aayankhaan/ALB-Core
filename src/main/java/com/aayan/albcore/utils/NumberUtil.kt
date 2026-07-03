package com.aayan.albcore.utils

object NumberUtil {

    fun formatNumber(n: Long): String {
        return when {
            n >= 1000000000000 -> String.format("%.1fT", n / 1000000000000.0)
            n >= 1000000000 -> String.format("%.1fB", n / 1000000000.0)
            n >= 1000000 -> String.format("%.1fM", n / 1000000.0)
            n >= 1000 -> String.format("%.1fK", n / 1000.0)
            else -> n.toString()
        }.replace(".0", "")
    }

    fun formatNumber(n: Double): String {
        return when {
            n >= 1000000000000 -> "%.2fT".format(n / 1000000000000)
            n >= 1000000000 -> "%.2fB".format(n / 1000000000)
            n >= 1000000 -> "%.2fM".format(n / 1000000)
            n >= 1000 -> "%.2fK".format(n / 1000)
            else -> "%.2f".format(n)
        }.replace(".00", "").replace(Regex("\\.([0-9])0$"), ".$1")
    }

    fun formatComma(n: Long): String {
        return "%,d".format(n)
    }

    fun parseNumber(input: String): Long? {
        val value = input.trim().uppercase()

        return when {
            value.endsWith("T") -> runCatching { (value.dropLast(1).toDouble() * 1000000000000).toLong() }.getOrNull()
            value.endsWith("B") -> runCatching { (value.dropLast(1).toDouble() * 1000000000).toLong() }.getOrNull()
            value.endsWith("M") -> runCatching { (value.dropLast(1).toDouble() * 1000000).toLong() }.getOrNull()
            value.endsWith("K") -> runCatching { (value.dropLast(1).toDouble() * 1000).toLong() }.getOrNull()
            else -> runCatching { value.toDouble().toLong() }.getOrNull()
        }
    }
}