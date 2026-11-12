package com.example.ytsaver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** Language switch (EN / عر) usable from non-UI code (engine, services). */
object L {
    @Volatile var ar: Boolean = false
    fun t(en: String, arabic: String): String = if (ar) arabic else en
}

val LocalAr = staticCompositionLocalOf { false }

@Composable
fun tr(en: String, arabic: String): String = if (LocalAr.current) arabic else en
