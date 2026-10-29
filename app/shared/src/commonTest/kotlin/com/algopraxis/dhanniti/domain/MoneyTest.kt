package com.algopraxis.dhanniti.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyTest {
    @Test
    fun formatsWithIndianGrouping() {
        assertEquals("₹0", Money.ZERO.formatInr())
        assertEquals("₹999", Money.rupees(999).formatInr())
        assertEquals("₹1,000", Money.rupees(1_000).formatInr())
        assertEquals("₹45,250.50", Money(4_525_050).formatInr())
        assertEquals("₹1,23,45,678", Money.rupees(12_345_678).formatInr())
    }

    @Test
    fun formatsPaiseAndNegatives() {
        assertEquals("₹0.05", Money(5).formatInr())
        assertEquals("₹1,200.00", Money.rupees(1_200).formatInr(alwaysShowPaise = true))
        assertEquals("-₹3,100", Money.rupees(-3_100).formatInr())
    }

    @Test
    fun parsesWhatPeopleType() {
        assertEquals(Money(4_525_000), Money.parse("45250"))
        assertEquals(Money(4_525_050), Money.parse("45,250.5"))
        assertEquals(Money(12_345_678), Money.parse("₹ 1,23,456.78"))
        assertEquals(Money(5), Money.parse("0.05"))
        assertEquals(Money(100), Money.parse("1."))
    }

    @Test
    fun rejectsInvalidAmounts() {
        listOf("", "abc", "1.234", "-5", "1.2.3", "99999999999").forEach { input ->
            assertEquals(null, Money.parse(input), "expected null for \"$input\"")
        }
    }

    @Test
    fun inputStringRoundTrips() {
        listOf(0L, 5L, 50L, 100L, 4_525_050L, 4_525_055L).forEach { paise ->
            val text = Money(paise).toInputString()
            assertEquals(Money(paise), Money.parse(text), "round trip of $paise via \"$text\"")
        }
        assertEquals("45250.5", Money(4_525_050).toInputString())
    }

    @Test
    fun arithmeticStaysInPaise() {
        val salary = Money.rupees(1_20_000)
        val dues = Money(68_45_000) + Money(5)
        assertEquals(Money(51_54_995), salary - dues)
        assertEquals(true, (dues - salary).isNegative)
    }
}
