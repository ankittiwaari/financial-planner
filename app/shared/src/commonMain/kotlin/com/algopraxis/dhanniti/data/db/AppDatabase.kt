package com.algopraxis.dhanniti.data.db

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/** Local offline cache of server data, plus changes made offline. The server stays the source of truth. */
@Database(
    entities = [UserEntity::class, CacheEntity::class, OutboxEntity::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)], // v2: cache and outbox (milestone 7)
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun cacheDao(): CacheDao
    abstract fun outboxDao(): OutboxDao
}

// Room's KSP processor generates the actual implementations.
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

fun RoomDatabase.Builder<AppDatabase>.buildAppDatabase(): AppDatabase =
    setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

const val DATABASE_FILE_NAME = "dhanniti.db"
