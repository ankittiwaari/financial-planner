package com.algopraxis.dhanniti.data.auth

import com.algopraxis.dhanniti.data.api.ApiException
import com.algopraxis.dhanniti.data.api.AuthApi
import com.algopraxis.dhanniti.data.db.UserDao
import com.algopraxis.dhanniti.data.db.toDomain
import com.algopraxis.dhanniti.data.db.toEntity
import com.algopraxis.dhanniti.domain.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class AuthRepository(
    private val api: AuthApi,
    private val tokenStore: TokenStore,
    private val userDao: UserDao,
    private val deviceName: String,
) {
    val isLoggedIn: Flow<Boolean> = tokenStore.tokenFlow.map { it != null }.distinctUntilChanged()

    val currentUser: Flow<User?> = userDao.observeCurrent().map { it?.toDomain() }

    suspend fun login(email: String, password: String) {
        val response = api.login(email.trim(), password, deviceName)
        userDao.upsert(response.user.toEntity())
        tokenStore.save(response.token)
    }

    /** Fetches the profile from the server. Signs out locally if the token was revoked. */
    suspend fun refreshProfile(): User {
        try {
            val user = api.me().toEntity()
            userDao.upsert(user)
            return user.toDomain()
        } catch (e: ApiException.Unauthorized) {
            clearLocalSession()
            throw e
        }
    }

    /** Revokes the token on the server when reachable; always signs out locally. */
    suspend fun logout() {
        runCatching { api.logout() }
        clearLocalSession()
    }

    private suspend fun clearLocalSession() {
        tokenStore.clear()
        userDao.clear()
    }
}
