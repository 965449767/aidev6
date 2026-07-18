package com.aidev.six.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aidev.six.terminal.PerfSample
import com.aidev.six.ui.theme.Radius
import com.aidev.six.ui.theme.Spacing

@Composable
fun TerminalPerfHud(sample: PerfSample, modifier: Modifier = Modifier) {
    val fps = sample.updFlushPerSec.coerceAtMost(60)
    val color = when {
        fps >= 55 -> MaterialTheme.colorScheme.primary
        fps >= 30 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .background(surface.copy(alpha = 0.85f), RoundedCornerShape(Radius.button))
            .padding(Spacing.s8),
        verticalArrangement = Arrangement.spacedBy(Spacing.s4),
    ) {
        Text(
            "FPS(刷新) $fps",
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Text("刷新请求/s ${sample.updSchedPerSec} → 合并省 ${sample.coalescedSaved}", color = onSurface, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Text("invalidate/s ${sample.invFlushPerSec}", color = onSurface, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Text("输入flush/s ${sample.inputFlushPerSec}  字节/s ${sample.inputBytesPerSec}", color = onSurface, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Text("渲染均 ${"%.2f".format(sample.renderAvgMs)}ms 峰 ${"%.2f".format(sample.renderMaxMs)}ms", color = onSurface, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}
