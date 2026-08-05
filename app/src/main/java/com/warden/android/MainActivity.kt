package com.warden.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.warden.android.ui.WardenNavHost
import com.warden.android.ui.theme.WardenTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = (application as WardenApplication).repository
        setContent {
            WardenTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WardenNavHost(repository = repository)
                }
            }
        }
    }
}
