package com.algopraxis.dhanniti.domain

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// Milestone 6: due reminders. The server stores the preference; the phone plans and schedules the notifications,
// so they fire offline. Wire format: backend/openapi.yaml.

@Serializable
data class ReminderSettings(
    val enabled: Boolean = true,
    /** Days before the due date; 0 = on the day. */
    @SerialName("offsets_days") val offsetsDays: List<Int> = listOf(5, 2, 0),
    /** "09:00", in local time. */
    @SerialName("time_of_day") val timeOfDay: String = "09:00",
    /** Nudge once a day while a due is overdue. */
    @SerialName("overdue_daily") val overdueDaily: Boolean = true,
) {
    val time: LocalTime get() = runCatching { LocalTime.parse(timeOfDay) }.getOrDefault(LocalTime(9, 0))
}

/** One notification to show at [fireAt]. [key] is stable, so replanning replaces rather than duplicates it. */
data class PlannedReminder(
    val key: String,
    val dueId: Long,
    val fireAt: Instant,
    val title: String,
    val text: String,
    /** Offer "Mark paid" from the notification: only when the amount is known. */
    val canMarkPaid: Boolean,
)

object ReminderPlanner {
    /**
     * Reminders for open dues at each offset before the due date, at the set time, skipping times already past.
     * Each overdue due gets one nudge at the next set time; replanning the next day schedules the next one.
     */
    fun plan(dues: List<Due>, settings: ReminderSettings, now: Instant, zone: TimeZone): List<PlannedReminder> {
        if (!settings.enabled) return emptyList()
        val today = now.toLocalDateTime(zone).date
        val time = settings.time

        return dues.flatMap { due ->
            when (due.status) {
                DueStatus.UPCOMING -> settings.offsetsDays.distinct().mapNotNull { offset ->
                    val fireAt = due.dueDate.minus(DatePeriod(days = offset)).atTime(time).toInstant(zone)
                    if (fireAt <= now) return@mapNotNull null
                    PlannedReminder(
                        key = "due-${due.id}-t$offset",
                        dueId = due.id,
                        fireAt = fireAt,
                        title = "${due.title}: ${amountLabel(due)}",
                        text = upcomingText(due, offset),
                        canMarkPaid = canMarkPaid(due),
                    )
                }
                DueStatus.OVERDUE -> {
                    if (!settings.overdueDaily) return@flatMap emptyList()
                    val todayAt = today.atTime(time).toInstant(zone)
                    val fireAt = if (todayAt > now) todayAt else today.plus(DatePeriod(days = 1)).atTime(time).toInstant(zone)
                    listOf(
                        PlannedReminder(
                            key = "due-${due.id}-overdue",
                            dueId = due.id,
                            fireAt = fireAt,
                            title = "Overdue: ${due.title}",
                            text = "${amountLabel(due)} was due ${due.dueDate.formatShort()}." + payHint(due),
                            canMarkPaid = canMarkPaid(due),
                        ),
                    )
                }
                DueStatus.PAID, DueStatus.SKIPPED -> emptyList()
            }
        }.sortedBy { it.fireAt }
    }

    private fun upcomingText(due: Due, offset: Int): String {
        val whenText = when (offset) {
            0 -> "Due today"
            1 -> "Due tomorrow"
            else -> "Due in $offset days, on ${due.dueDate.formatShort()}"
        }
        return "$whenText." + payHint(due)
    }

    private fun payHint(due: Due): String = when {
        due.isEstimate -> " The bill isn't entered yet: add it in Cards."
        due.autoDebit -> " It's auto-debited: just keep the money in the account."
        else -> ""
    }

    private fun amountLabel(due: Due): String =
        if (due.isEstimate && due.amount == Money.ZERO) "amount not known yet" else due.amount.formatInr()

    private fun canMarkPaid(due: Due): Boolean = due.amount > Money.ZERO && !due.isEstimate
}
