package com.anvit.localai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.anvit.localai.ui.navigation.AnvitNavHost
import com.anvit.localai.ui.theme.AnvitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AnvitTheme {
                AnvitNavHost()
            }
        }
    }
}
