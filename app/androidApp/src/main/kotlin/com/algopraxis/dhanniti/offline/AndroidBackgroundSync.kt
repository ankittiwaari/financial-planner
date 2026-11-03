package com.algopraxis.dhanniti.offline

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.algopraxis.dhanniti.data.sync.BackgroundSync
import com.algopraxis.dhanniti.data.sync.SyncRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/** Sends changes made offline once there's a network, even if the app has been closed since. */
class AndroidBackgroundSync(private val context: Context) : BackgroundSync {
    override fun requestWhenOnline() {
        val request = OneTimeWorkRequestBuilder<OutboxSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        // KEEP: one pending run sends everything queued by then.
        WorkManager.getInstance(context).enqueueUniqueWork("outbox-sync", ExistingWorkPolicy.KEEP, request)
    }
}

class OutboxSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val sync: SyncRepository by inject()

    // "Connected" can still mean the server is unreachable (e.g. the dev machine is off): try again later then.
    override suspend fun doWork(): Result = if (sync.sync()) Result.success() else Result.retry()
}
