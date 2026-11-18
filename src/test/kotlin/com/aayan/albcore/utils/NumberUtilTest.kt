package com.aayan.albcore.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.Locale

class NumberUtilTest {

    @ParameterizedTest
    @CsvSource(
        "0, 0",
        "999, 999",
        "1000, 1K",
        "1500, 1.5K",
        "1050, 1.1K",
        "999949, 999.9K",
        "999950, 1M",
        "1234567, 1.2M",
        "1000000000, 1B",
        "2500000000000, 2.5T",
        "-1500, -1.5K",
        "-999, -999",
    )
    fun `formats longs with one decimal`(input: Long, expected: String) {
        assertEquals(expected, NumberUtil.formatNumber(input))
    }

    @ParameterizedTest
    @CsvSource(
        "0.0, 0",
        "12.0, 12",
        "12.5, 12.5",
        "12.345, 12.35",
        "1500.0, 1.5K",
        "1234.5, 1.23K",
        "1250.0, 1.25K",
        "999999.0, 1M",
        "3333333.0, 3.33M",
        "-0.001, 0",
    )
    fun `formats doubles with up to two decimals`(input: Double, expected: String) {
        assertEquals(expected, NumberUtil.formatNumber(input))
    }

    @Test
    fun `formatting ignores the default locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.5K", NumberUtil.formatNumber(1500L))
            assertEquals("1,234,567", NumberUtil.formatComma(1234567L))
            assertEquals("1,234.5", NumberUtil.formatComma(1234.5))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @ParameterizedTest
    @CsvSource(
        "1500, 1500",
        "1.5k, 1500",
        "1.5K, 1500",
        "2m, 2000000",
        "3B, 3000000000",
        "1t, 1000000000000",
        "'1,000', 1000",
        "1.005K, 1005",
        "0.3k, 300",
        "1.9, 1",
        "' 42 ', 42",
        "-2k, -2000",
    )
    fun `parses player input`(input: String, expected: Long) {
        assertEquals(expected, NumberUtil.parseNumber(input))
    }

    @ParameterizedTest
    @CsvSource("''", "k", "abc", "1.5x", "99999999999999999999")
    fun `rejects invalid input`(input: String) {
        assertNull(NumberUtil.parseNumber(input))
    }
}
