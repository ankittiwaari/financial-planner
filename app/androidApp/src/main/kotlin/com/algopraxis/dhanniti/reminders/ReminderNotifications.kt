package com.algopraxis.dhanniti.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.workDataOf
import com.algopraxis.dhanniti.MainActivity
import com.algopraxis.dhanniti.R
import com.algopraxis.dhanniti.domain.PlannedReminder

/** What a reminder notification shows, carried in WorkManager input data so it survives reboots. */
data class ReminderContent(val dueId: Long, val title: String, val text: String, val canMarkPaid: Boolean) {
    fun toData(): Data = workDataOf(KEY_DUE_ID to dueId, KEY_TITLE to title, KEY_TEXT to text, KEY_CAN_PAY to canMarkPaid)

    /** One notification per due: a later reminder for the same due replaces the earlier one. */
    val notificationId: Int get() = (dueId % Int.MAX_VALUE).toInt()

    companion object {
        private const val KEY_DUE_ID = "due_id"
        private const val KEY_TITLE = "title"
        private const val KEY_TEXT = "text"
        private const val KEY_CAN_PAY = "can_mark_paid"

        fun from(reminder: PlannedReminder) = ReminderContent(reminder.dueId, reminder.title, reminder.text, reminder.canMarkPaid)

        fun from(data: Data): ReminderContent? {
            val dueId = data.getLong(KEY_DUE_ID, -1).takeIf { it > 0 } ?: return null
            return ReminderContent(dueId, data.getString(KEY_TITLE).orEmpty(), data.getString(KEY_TEXT).orEmpty(), data.getBoolean(KEY_CAN_PAY, false))
        }
    }
}

object ReminderNotifications {
    private const val CHANNEL_DUES = "dues"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_DUES, "Due reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "EMIs, card bills and recurring payments coming up or overdue"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /** A due reminder, with "Mark paid" (when the amount is known) and "Snooze 1 day". Tapping opens the app. */
    fun showReminder(context: Context, content: ReminderContent) {
        val builder = base(context, content.title, content.text)
            .addAction(0, "Snooze 1 day", actionIntent(context, ReminderActionReceiver.ACTION_SNOOZE, content))
        if (content.canMarkPaid) {
            builder.addAction(0, "Mark paid", actionIntent(context, ReminderActionReceiver.ACTION_PAY, content))
        }
        notify(context, content.notificationId, builder)
    }

    /** Shown when "Mark paid" from a notification couldn't go through. */
    fun showPaymentFailed(context: Context, content: ReminderContent, reason: String) {
        notify(context, content.notificationId, base(context, "Couldn't mark ${content.title.substringBefore(':')} paid", "$reason Open the app to pay it."))
    }

    fun cancel(context: Context, notificationId: Int) = NotificationManagerCompat.from(context).cancel(notificationId)

    private fun base(context: Context, title: String, text: String): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_DUES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
    }

    private fun actionIntent(context: Context, action: String, content: ReminderContent): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java)
            .setAction(action)
            .putExtra(ReminderActionReceiver.EXTRA_DUE_ID, content.dueId)
            .putExtra(ReminderActionReceiver.EXTRA_TITLE, content.title)
            .putExtra(ReminderActionReceiver.EXTRA_TEXT, content.text)
            .putExtra(ReminderActionReceiver.EXTRA_CAN_PAY, content.canMarkPaid)
        // Distinct request codes per due and action, or PendingIntents would overwrite each other.
        val requestCode = content.notificationId * 2 + if (action == ReminderActionReceiver.ACTION_PAY) 1 else 0
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @Suppress("MissingPermission") // checked in canNotify
    private fun notify(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (canNotify(context)) NotificationManagerCompat.from(context).notify(id, builder.build())
    }
}
