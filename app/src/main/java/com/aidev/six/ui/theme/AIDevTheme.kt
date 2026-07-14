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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

// ── 设计 Token（single source of truth，见 rules/core/UI.md + docs/DESIGN_SYSTEM.md）──
// 颜色只用 Token，禁止在业务代码写 Color(0xFF…)。
// Material 3 无 success/warning 槽位 → Success 映射 secondary、Warning 映射 tertiary。
val AIDarkColorScheme = darkColorScheme(
    primary = Color(0xFF4C8DFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF15315E),
    onPrimaryContainer = Color(0xFFA8C7FF),
    secondary = Color(0xFF3FB950),           // Success
    onSecondary = Color(0xFF06281A),
    tertiary = Color(0xFFE3B341),            // Warning
    onTertiary = Color(0xFF3A2A00),
    background = Color(0xFF101114),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF17191D),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF24262B),
    onSurfaceVariant = Color(0xFFCCCCCC),
    outline = Color(0xFF4A4D52),
    error = Color(0xFFF85149),
    onError = Color(0xFFFFFFFF),
)

val AILightColorScheme = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E3FF),
    onPrimaryContainer = Color(0xFF0B1F3A),
    secondary = Color(0xFF1E8E3E),           // Success
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFFB8860B),            // Warning
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF1F2937),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F2937),
    surfaceVariant = Color(0xFFE7EAEE),
    onSurfaceVariant = Color(0xFF5F6B7A),
    outline = Color(0xFFC2C7CD),
    error = Color(0xFFC0392B),
    onError = Color(0xFFFFFFFF),
)

// 语义色别名（业务代码优先用这些，而非记 hex）
val SuccessColor @Suppress("unused") get() = AIDarkColorScheme.secondary
val WarningColor @Suppress("unused") get() = AIDarkColorScheme.tertiary

// 间距阶梯：只取 4/8/12/16/24/32 dp
object Spacing {
    val s4 = 4.dp
    val s8 = 8.dp
    val s12 = 12.dp
    val s16 = 16.dp
    val s24 = 24.dp
    val s32 = 32.dp
}

// 圆角：Button 12 / Card 16 / Dialog 28 dp
object Radius {
    val button = 12.dp
    val card = 16.dp
    val dialog = 28.dp
}

// 等宽字体：代码/数字/终端。目标 JetBrains Mono；资源就位前用系统 Monospace。
val CodeFont = FontFamily.Monospace

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
                bg = 0xFF101114.toInt(),
                surface = 0xFF17191D.toInt(),
                surfaceAlt = 0xFF24262B.toInt(),
                text = 0xFFFFFFFF.toInt(),
                muted = 0xFFCCCCCC.toInt(),
                outline = 0xFF4A4D52.toInt(),
                accent = 0xFF4C8DFF.toInt(),
                success = 0xFF3FB950.toInt(),
                warning = 0xFFE3B341.toInt(),
                danger = 0xFFF85149.toInt(),
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
