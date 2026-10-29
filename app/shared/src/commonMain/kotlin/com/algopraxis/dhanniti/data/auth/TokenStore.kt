package com.algopraxis.dhanniti.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Holds the Sanctum API token for the signed-in user. */
interface TokenStore {
    val tokenFlow: Flow<String?>
    suspend fun token(): String?
    suspend fun save(token: String)
    suspend fun clear()
}

class DataStoreTokenStore(private val dataStore: DataStore<Preferences>) : TokenStore {
    override val tokenFlow: Flow<String?> = dataStore.data.map { it[KEY_TOKEN] }

    override suspend fun token(): String? = tokenFlow.first()

    override suspend fun save(token: String) {
        dataStore.edit { it[KEY_TOKEN] = token }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY_TOKEN) }
    }

    private companion object {
        val KEY_TOKEN = stringPreferencesKey("api_token")
    }
}
