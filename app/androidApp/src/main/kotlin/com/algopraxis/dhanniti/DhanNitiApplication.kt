package com.algopraxis.dhanniti

import android.app.Application
import com.algopraxis.dhanniti.data.reminders.ReminderCoordinator
import com.algopraxis.dhanniti.data.reminders.ReminderScheduler
import com.algopraxis.dhanniti.data.sync.BackgroundSync
import com.algopraxis.dhanniti.data.sync.SyncRepository
import com.algopraxis.dhanniti.di.androidPlatformModule
import com.algopraxis.dhanniti.di.sharedModule
import com.algopraxis.dhanniti.offline.AndroidBackgroundSync
import com.algopraxis.dhanniti.reminders.AndroidReminderScheduler
import com.algopraxis.dhanniti.reminders.ReminderNotifications
import com.algopraxis.dhanniti.reminders.ReminderSyncWorker
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

class DhanNitiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@DhanNitiApplication)
            modules(
                androidPlatformModule,
                module {
                    single<ReminderScheduler> { AndroidReminderScheduler(androidContext()) }
                    single<BackgroundSync> { AndroidBackgroundSync(androidContext()) }
                },
                sharedModule(apiBaseUrl = BuildConfig.API_BASE_URL, enableHttpLogging = BuildConfig.DEBUG),
            )
        }
        ReminderNotifications.createChannel(this)
        // Cached data first, then a fresh sync; offline changes go out as soon as the server is reachable.
        GlobalContext.get().get<SyncRepository>().start()
        // In the app: replan whenever dues or settings change. In the background: twice a day.
        GlobalContext.get().get<ReminderCoordinator>().start()
        ReminderSyncWorker.enqueuePeriodic(this)
    }
}
