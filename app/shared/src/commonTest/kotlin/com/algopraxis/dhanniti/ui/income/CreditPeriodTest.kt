package com.algopraxis.dhanniti.ui.income

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class CreditPeriodTest {
    @Test
    fun offersLastThisAndNextMonthAcrossYearEnd() {
        assertEquals(listOf("2025-12", "2026-01", "2026-02"), IncomeViewModel.creditPeriodChoices(LocalDate(2026, 1, 15)))
        assertEquals(listOf("2026-11", "2026-12", "2027-01"), IncomeViewModel.creditPeriodChoices(LocalDate(2026, 12, 31)))
    }

    @Test
    fun defaultsToThisMonthUnlessAlreadyLogged() {
        val choices = IncomeViewModel.creditPeriodChoices(LocalDate(2026, 10, 29))
        assertEquals("2026-10", IncomeViewModel.defaultCreditPeriod(choices, lastCredited = null))
        assertEquals("2026-10", IncomeViewModel.defaultCreditPeriod(choices, lastCredited = "2026-09"))
        assertEquals("2026-11", IncomeViewModel.defaultCreditPeriod(choices, lastCredited = "2026-10"))
    }
}
