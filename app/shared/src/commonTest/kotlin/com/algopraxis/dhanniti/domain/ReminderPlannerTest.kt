package com.algopraxis.dhanniti.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReminderPlannerTest {
    private val zone = TimeZone.of("Asia/Kolkata")

    private fun at(date: String, time: String = "10:00") = LocalDateTime.parse("${date}T$time").toInstant(zone)

    private fun due(
        id: Long,
        date: String,
        status: DueStatus = DueStatus.UPCOMING,
        amount: Long = 3_100_000,
        estimate: Boolean = false,
        autoDebit: Boolean = false,
    ) = Due(
        id = id, sourceType = DueSource.LOAN, sourceId = 1, period = date.take(7), title = "Home loan EMI",
        amount = Money(amount), dueDate = LocalDate.parse(date), status = status, isEstimate = estimate, autoDebit = autoDebit,
    )

    @Test
    fun remindsAtEachOffsetAtTheSetTimeSkippingThePast() {
        // Tue 3 Nov, 10:00. The EMI is due Thu 5 Nov: T-5 has passed, T-2 (today 9:00) too, T-0 is ahead.
        val plan = ReminderPlanner.plan(
            listOf(due(1, "2026-11-05"), due(2, "2026-11-12")),
            ReminderSettings(offsetsDays = listOf(5, 2, 0), timeOfDay = "09:00"),
            now = at("2026-11-03"),
            zone = zone,
        )

        assertEquals(
            listOf("due-1-t0" to at("2026-11-05", "09:00"), "due-2-t5" to at("2026-11-07", "09:00"), "due-2-t2" to at("2026-11-10", "09:00"), "due-2-t0" to at("2026-11-12", "09:00")),
            plan.map { it.key to it.fireAt },
        )
        assertEquals("Home loan EMI: ₹31,000", plan[0].title)
        assertEquals("Due today.", plan[0].text)
        assertEquals("Due in 5 days, on 12 Nov 2026.", plan[1].text)
        assertTrue(plan.all { it.canMarkPaid })
    }

    @Test
    fun remindsTwoDaysOutLaterToday() {
        val plan = ReminderPlanner.plan(listOf(due(1, "2026-11-05")), ReminderSettings(offsetsDays = listOf(2), timeOfDay = "18:30"), at("2026-11-03"), zone)

        assertEquals(listOf(at("2026-11-03", "18:30")), plan.map { it.fireAt })
        assertEquals("Due in 2 days, on 5 Nov 2026.", plan.single().text)
    }

    @Test
    fun nudgesOverdueDuesOnceAtTheNextSetTime() {
        val dues = listOf(due(1, "2026-11-01", DueStatus.OVERDUE))

        val beforeTime = ReminderPlanner.plan(dues, ReminderSettings(timeOfDay = "11:00"), at("2026-11-03"), zone).single()
        val afterTime = ReminderPlanner.plan(dues, ReminderSettings(timeOfDay = "09:00"), at("2026-11-03"), zone).single()

        assertEquals(at("2026-11-03", "11:00"), beforeTime.fireAt)
        assertEquals(at("2026-11-04", "09:00"), afterTime.fireAt)
        assertEquals("Overdue: Home loan EMI", afterTime.title)
        assertEquals("₹31,000 was due 1 Nov 2026.", afterTime.text)
        assertTrue(ReminderPlanner.plan(dues, ReminderSettings(overdueDaily = false), at("2026-11-03"), zone).isEmpty())
    }

    @Test
    fun explainsEstimatesAndAutoDebitsAndOnlyOffersMarkPaidForKnownAmounts() {
        val plan = ReminderPlanner.plan(
            listOf(due(1, "2026-11-05", amount = 0, estimate = true), due(2, "2026-11-05", autoDebit = true)),
            ReminderSettings(offsetsDays = listOf(0)),
            at("2026-11-03"),
            zone,
        )

        assertEquals("Home loan EMI: amount not known yet", plan[0].title)
        assertEquals("Due today. The bill isn't entered yet: add it in Cards.", plan[0].text)
        assertFalse(plan[0].canMarkPaid)
        assertEquals("Due today. It's auto-debited: just keep the money in the account.", plan[1].text)
    }

    @Test
    fun plansNothingWhenOffOrForSettledDues() {
        val dues = listOf(due(1, "2026-11-05"), due(2, "2026-11-05", DueStatus.PAID), due(3, "2026-11-05", DueStatus.SKIPPED))

        assertTrue(ReminderPlanner.plan(dues, ReminderSettings(enabled = false), at("2026-11-03"), zone).isEmpty())
        assertEquals(setOf(1L), ReminderPlanner.plan(dues, ReminderSettings(), at("2026-11-03"), zone).map { it.dueId }.toSet())
    }
}
