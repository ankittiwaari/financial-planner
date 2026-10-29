package com.algopraxis.dhanniti.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.algopraxis.dhanniti.data.api.UserDto
import com.algopraxis.dhanniti.domain.User
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val email: String,
    val timezone: String,
    val currency: String,
)

@Dao
interface UserDao {
    @Query("SELECT * FROM users LIMIT 1")
    fun observeCurrent(): Flow<UserEntity?>

    @Upsert
    suspend fun upsert(user: UserEntity)

    @Query("DELETE FROM users")
    suspend fun clear()
}

fun UserDto.toEntity() = UserEntity(id, name, email, timezone, currency)

fun UserEntity.toDomain() = User(id, name, email, timezone, currency)
