package com.sage.localai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.sage.localai.ui.navigation.SageNavHost
import com.sage.localai.ui.theme.SageTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SageTheme {
                SageNavHost()
            }
        }
    }
}
