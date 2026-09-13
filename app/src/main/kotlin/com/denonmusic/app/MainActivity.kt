package com.denonmusic.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import com.denonmusic.app.nav.MainScreen
import com.denonmusic.app.ui.Winamp
import com.denonmusic.app.ui.WinampTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WinampTheme {
                Surface(color = Winamp.Background) {
                    MainScreen()
                }
            }
        }
    }
}
