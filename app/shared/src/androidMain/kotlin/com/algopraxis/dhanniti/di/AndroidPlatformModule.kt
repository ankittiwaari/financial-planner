package com.algopraxis.dhanniti.di

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import com.algopraxis.dhanniti.data.db.AppDatabase
import com.algopraxis.dhanniti.data.db.DATABASE_FILE_NAME
import com.algopraxis.dhanniti.data.db.buildAppDatabase
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val androidPlatformModule = module {
    single<HttpClientEngine> { OkHttp.create() }
    single<DataStore<Preferences>> { createSessionDataStore(androidContext()) }
    single<AppDatabase> {
        val context = androidContext()
        Room.databaseBuilder<AppDatabase>(
            context = context,
            name = context.getDatabasePath(DATABASE_FILE_NAME).absolutePath,
        ).buildAppDatabase()
    }
    single(DeviceName) { "${Build.MANUFACTURER} ${Build.MODEL}".trim() }
}

private fun createSessionDataStore(context: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        produceFile = { context.filesDir.resolve("datastore/session.preferences_pb") },
    )
