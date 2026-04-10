package com.example.surveillanceapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.surveillanceapp.ui.SurveillanceRoot
import com.example.surveillanceapp.ui.theme.SurveillanceAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SurveillanceAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Step 2+: drone connection + live video pipeline; later steps add overlays + detectors.
                    SurveillanceRoot()
                }
            }
        }
    }
}
