package com.example.ytsaver

import android.app.WallpaperManager
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.cos
import kotlin.math.sin

/** One colour theme the user can pick in Settings → Themes. Every theme has a dark and a light colour scheme. */
class AppTheme(
    val id: String,
    val en: String,
    val ar: String,
    val swatch: Color,
    val dark: ColorScheme,
    val light: ColorScheme,
)

private val BrightOn = Color(0xFF0B0B0B)      // text on the bright accent colours of the dark schemes

private fun darkTheme(
    primary: Color, bg: Color, surface: Color, variant: Color,
    onPrimary: Color = BrightOn, secondary: Color? = null,
): ColorScheme =
    darkColorScheme(
        primary = primary, onPrimary = onPrimary,
        secondary = secondary ?: primary, onSecondary = BrightOn,
        background = bg, surface = surface, surfaceVariant = variant,
        onBackground = Color(0xFFEDEDED), onSurface = Color(0xFFEDEDED),
        primaryContainer = primary.copy(alpha = 0.25f).compositeOver(surface), onPrimaryContainer = Color(0xFFEDEDED),
    )

private fun lightTheme(primary: Color, container: Color, onContainer: Color, secondary: Color? = null): ColorScheme =
    LightColors.copy(
        primary = primary, onPrimary = Color.White,
        secondary = secondary ?: LightColors.secondary, onSecondary = Color.White,
        primaryContainer = container, onPrimaryContainer = onContainer,
        secondaryContainer = container, onSecondaryContainer = onContainer,
    )

/** The VibeVault brand palette: midnight navy #101522, deep blue #202C4C, electric violet #8B83FF, turquoise #51D8D1. */
private val VibeDark = darkColorScheme(
    primary = Color(0xFF8B83FF), onPrimary = Color(0xFF101522),
    secondary = Color(0xFF51D8D1), onSecondary = Color(0xFF101522),
    tertiary = Color(0xFF51D8D1),
    background = Color(0xFF101522), onBackground = Color.White,
    surface = Color(0xFF171F37), onSurface = Color.White,
    surfaceVariant = Color(0xFF202C4C), onSurfaceVariant = Color(0xFFA9B4D0),
    primaryContainer = Color(0xFF8B83FF).copy(alpha = 0.25f).compositeOver(Color(0xFF171F37)), onPrimaryContainer = Color.White,
    secondaryContainer = Color(0xFF51D8D1).copy(alpha = 0.20f).compositeOver(Color(0xFF171F37)), onSecondaryContainer = Color.White,
    outline = Color(0xFF3A4770),
)

