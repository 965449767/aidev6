package com.aidev.six.ui.pages

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aidev.six.ClipboardHelper
import com.aidev.six.terminal.CommandCatalog
import com.aidev.six.ui.components.AppActionRow
import com.aidev.six.ui.components.AppChip
import com.aidev.six.ui.theme.CodeFont
import com.aidev.six.ui.theme.Radius
import com.aidev.six.ui.theme.Spacing

/**
 * 命令帮助内容（不含 ModalBottomSheet 外壳）。
 * 既可作为独立 [CommandHelpSheet] 使用，也可嵌入「更多」菜单同一个底部弹层内（避免嵌套 / 切换两个 sheet 导致崩溃）。
 *
 * @param onBack 非 null 时显示「返回」头部（用于嵌入更多菜单的场景）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CommandHelpContent(
    activity: Activity,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current

    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var commands by remember { mutableStateOf(CommandCatalog.allRegistryCommands()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        commands = CommandCatalog.scanInstalledCommands(context)
        loaded = true
    }

    val categories = remember(commands) {
        listOf("全部") + commands.map { it.category }.distinct()
    }

    val filtered = remember(commands, query, selectedCategory) {
        val q = query.trim().lowercase()
        commands.filter { cmd ->
            val catOk = selectedCategory == null || selectedCategory == "全部" || cmd.category == selectedCategory
            val qOk = q.isEmpty() ||
                cmd.name.contains(q) ||
                cmd.description.contains(q, ignoreCase = true) ||
                cmd.usage.contains(q, ignoreCase = true)
            catOk && qOk
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 600.dp)
            .padding(horizontal = Spacing.s16)
            .padding(bottom = Spacing.s24),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Text(
                    text = "‹ 返回",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(start = 0.dp, top = Spacing.s4, end = Spacing.s12, bottom = Spacing.s4),
                )
            }
            Text(
                text = "AIDev 内置命令",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索命令 / 功能 / 用法…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { defaultKeyboardAction(ImeAction.Search) }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.s8),
            shape = RoundedCornerShape(Radius.button),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
            verticalArrangement = Arrangement.spacedBy(Spacing.s8),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.s8),
        ) {
            categories.forEach { cat ->
                AppChip(
                    text = cat,
                    selected = selectedCategory == null || selectedCategory == cat,
                    onClick = {
                        selectedCategory = if (cat == "全部") null else cat
                    },
                )
            }
        }

        if (!loaded) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { Text("加载中…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else if (filtered.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (commands.isEmpty()) "尚未部署命令（请先启动终端以部署开发环境）"
                    else "无匹配命令",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(filtered, key = { it.name }) { cmd ->
                    CommandHelpRow(
                        name = cmd.name,
                        description = cmd.description,
                        usage = cmd.usage,
                        onClick = {
                            ClipboardHelper.copy(activity, cmd.name, cmd.name)
                            Toast.makeText(activity, "已复制命令：${cmd.name}", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }
    }
}

/** 独立底部弹层封装（备用 / 未来若需从其他入口直接弹出）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandHelpSheet(
    activity: Activity,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        CommandHelpContent(activity = activity)
    }
}

@Composable
private fun CommandHelpRow(
    name: String,
    description: String,
    usage: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.s16, vertical = Spacing.s12),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = CodeFont,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (usage.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = usage,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = CodeFont,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}
