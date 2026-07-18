package com.aidev.six.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.aidev.six.ui.theme.Spacing
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppActionRow(
    label: String,
    desc: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    enabled: Boolean = true,
) {
    val vPad = if (compact) Spacing.s8 else Spacing.s12
    val labelColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val descColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    Column(
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.s16, vertical = vPad),
    ) {
        Text(
            label,
            color = labelColor,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        if (desc != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                desc,
                color = descColor,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