val AppThemes: List<AppTheme> = listOf(
    AppTheme(
        "vibevault", "VibeVault", "VibeVault", Color(0xFF8B83FF),
        VibeDark,
        lightTheme(Color(0xFF5B52E0), Color(0xFFE4E2FF), Color(0xFF16106B), secondary = Color(0xFF1A9E98)),
    ),
    AppTheme("coral", "Coral", "مرجاني", Color(0xFFFF5A5F), DarkColors, LightColors),
    AppTheme(
        "ocean", "Ocean", "محيطي", Color(0xFF29B6F6),
        darkTheme(Color(0xFF29B6F6), Color(0xFF0A1219), Color(0xFF121E29), Color(0xFF1C2B3A)),
        lightTheme(Color(0xFF0277BD), Color(0xFFD3EBFA), Color(0xFF002B45)),
    ),
    AppTheme(
        "emerald", "Emerald", "زمردي", Color(0xFF2ECC71),
        darkTheme(Color(0xFF2ECC71), Color(0xFF0A1410), Color(0xFF12201A), Color(0xFF1B2E25)),
        lightTheme(Color(0xFF1B8A4B), Color(0xFFD5F3E0), Color(0xFF00391B)),
    ),
    AppTheme(
        "purple", "Purple", "بنفسجي", Color(0xFFB388FF),
        darkTheme(Color(0xFFB388FF), Color(0xFF110D18), Color(0xFF1C1626), Color(0xFF2A2138)),
        lightTheme(Color(0xFF6A3FD0), Color(0xFFE8DDFF), Color(0xFF22005D)),
    ),
    AppTheme(
        "amoled", "AMOLED black", "أسود AMOLED", Color(0xFF000000),
        darkTheme(Color(0xFF4DD0E1), Color(0xFF000000), Color(0xFF0B0B0B), Color(0xFF1A1A1A)),
        lightTheme(Color(0xFF00838F), Color(0xFFCFF3F7), Color(0xFF001F24)),
    ),
    // AMOLED black like the one above, but the buttons and the accent text are grey instead of cyan
    AppTheme(
        "amoledgrey", "AMOLED grey", "AMOLED رمادي", Color(0xFF9E9E9E),
        darkTheme(Color(0xFF9E9E9E), Color(0xFF000000), Color(0xFF0B0B0B), Color(0xFF1A1A1A)),
        lightTheme(Color(0xFF616161), Color(0xFFE4E4E4), Color(0xFF1A1A1A)),
    ),
    AppTheme(
        "sunset", "Sunset", "الغروب", Color(0xFFFF9A3C),
        darkTheme(Color(0xFFFF9A3C), Color(0xFF140F09), Color(0xFF211810), Color(0xFF30251A)),
        lightTheme(Color(0xFFB45F06), Color(0xFFFFE0BF), Color(0xFF2E1500)),
    ),
    AppTheme(
        "rose", "Rose", "وردي", Color(0xFFFF6FA8),
        darkTheme(Color(0xFFFF6FA8), Color(0xFF160B11), Color(0xFF22141B), Color(0xFF301C27)),
        lightTheme(Color(0xFFC2185B), Color(0xFFFFD9E6), Color(0xFF3E001D)),
    ),
    AppTheme(
        "gold", "Gold", "ذهبي", Color(0xFFFFD54F),
        darkTheme(Color(0xFFFFD54F), Color(0xFF14120A), Color(0xFF211E10), Color(0xFF302B18)),
        lightTheme(Color(0xFF8A6D00), Color(0xFFFFEFB3), Color(0xFF2B2100)),
    ),
    AppTheme(
        "indigo", "Indigo", "نيلي", Color(0xFF7C8CFF),
        darkTheme(Color(0xFF7C8CFF), Color(0xFF0B0C18), Color(0xFF14172A), Color(0xFF1F2340)),
        lightTheme(Color(0xFF3F51B5), Color(0xFFDDE1FF), Color(0xFF000F5C)),
    ),
    AppTheme(
        "lime", "Lime", "ليموني", Color(0xFFB6E34A),
        darkTheme(Color(0xFFB6E34A), Color(0xFF0E1208), Color(0xFF182010), Color(0xFF232E18)),
        lightTheme(Color(0xFF5B7F00), Color(0xFFDDF59B), Color(0xFF1B2600)),
    ),
    AppTheme(
        "graphite", "Graphite", "رمادي", Color(0xFFB0BEC5),
        darkTheme(Color(0xFFB0BEC5), Color(0xFF101213), Color(0xFF1A1D1F), Color(0xFF272B2E)),
        lightTheme(Color(0xFF455A64), Color(0xFFDCE6EA), Color(0xFF0D1B21)),
    ),
    AppTheme(
        "crimson", "Crimson", "قرمزي", Color(0xFFE53950),
        darkTheme(Color(0xFFE53950), Color(0xFF0E0608), Color(0xFF1A0C10), Color(0xFF2A151B), onPrimary = Color.White),
        lightTheme(Color(0xFFB3122B), Color(0xFFFFD9DD), Color(0xFF410009)),
    ),
    AppTheme(
        "nord", "Nord", "نورد", Color(0xFF88C0D0),
        darkTheme(Color(0xFF88C0D0), Color(0xFF20242C), Color(0xFF2B303B), Color(0xFF394150)),
        lightTheme(Color(0xFF3B6E8F), Color(0xFFD9E6EE), Color(0xFF0B2230)),
    ),
    AppTheme(
        "mocha", "Mocha", "موكا", Color(0xFFD4A856),
        darkTheme(Color(0xFFD4A856), Color(0xFF130D09), Color(0xFF21170F), Color(0xFF32241A)),
        lightTheme(Color(0xFF8A5A1F), Color(0xFFF3DFC4), Color(0xFF2B1600)),
    ),
    AppTheme(
        "cyber", "Cyber", "سايبر", Color(0xFF00F5D4),
        darkTheme(Color(0xFF00F5D4), Color(0xFF05060A), Color(0xFF0D0F17), Color(0xFF1B1428), secondary = Color(0xFFFF2BD6)),
        lightTheme(Color(0xFF00796B), Color(0xFFC8F7F0), Color(0xFF00201B), secondary = Color(0xFFB0007F)),
    ),
    AppTheme(
        "abyss", "Abyss", "الأعماق", Color(0xFF4DB6AC),
        darkTheme(Color(0xFF4DB6AC), Color(0xFF021113), Color(0xFF072024), Color(0xFF0E3138)),
        lightTheme(Color(0xFF00695C), Color(0xFFCDEBE6), Color(0xFF00201C)),
    ),
    // plain dark themes: calm dark surfaces and dark-coloured buttons (white text on them)
    AppTheme(
        "plaindark", "Plain Dark", "داكن عادي", Color(0xFF5C5C5C),
        darkTheme(Color(0xFF5C5C5C), Color(0xFF121212), Color(0xFF1C1C1C), Color(0xFF2A2A2A), onPrimary = Color.White),
        lightTheme(Color(0xFF333333), Color(0xFFE2E2E2), Color(0xFF111111)),
    ),
    AppTheme(
        "slatedark", "Slate Dark", "سليت داكن", Color(0xFF4F5D75),
        darkTheme(Color(0xFF4F5D75), Color(0xFF10131A), Color(0xFF181C25), Color(0xFF252B38), onPrimary = Color.White),
        lightTheme(Color(0xFF334155), Color(0xFFDCE3EE), Color(0xFF0F1A2B)),
    ),
    AppTheme(
        "navydark", "Navy Dark", "كحلي داكن", Color(0xFF3D5A9E),
        darkTheme(Color(0xFF3D5A9E), Color(0xFF080C16), Color(0xFF0F1524), Color(0xFF1A2236), onPrimary = Color.White),
        lightTheme(Color(0xFF1E3A8A), Color(0xFFDCE4FA), Color(0xFF0A1740)),
    ),
)

