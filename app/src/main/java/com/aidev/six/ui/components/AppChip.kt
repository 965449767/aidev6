package com.aidev.six.ui.components

import com.aidev.six.ui.theme.Radius

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectedColor: Color = MaterialTheme.colorScheme.primary,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    selectedTextColor: Color = MaterialTheme.colorScheme.surface,
    bgColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    selectedBgColor: Color = MaterialTheme.colorScheme.primary,
    shape: RoundedCornerShape = RoundedCornerShape(Radius.button),
    fontSize: Int = 12,
) {
    val bg = if (selected) selectedBgColor else bgColor
    val fg = if (selected) selectedTextColor else textColor
    Box(
        modifier = modifier
            .background(bg, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = fontSize.sp,
            color = fg,
            maxLines = 1,
        )
    }
}
