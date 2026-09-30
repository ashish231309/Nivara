package com.nivara.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nivara.app.ui.NivaraApp
import com.nivara.app.ui.theme.NivaraTheme

/**
 * The single activity of Nivara.
 *
 * Compose owns the whole window, so there is no reason to spread screens across activities;
 * keeping one activity also means sensitive state stays inside one process and one task.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the system bars; the app bar and screens apply their own insets.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            NivaraTheme {
                NivaraApp()
            }
        }
    }
}
