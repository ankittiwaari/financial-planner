package com.algopraxis.dhanniti

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.algopraxis.dhanniti.ui.App

class MainActivity : ComponentActivity() {
    private val requestLocalNetwork = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestLocalNetworkForDevServer()
        setContent { App() }
    }

    /** Debug builds call the API on the dev machine's LAN address, which Android 17+ gates behind this permission. */
    private fun requestLocalNetworkForDevServer() {
        if (!BuildConfig.DEBUG || Build.VERSION.SDK_INT < 37) return
        if (checkSelfPermission(ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            requestLocalNetwork.launch(ACCESS_LOCAL_NETWORK)
        }
    }

    private companion object {
        const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