/** The last theme in the slider: colours taken from the phone's own wallpaper (see [wallpaperScheme]). */
const val THEME_WALLPAPER = "wallpaper"

fun schemeFor(id: String, light: Boolean): ColorScheme {
    val t = AppThemes.firstOrNull { it.id == id } ?: AppThemes[0]
    return if (light) t.light else t.dark
}

/**
 * Colours from the user's wallpaper. Android 12+ gives the full Material You palette; on Android 10 / 11 the
 * wallpaper's main colour is read with WallpaperManager and a matching dark or light scheme is built from it.
 * It is rebuilt every time the app comes back to the foreground, so a new wallpaper shows up straight away.
 */
@Composable
fun wallpaperScheme(light: Boolean): ColorScheme {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return remember(light, tick) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (light) dynamicLightColorScheme(ctx) else dynamicDarkColorScheme(ctx)
        } else {
            val seed = runCatching {
                WallpaperManager.getInstance(ctx).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb()
            }.getOrNull() ?: 0xFFFF5A5F.toInt()
            schemeFromSeed(seed, light)
        }
    }
}

/** A dark or light scheme in the hue of [seed] (an ARGB colour). */
private fun schemeFromSeed(seed: Int, light: Boolean): ColorScheme {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(seed, hsl)
    val h = hsl[0]
    val s = hsl[1].coerceIn(0.35f, 0.9f)
    fun c(sat: Float, l: Float) = Color(ColorUtils.HSLToColor(floatArrayOf(h, sat.coerceIn(0f, 1f), l)))
    return if (light) {
        lightTheme(c(s, 0.38f), c(0.65f, 0.90f), c(0.8f, 0.13f))
    } else {
        darkTheme(c(s, 0.70f), c(s * 0.35f, 0.06f), c(s * 0.30f, 0.10f), c(s * 0.30f, 0.16f))
    }
}

/** The same scheme, but its main colours glide to the new values when the theme changes (no restart, no flash). */
@Composable
fun animatedScheme(target: ColorScheme): ColorScheme {
    val spec = tween<Color>(durationMillis = 450)
    val primary by animateColorAsState(target.primary, spec, label = "primary")
    val onPrimary by animateColorAsState(target.onPrimary, spec, label = "onPrimary")
    val background by animateColorAsState(target.background, spec, label = "background")
    val surface by animateColorAsState(target.surface, spec, label = "surface")
    val variant by animateColorAsState(target.surfaceVariant, spec, label = "variant")
    val container by animateColorAsState(target.primaryContainer, spec, label = "container")
    return target.copy(
        primary = primary, onPrimary = onPrimary, background = background,
        surface = surface, surfaceVariant = variant, primaryContainer = container,
    )
}

/**
 * Slow, soft glowing shapes behind the screens. [paused] (a download or playback is running) freezes them so the
 * animation costs no battery in the background.
 */
@Composable
fun LiveBackground(paused: Boolean, modifier: Modifier = Modifier) {
    val c1 = MaterialTheme.colorScheme.primary
    val c2 = lerp(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onBackground, 0.4f)
    if (paused) {
        Blobs(modifier, c1, c2) { 0.35f }
    } else {
        val transition = rememberInfiniteTransition(label = "liveBackground")
        val phase by transition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(32_000, easing = LinearEasing), RepeatMode.Restart),
            label = "phase",
        )
        Blobs(modifier, c1, c2) { phase }
    }
}

@Composable
private fun Blobs(modifier: Modifier, c1: Color, c2: Color, phase: () -> Float) {
    Canvas(modifier.fillMaxSize()) {
        val a = phase() * 2.0 * Math.PI
        val w = size.width
        val h = size.height
        val r = maxOf(w, h) * 0.6f
        fun blob(cx: Float, cy: Float, radius: Float, color: Color) {
            drawCircle(
                brush = Brush.radialGradient(listOf(color, Color.Transparent), center = Offset(cx, cy), radius = radius),
                radius = radius, center = Offset(cx, cy),
            )
        }
        blob(w * (0.25f + 0.22f * cos(a).toFloat()), h * (0.18f + 0.12f * sin(a).toFloat()), r, c1.copy(alpha = 0.20f))
        blob(w * (0.80f + 0.18f * sin(a * 1.0).toFloat()), h * (0.55f + 0.16f * cos(a).toFloat()), r * 0.9f, c2.copy(alpha = 0.16f))
        blob(w * (0.45f + 0.25f * sin(a + 2.1).toFloat()), h * (0.92f + 0.08f * cos(a + 1.3).toFloat()), r * 0.8f, c1.copy(alpha = 0.14f))
    }
}
