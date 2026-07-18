package com.aidev.six.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal val themePresetList = listOf(
    "classic-dark" to "One Dark",
    "classic-light" to "One Light",
    "solarized-dark" to "Solarized Dark",
    "dracula" to "Dracula",
    "nord" to "Nord",
    "tokyo-night" to "Tokyo Night",
    "catppuccin" to "Catppuccin Mocha",
    "gruvbox" to "Gruvbox Dark",
    "monokai" to "Monokai",
    "everforest" to "Everforest Dark",
)

internal fun themeColor(key: String): Color = when (key) {
    "classic-dark" -> Color(0xFF61AFEF)
    "classic-light" -> Color(0xFF407FF4)
    "solarized-dark" -> Color(0xFFCB4B16)
    "dracula" -> Color(0xFFBD93F9)
    "nord" -> Color(0xFF5E81AC)
    "tokyo-night" -> Color(0xFF7AA2F7)
    "catppuccin" -> Color(0xFFF5C2E7)
    "gruvbox" -> Color(0xFFD79921)
    "monokai" -> Color(0xFFA6E22E)
    "everforest" -> Color(0xFFA7C080)
    else -> Color.Gray
}

internal fun themeAbbreviation(key: String): String = when (key) {
    "classic-dark" -> "One"
    "classic-light" -> "Lt"
    "solarized-dark" -> "Sol"
    "dracula" -> "Dra"
    "nord" -> "Nrd"
    "tokyo-night" -> "Tok"
    "catppuccin" -> "Cat"
    "gruvbox" -> "Grb"
    "monokai" -> "Mon"
    "everforest" -> "Evr"
    else -> "??"
}

internal fun sessionColor(key: String): Color = when (key) {
    "red" -> Color(0xFFEF4444)
    "orange" -> Color(0xFFF97316)
    "yellow" -> Color(0xFFEAB308)
    "green" -> Color(0xFF22C55E)
    "cyan" -> Color(0xFF06B6D4)
    "blue" -> Color(0xFF3B82F6)
    "purple" -> Color(0xFFA855F7)
    "pink" -> Color(0xFFEC4899)
    "gray" -> Color(0xFF6B7280)
    else -> Color.Transparent
}

internal val sessionColorOptions = listOf(
    "" to "\u65E0",
    "red" to "\u7EA2",
    "orange" to "\u6A59",
    "yellow" to "\u9EC4",
    "green" to "\u7EFF",
    "cyan" to "\u9752",
    "blue" to "\u84DD",
    "purple" to "\u7D2B",
    "pink" to "\u7C89",
    "gray" to "\u7070",
)

@Composable
internal fun TabColorPickerDialog(
    currentColor: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("\u9009\u62E9\u4F1A\u8BDD\u989C\u8272") },
        text = {
            Column {
                sessionColorOptions.forEach { (key, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(key) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(sessionColor(key)),
                        )
                        Spacer(Modifier.width(Spacing.s8))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        if (key == currentColor) {
                            Spacer(Modifier.weight(1f))
                            Text("\u2713", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("\u53D6\u6D88") } },
    )
}
