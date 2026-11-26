package io.github.totocastaldi.herdrdesktop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import io.github.totocastaldi.herdrdesktop.machines.MachinesScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HerdrApp).container
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MachinesScreen(container)
            }
        }
    }
}
