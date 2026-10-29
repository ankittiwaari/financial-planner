package com.algopraxis.dhanniti.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.algopraxis.dhanniti.data.auth.AuthRepository
import com.algopraxis.dhanniti.ui.login.LoginScreen
import com.algopraxis.dhanniti.ui.theme.DhanNitiTheme
import org.koin.compose.koinInject

@Composable
fun App() {
    DhanNitiTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val authRepository = koinInject<AuthRepository>()
            val isLoggedIn by authRepository.isLoggedIn.collectAsStateWithLifecycle(initialValue = null)

            when (isLoggedIn) {
                null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                false -> LoginScreen()
                true -> MainNavHost()
            }
        }
    }
}
