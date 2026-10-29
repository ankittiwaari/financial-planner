package com.algopraxis.dhanniti

import android.app.Application
import com.algopraxis.dhanniti.di.androidPlatformModule
import com.algopraxis.dhanniti.di.sharedModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class DhanNitiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@DhanNitiApplication)
            modules(
                androidPlatformModule,
                sharedModule(apiBaseUrl = BuildConfig.API_BASE_URL, enableHttpLogging = BuildConfig.DEBUG),
            )
        }
    }
}
