package com.aidev.six.ui.theme

import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext

val AIDarkColorScheme = darkColorScheme(
    primary = Color(0xFF00E676),
    onPrimary = Color(0xFF003300),
    primaryContainer = Color(0xFF006600),
    onPrimaryContainer = Color(0xFFA8FFC8),
    secondary = Color(0xFF00E676),
    onSecondary = Color(0xFF003300),
    tertiary = Color(0xFFFFD740),
    onTertiary = Color(0xFF332200),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF111111),
    onSurfaceVariant = Color(0xFFCCCCCC),
    outline = Color(0xFF333333),
    error = Color(0xFFFF5252),
    onError = Color(0xFFFFFFFF),
)

val AILightColorScheme = lightColorScheme(
    primary = Color(0xFF00B86E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF064E3B),
    secondary = Color(0xFF059669),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFFD97706),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF1F2937),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F2937),
    surfaceVariant = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF6B7280),
    outline = Color(0xFFD1D5DB),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
)

@Immutable
data class BackgroundConfig(
    val mode: String = "solid",
    val gradientColors: List<Color> = emptyList(),
    val imageBitmap: ImageBitmap? = null,
)

val LocalBackgroundConfig = staticCompositionLocalOf { BackgroundConfig() }

@Composable
fun rememberBackgroundConfig(prefs: SharedPreferences): BackgroundConfig {
    val context = LocalContext.current
    val mode = prefs.getString("bg_mode", "solid") ?: "solid"
    val imageUri = prefs.getString("bg_image_uri", null)
    return remember(mode, imageUri) {
        val gradient = if (mode == "gradient") {
            val palette = com.aidev.six.WorkbenchPalette(
                bg = 0xFF000000.toInt(),
                surface = 0xFF000000.toInt(),
                surfaceAlt = 0xFF111111.toInt(),
                text = 0xFFFFFFFF.toInt(),
                muted = 0xFFCCCCCC.toInt(),
                outline = 0xFF333333.toInt(),
                accent = 0xFF00E676.toInt(),
                success = 0xFF00E676.toInt(),
                warning = 0xFFFFD740.toInt(),
                danger = 0xFFFF5252.toInt(),
            )
            listOf(Color(palette.bg), Color(palette.surfaceAlt), Color(palette.bg))
        } else emptyList()
        val bitmap = if (mode == "image" && imageUri != null) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(imageUri)).use { input ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeStream(input, null, opts)?.asImageBitmap()
                }
            }.getOrNull()
        } else null
        BackgroundConfig(mode, gradient, bitmap)
    }
}

@Composable
fun AIDevTheme(
    prefs: SharedPreferences,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (prefs.getString("theme_preset", "system")) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val colorScheme = if (darkTheme) AIDarkColorScheme else AILightColorScheme
    val bgConfig = rememberBackgroundConfig(prefs)
    CompositionLocalProvider(LocalBackgroundConfig provides bgConfig) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AITypography,
            content = content,
        )
    }
}
