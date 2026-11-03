package com.algopraxis.dhanniti.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The last server snapshot (GET /sync), as JSON, so the app opens with data even when offline. */
@Entity(tableName = "cache")
data class CacheEntity(
    @PrimaryKey val key: String,
    val json: String,
    /** Epoch milliseconds. */
    val savedAt: Long,
)

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache WHERE `key` = :key")
    suspend fun get(key: String): CacheEntity?

    @Upsert
    suspend fun put(entry: CacheEntity)

    @Query("DELETE FROM cache")
    suspend fun clear()
}

/** A change made offline, waiting to be sent. [payload] is a serialised PendingChange. */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val payload: String,
    val createdAt: Long,
    /** Set when the server refused it; it then waits for the person to review it. */
    val lastError: String? = null,
)

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY id")
    fun observeAll(): Flow<List<OutboxEntity>>

    @Query("SELECT * FROM outbox ORDER BY id")
    suspend fun all(): List<OutboxEntity>

    @Upsert
    suspend fun upsert(entry: OutboxEntity): Long

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM outbox")
    suspend fun clear()
}
