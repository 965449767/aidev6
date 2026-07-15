package com.aidev.six.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aidev.six.terminal.PerfSample

@Composable
fun TerminalPerfHud(sample: PerfSample, modifier: Modifier = Modifier) {
    val fps = sample.updFlushPerSec.coerceAtMost(60)
    val color = when {
        fps >= 55 -> Color(0xFF4CAF50)
        fps >= 30 -> Color(0xFFFFC107)
        else -> Color(0xFFF44336)
    }
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "FPS(刷新) $fps",
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Text("刷新请求/s ${sample.updSchedPerSec} → 合并省 ${sample.coalescedSaved}", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Text("invalidate/s ${sample.invFlushPerSec}", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Text("输入flush/s ${sample.inputFlushPerSec}  字节/s ${sample.inputBytesPerSec}", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Text("渲染均 ${"%.2f".format(sample.renderAvgMs)}ms 峰 ${"%.2f".format(sample.renderMaxMs)}ms", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}
