package com.algopraxis.dhanniti.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.algopraxis.dhanniti.data.reminders.ReminderScheduler
import com.algopraxis.dhanniti.domain.PlannedReminder
import java.util.concurrent.TimeUnit
import kotlin.time.Clock

/**
 * Schedules each planned reminder as a delayed WorkManager job. WorkManager keeps them across app restarts and
 * reboots. Timing is approximate: Android may hold work back a little in Doze, which is fine for a 9 am nudge.
 */
class AndroidReminderScheduler(private val context: Context, private val clock: Clock = Clock.System) : ReminderScheduler {
    override fun schedule(reminders: List<PlannedReminder>) {
        val workManager = WorkManager.getInstance(context)
        // Replace the whole plan: reminders for dues paid or deleted since simply aren't re-added.
        workManager.cancelAllWorkByTag(TAG_PLANNED)
        val now = clock.now()
        reminders.forEach { reminder ->
            val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay((reminder.fireAt - now).inWholeMilliseconds.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setInputData(ReminderContent.from(reminder).toData())
                .addTag(TAG_PLANNED)
                .build()
            workManager.enqueueUniqueWork("reminder:${reminder.key}", ExistingWorkPolicy.REPLACE, request)
        }
    }

    override fun cancelAll() {
        WorkManager.getInstance(context).apply {
            cancelAllWorkByTag(TAG_PLANNED)
            cancelAllWorkByTag(TAG_SNOOZED)
        }
    }

    companion object {
        const val TAG_PLANNED = "due-reminder"
        const val TAG_SNOOZED = "due-reminder-snoozed"
    }
}

/** The notification's "Mark paid" and "Snooze 1 day" buttons. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val content = ReminderContent(
            dueId = intent.getLongExtra(EXTRA_DUE_ID, -1).takeIf { it > 0 } ?: return,
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            text = intent.getStringExtra(EXTRA_TEXT).orEmpty(),
            canMarkPaid = intent.getBooleanExtra(EXTRA_CAN_PAY, false),
        )
        ReminderNotifications.cancel(context, content.notificationId)
        val workManager = WorkManager.getInstance(context)

        when (intent.action) {
            ACTION_PAY -> workManager.enqueueUniqueWork(
                "pay:${content.dueId}",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PayDueWorker>()
                    .setInputData(content.toData())
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
            ACTION_SNOOZE -> workManager.enqueueUniqueWork(
                "snooze:${content.dueId}",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReminderWorker>()
                    .setInitialDelay(1, TimeUnit.DAYS)
                    .setInputData(content.toData())
                    .addTag(AndroidReminderScheduler.TAG_SNOOZED)
                    .build(),
            )
        }
    }

    companion object {
        const val ACTION_PAY = "com.algopraxis.dhanniti.reminders.PAY"
        const val ACTION_SNOOZE = "com.algopraxis.dhanniti.reminders.SNOOZE"
        const val EXTRA_DUE_ID = "due_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_CAN_PAY = "can_mark_paid"
    }
}

/**
 * After a reboot, or a clock or timezone change, replan from the server: WorkManager restores scheduled work by
 * itself, but times worked out before a timezone change would be off.
 */
class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED ->
                ReminderSyncWorker.enqueueNow(context)
        }
    }
}
