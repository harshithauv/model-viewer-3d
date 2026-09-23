package com.infusory.modelviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colors = darkColors(
                primary = AppColors.Accent,
                primaryVariant = AppColors.AccentVariant,
                secondary = AppColors.Accent,
                background = AppColors.Background,
                surface = AppColors.Surface
            )
            MaterialTheme(colors = colors) {
                ModelViewerScreen()
            }
        }
    }
}
