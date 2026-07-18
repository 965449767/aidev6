package com.aidev.six.ui.pages

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aidev.six.ui.theme.Radius
import com.aidev.six.ui.theme.Spacing
import kotlin.math.roundToInt

@Composable
internal fun FontSizeOverlay(fontSp: Float) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${fontSp.roundToInt()}sp",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                    RoundedCornerShape(Radius.card),
                )
                .padding(horizontal = Spacing.s48, vertical = Spacing.s32),
        )
    }
}

@Composable
internal fun ThemeOverlay(themeDisplayName: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = themeDisplayName,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                    RoundedCornerShape(Radius.card),
                )
                .padding(horizontal = Spacing.s48, vertical = Spacing.s32),
        )
    }
}

@Composable
internal fun SwipeSensitivityDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uiPrefs = context.getSharedPreferences("aidev_ui", android.content.Context.MODE_PRIVATE)
    val current = uiPrefs.getFloat("swipe_sensitivity", 1.0f)
    val items = listOf("low" to "\u4F4E\u654F\u611F", "medium" to "\u4E2D\u654F\u611F", "high" to "\u9AD8\u654F\u611F")
    val floatMap = mapOf("low" to 1.5f, "medium" to 1.0f, "high" to 0.5f)
    val initialKey = floatMap.entries.firstOrNull { it.value == current }?.key ?: "medium"
    var selectedValue by remember { mutableStateOf(initialKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("\u6ED1\u52A8\u7075\u654F\u5EA6") },
        text = {
            com.aidev.six.ui.components.AppRadioDialogContent(
                items = items,
                selectedValue = selectedValue,
                onSelect = { selectedValue = it },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                uiPrefs.edit().putFloat("swipe_sensitivity", floatMap[selectedValue] ?: 1.0f).apply()
                onDismiss()
            }) { Text("\u786E\u5B9A") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("\u53D6\u6D88") }
        },
    )
}

@Composable
internal fun ResetKeyboardDialog(activity: Activity, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("\u786E\u8BA4\u91CD\u7F6E") },
        text = { Text("\u5C06\u6062\u590D\u952E\u76D8\u6309\u952E\u987A\u5E8F\u5230\u9ED8\u8BA4\u5E03\u5C40\uFF0C\u81EA\u5B9A\u4E49\u6309\u952E\u8986\u76D6\u4E0D\u4F1A\u4E22\u5931\u3002", color = MaterialTheme.colorScheme.onSurface) },
        confirmButton = {
            TextButton(onClick = {
                activity.getSharedPreferences("aidev_ui", Activity.MODE_PRIVATE)
                    .edit().remove("terminal_key_order").apply()
                onDismiss()
            }) { Text("\u91CD\u7F6E") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("\u53D6\u6D88") }
        },
    )
}

private data class HuePreset(val label: String, val r: Int, val g: Int, val b: Int)

private val huePresets = listOf(
    HuePreset("\u7070", 0x88, 0x88, 0x88),
    HuePreset("\u84DD", 0x1A, 0x1B, 0x3E),
    HuePreset("\u7D2B", 0x3A, 0x1B, 0x4E),
    HuePreset("\u7EFF", 0x1A, 0x3E, 0x1B),
    HuePreset("\u6696", 0x3E, 0x27, 0x1A),
)

@Composable
internal fun BackgroundColorDialog(
    initialOverride: Int,
    onApply: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hasOverride = initialOverride != -1
    val initR = if (hasOverride) (initialOverride shr 16) and 0xFF else 0x88
    val initG = if (hasOverride) (initialOverride shr 8) and 0xFF else 0x88
    val initB = if (hasOverride) initialOverride and 0xFF else 0x88
    val initHue = huePresets.indices.minByOrNull { i ->
        val p = huePresets[i]; val dr = initR - p.r; val dg = initG - p.g; val db = initB - p.b
        dr * dr + dg * dg + db * db
    } ?: 0
    val maxComponent = maxOf(initR, initG, initB, 1)
    val initBrightness = ((initR.toFloat() / maxComponent * 255f).roundToInt()).coerceIn(30, 255)

    var selectedHue by remember { mutableIntStateOf(initHue) }
    var brightness by remember { mutableIntStateOf(initBrightness) }

    val preset = huePresets[selectedHue]
    val r = (preset.r * brightness / 255).coerceIn(0, 255)
    val g = (preset.g * brightness / 255).coerceIn(0, 255)
    val b = (preset.b * brightness / 255).coerceIn(0, 255)
    val previewColor = Color(r / 255f, g / 255f, b / 255f)
    val resultInt = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("\u8C03\u6574\u80CC\u666F\u8272") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(previewColor, RoundedCornerShape(Radius.button))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Radius.button)),
                )
                Spacer(Modifier.height(Spacing.s12))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    huePresets.forEachIndexed { i, hue ->
                        val chipColor = if (i == selectedHue) {
                            Color(hue.r / 255f, hue.g / 255f, hue.b / 255f)
                        } else {
                            Color(hue.r / 255f * 0.5f, hue.g / 255f * 0.5f, hue.b / 255f * 0.5f)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(chipColor)
                                .border(
                                    if (i == selectedHue) 2.dp else 0.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(6.dp),
                                )
                                .clickable { selectedHue = i },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(hue.label, style = MaterialTheme.typography.labelSmall, color = Color.White)
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.s12))
                Text("\u4EAE\u5EA6", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = brightness.toFloat(),
                    onValueChange = { brightness = it.roundToInt().coerceIn(30, 255) },
                    valueRange = 30f..255f,
                    colors = SliderDefaults.colors(
                        thumbColor = previewColor,
                        activeTrackColor = previewColor,
                    ),
                )
                Text("${brightness * 100 / 255}%", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(resultInt) }) { Text("\u786E\u5B9A") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text("\u91CD\u7F6E", color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.width(Spacing.s8))
                TextButton(onClick = onDismiss) { Text("\u53D6\u6D88") }
            }
        },
    )
}
