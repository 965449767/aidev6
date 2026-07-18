package com.aidev.six.ui.pages

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aidev.six.ClipboardHelper
import com.aidev.six.terminal.CommandCatalog
import com.aidev.six.ui.components.AppChip
import com.aidev.six.ui.theme.CodeFont
import com.aidev.six.ui.theme.Radius
import com.aidev.six.ui.theme.Spacing

/**
 * 命令帮助内容（普通 Column + verticalScroll，无 LazyColumn / heightIn / weight）。
 * 展示全部内置命令（[CommandCatalog.allRegistryCommands]，不按部署过滤），
 * 每条命令完整展示「名称 / 分类 / 描述 / 全部用法」。
 */
@Composable
fun CommandHelpContent(
    activity: Activity,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    val commands = CommandCatalog.allRegistryCommands()

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
            .padding(horizontal = Spacing.s16)
            .padding(bottom = Spacing.s24),
    ) {
        Text(
            text = "AIDev \u5185\u7F6E\u547D\u4EE4",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = Spacing.s8),
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("\u641C\u7D22\u547D\u4EE4 / \u529F\u80FD / \u7528\u6CD5\u2026") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { /* no-op */ }),
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

        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = Spacing.s12),
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

        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            if (filtered.isEmpty()) {
                Text(
                    text = "\u65E0\u5339\u914D\u547D\u4EE4",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 32.dp),
                )
            } else {
                filtered.forEach { cmd ->
                    CommandHelpCard(activity = activity, cmd = cmd)
                    Spacer(Modifier.height(Spacing.s8))
                }
            }
        }
    }
}

@Composable
private fun CommandHelpCard(
    activity: Activity,
    cmd: CommandCatalog.CommandInfo,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                ClipboardHelper.copy(activity, cmd.name, cmd.name)
                Toast.makeText(activity, "\u5DF2\u590D\u5236\u547D\u4EE4\uFF1A${cmd.name}", Toast.LENGTH_SHORT).show()
            }
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(Radius.card))
            .padding(Spacing.s12),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
        ) {
            Text(
                text = cmd.name,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = CodeFont,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(Radius.button))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = cmd.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = cmd.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (cmd.usage.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = cmd.usage,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = CodeFont,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Radius.button))
                    .padding(Spacing.s8),
            )
        }
    }
}
