package com.algopraxis.dhanniti.reminders

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.algopraxis.dhanniti.data.api.ApiException
import com.algopraxis.dhanniti.data.api.ObligationsApi
import com.algopraxis.dhanniti.data.api.PayDueInput
import com.algopraxis.dhanniti.data.api.userMessage
import com.algopraxis.dhanniti.data.auth.TokenStore
import com.algopraxis.dhanniti.data.reminders.ReminderScheduler
import com.algopraxis.dhanniti.domain.ReminderPlanner
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit
import kotlin.time.Clock

/** Shows one due reminder, unless the due has been paid or skipped since it was scheduled. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val api: ObligationsApi by inject()
    private val tokenStore: TokenStore by inject()

    override suspend fun doWork(): Result {
        val content = ReminderContent.from(inputData) ?: return Result.success()
        if (tokenStore.token() == null) return Result.success() // signed out since
        // Offline or slow: remind anyway. Better a reminder for a paid due than none for an unpaid one.
        val stillOpen = withTimeoutOrNull(10_000) { runCatching { api.due(content.dueId).isOpen }.getOrNull() } ?: true
        if (stillOpen) ReminderNotifications.showReminder(applicationContext, content)
        return Result.success()
    }
}

/** "Mark paid" from a notification. Waits for a network connection, so it's queued while offline. */
class PayDueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val api: ObligationsApi by inject()

    override suspend fun doWork(): Result {
        val content = ReminderContent.from(inputData) ?: return Result.success()
        return try {
            api.payDue(content.dueId, PayDueInput()) // its amount, today, from its account
            Result.success()
        } catch (e: ApiException.Http) {
            if (e.status == 409) return Result.success() // already paid or skipped: nothing to do
            fail(content, e)
        } catch (e: ApiException) {
            fail(content, e) // validation or signed out: retrying won't help
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else fail(content, e)
        }
    }

    private fun fail(content: ReminderContent, e: Exception): Result {
        ReminderNotifications.showPaymentFailed(applicationContext, content, e.userMessage())
        return Result.failure()
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}

/**
 * Replans every reminder from the server's dues and settings, without the app open: twice a day, after a reboot and
 * after the clock or timezone changes. This is what rolls the 45-day window forward and re-nudges overdue dues daily.
 */
class ReminderSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val api: ObligationsApi by inject()
    private val tokenStore: TokenStore by inject()
    private val scheduler: ReminderScheduler by inject()

    override suspend fun doWork(): Result {
        if (tokenStore.token() == null) {
            scheduler.cancelAll()
            return Result.success()
        }
        return try {
            val plan = ReminderPlanner.plan(api.dues(), api.reminderSettings(), Clock.System.now(), TimeZone.currentSystemDefault())
            scheduler.schedule(plan)
            Result.success()
        } catch (e: ApiException) {
            Result.failure()
        } catch (e: Exception) {
            Result.retry() // offline: what's scheduled stays until this succeeds
        }
    }

    companion object {
        private const val PERIODIC = "reminder-sync"
        private const val NOW = "reminder-sync-now"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReminderSyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(online)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun enqueueNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReminderSyncWorker>()
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
