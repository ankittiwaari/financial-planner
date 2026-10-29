package com.algopraxis.dhanniti.domain

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlin.time.Clock

fun today(clock: Clock = Clock.System, zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate =
    clock.todayIn(zone)

/** "2026-10": the pay period key the API uses. */
fun LocalDate.periodKey(): String = "$year-${month.number.toString().padStart(2, '0')}"

fun LocalDate.startOfMonth(): LocalDate = LocalDate(year, month, 1)

fun LocalDate.endOfMonth(): LocalDate = startOfMonth().plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "28 Oct 2026". */
fun LocalDate.formatShort(): String = "$day ${MONTHS[month.number - 1]} $year"

/** "Today", "Yesterday", "Tomorrow", or "28 Oct 2026". */
fun LocalDate.formatRelative(today: LocalDate): String = when (this) {
    today -> "Today"
    today.minus(DatePeriod(days = 1)) -> "Yesterday"
    today.plus(DatePeriod(days = 1)) -> "Tomorrow"
    else -> formatShort()
}

/** "2026-10" → "Oct 2026". Falls back to the key itself if it isn't a valid period. */
fun formatPeriod(period: String): String {
    val parts = period.split("-")
    val month = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..12 } ?: return period
    return "${MONTHS[month - 1]} ${parts[0]}"
}
