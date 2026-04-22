package com.cdp.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cdp.remote.presentation.navigation.AppNavGraph
import com.cdp.remote.presentation.theme.CdpRemoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CdpRemoteTheme {
                AppNavGraph()
            }
        }
    }
}
