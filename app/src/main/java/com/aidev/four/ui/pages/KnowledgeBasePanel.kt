package com.aidev.four.ui.pages

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aidev.four.ProjectCommands
import com.aidev.four.data.KnowledgeBaseRepository
import com.aidev.four.ui.components.AppChip
import com.aidev.four.ui.components.AppSectionTitle
import java.io.File

@Composable
fun KnowledgeBasePanel(
    onExecuteCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(KnowledgeBaseUiState()) }
    val repository = remember { KnowledgeBaseRepository(context) }

    val prefs = remember { context.getSharedPreferences("aidev_ui", Context.MODE_PRIVATE) }
    val recentCommands = prefs.getString("project_action_history", "")
        ?.lines()?.filter { it.isNotBlank() }?.takeLast(20)?.reversed() ?: emptyList()
    val currentProjectPath = prefs.getString("current_project_path", "")?.takeIf { it.isNotBlank() }

    LaunchedEffect(Unit) {
        state = state.copy(
            categories = repository.loadKnowledgeBase(),
            customCommands = repository.loadCustomCommands(),
        )
    }

    var customEditIndex by remember { mutableIntStateOf(-1) }
    var customShowAdd by remember { mutableStateOf(false) }
    var customEditCommands by remember { mutableStateOf(emptyList<KnowledgeItem>()) }

    LaunchedEffect(state.showCustomManager) {
        if (state.showCustomManager) {
            customEditCommands = state.customCommands
            customEditIndex = -1
            customShowAdd = false
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SearchBar(
                query = state.searchQuery,
                onQueryChange = { state = state.copy(searchQuery = it) },
                onManage = { state = state.copy(showCustomManager = true) },
            )

            CategoryChips(
                categories = state.allCategories,
                selectedId = state.selectedCategoryId,
                onSelect = { state = state.copy(selectedCategoryId = it) },
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val showDynamic = state.searchQuery.isEmpty()

                if (showDynamic && recentCommands.isNotEmpty()) {
                    item { AppSectionTitle("最近命令") }
                    items(recentCommands, key = { it }) { row ->
                        val parts = row.split("\t", limit = 4)
                        val label = parts.getOrNull(1) ?: "命令"
                        val path = parts.getOrNull(2).orEmpty()
                        val command = parts.getOrNull(3).orEmpty()
                        RecentCommandRow(
                            label = label,
                            command = command,
                            onClick = {
                                val cmd = if (path.isNotBlank()) "cd \"$path\" && $command" else command
                                onExecuteCommand(cmd)
                            },
                        )
                    }
                }

                if (showDynamic && currentProjectPath != null) {
                    item { AppSectionTitle("当前项目") }
                    item {
                        ProjectActionsRow(
                            projectPath = currentProjectPath,
                            onExecute = { cmd ->
                                onExecuteCommand("cd \"$currentProjectPath\" && $cmd")
                            },
                        )
                    }
                }

                val filteredItems = state.filteredItems

                if (filteredItems.isEmpty() && state.categories.isNotEmpty()) {
                    item {
                        Text(
                            text = if (state.searchQuery.isNotEmpty()) "无匹配结果" else "知识库加载失败",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }

                var lastCategory = ""
                itemsIndexed(filteredItems, key = { _, pair -> "${pair.first.title}|${pair.first.cmd}" }) { index, (item, catName) ->
                    if (catName != lastCategory) {
                        lastCategory = catName
                        AppSectionTitle(catName)
                    }
                    KnowledgeItemRow(
                        item = item,
                        searchQuery = state.searchQuery,
                        onClick = { state = state.copy(selectedItem = item) },
                        onCopy = { copyToClipboard(context, item.cmd) },
                    )
                    if (index < filteredItems.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    }
                }
            }
        }
    }

    state.selectedItem?.let { item ->
        ItemDetailSheet(
            item = item,
            onDismiss = { state = state.copy(selectedItem = null) },
            onExecute = {
                onExecuteCommand(item.cmd)
                state = state.copy(selectedItem = null)
            },
            onCopy = { copyToClipboard(context, item.cmd) },
            onSearchTag = { tag ->
                state = state.copy(searchQuery = tag, selectedItem = null)
            },
        )
    }

    if (state.showCustomManager) {
        CustomCommandsManager(
            commands = customEditCommands,
            onDismiss = { state = state.copy(showCustomManager = false) },
            onSave = { updated ->
                state = state.copy(customCommands = updated, showCustomManager = false)
                repository.saveCustomCommands(updated)
            },
            onEdit = { index -> customEditIndex = index },
            onDelete = { index ->
                customEditCommands = customEditCommands.toMutableList().apply { removeAt(index) }
            },
            onAddRequest = { customShowAdd = true },
        )

        if (customEditIndex >= 0 && customEditIndex < customEditCommands.size) {
            CustomCommandEditDialog(
                initial = customEditCommands[customEditIndex],
                onDismiss = { customEditIndex = -1 },
                onConfirm = { updated ->
                    customEditCommands = customEditCommands.toMutableList().apply { set(customEditIndex, updated) }
                    customEditIndex = -1
                },
            )
        }

        if (customShowAdd) {
            CustomCommandEditDialog(
                initial = KnowledgeItem("", "", "", emptyList(), "", "", ""),
                onDismiss = { customShowAdd = false },
                onConfirm = { item ->
                    customEditCommands = customEditCommands + item
                    customShowAdd = false
                },
            )
        }
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, onManage: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("搜索命令...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {}),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier
                .weight(1f)
                .height(44.dp),
            shape = RoundedCornerShape(8.dp),
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onManage)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("管理", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CategoryChips(
    categories: List<KnowledgeCategory>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AppChip(
            text = "全部",
            selected = selectedId == null,
            onClick = { onSelect(null) },
        )
        categories.forEach { cat ->
            AppChip(
                text = cat.name,
                selected = selectedId == cat.id,
                onClick = { onSelect(cat.id) },
            )
        }
    }
}

@Composable
private fun KnowledgeItemRow(
    item: KnowledgeItem,
    searchQuery: String,
    onClick: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(
            text = item.title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.cmd,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "复制",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .clickable(onClick = onCopy)
                    .padding(start = 4.dp),
            )
        }

        Text(
            text = item.desc,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )

        if (item.tags.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                item.tags.forEach { tag ->
                    Text(
                        text = tag,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(2.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemDetailSheet(
    item: KnowledgeItem,
    onDismiss: () -> Unit,
    onExecute: () -> Unit,
    onCopy: () -> Unit,
    onSearchTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            CommandBlock(item.cmd)

            if (item.tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.tags.forEach { tag ->
                        Text(
                            text = tag,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onSearchTag(tag) }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(text = item.desc, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)

            if (item.usage.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SectionLabel("用法示例")
                item.usage.split("\n").filter { it.trim().isNotEmpty() }.forEach { line ->
                    val trimmed = line.trim()
                    val commentIdx = trimmed.indexOf("  # ")
                    val cmdPart = if (commentIdx >= 0) trimmed.substring(0, commentIdx).trim() else trimmed
                    val explPart = if (commentIdx >= 0) trimmed.substring(commentIdx + 4).trim() else ""
                    UsageLine(cmdPart, explPart)
                }
            }

            if (item.permissions.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SectionLabel("所需权限")
                Text(text = item.permissions, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
            }

            if (item.notes.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SectionLabel("注意事项")
                Text(text = item.notes, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton("在终端执行", onClick = onExecute, modifier = Modifier.weight(1f))
                SmallButton("复制命令", onClick = onCopy, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CommandBlock(cmd: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = cmd,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(bottom = 2.dp),
    )
}

@Composable
private fun UsageLine(cmdPart: String, explPart: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(top = 6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = cmdPart,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
        }
        if (explPart.isNotEmpty()) {
            Text(
                text = explPart,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RecentCommandRow(
    label: String,
    command: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = command,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProjectActionsRow(
    projectPath: String,
    onExecute: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = projectPath,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton("测试", onClick = { onExecute(ProjectCommands.testCommand(File(projectPath))) })
            SmallButton("构建", onClick = { onExecute(ProjectCommands.buildCommand(File(projectPath))) })
            SmallButton("诊断", onClick = { onExecute(ProjectCommands.healthCommand(File(projectPath))) })
            SmallButton("修复", onClick = { onExecute(ProjectCommands.repairCommand(File(projectPath))) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomCommandsManager(
    commands: List<KnowledgeItem>,
    onDismiss: () -> Unit,
    onSave: (List<KnowledgeItem>) -> Unit,
    onEdit: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onAddRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("自定义命令", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            if (commands.isEmpty()) {
                Text("暂无自定义命令", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(commands.indices.toList(), key = { it }) { i ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(commands[i].title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text(commands[i].cmd, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                text = "编辑",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { onEdit(i) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                            Text(
                                text = "删除",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { onDelete(i) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton("添加", onClick = onAddRequest, modifier = Modifier.weight(1f))
                SmallButton("保存", onClick = { onSave(commands) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CustomCommandEditDialog(
    initial: KnowledgeItem,
    onDismiss: () -> Unit,
    onConfirm: (KnowledgeItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var title by remember { mutableStateOf(initial.title) }
    var cmd by remember { mutableStateOf(initial.cmd) }
    var desc by remember { mutableStateOf(initial.desc) }
    var tagsText by remember { mutableStateOf(initial.tags.joinToString(", ")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(if (initial.title.isEmpty()) "添加命令" else "编辑命令", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(modifier = modifier) {
                TextField(value = title, onValueChange = { title = it }, label = { Text("标题") },
                    colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        cursorColor = MaterialTheme.colorScheme.primary, focusedLabelColor = MaterialTheme.colorScheme.primary, unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                TextField(value = cmd, onValueChange = { cmd = it }, label = { Text("命令") },
                    colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        cursorColor = MaterialTheme.colorScheme.primary, focusedLabelColor = MaterialTheme.colorScheme.primary, unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace),
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                TextField(value = desc, onValueChange = { desc = it }, label = { Text("描述") },
                    colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        cursorColor = MaterialTheme.colorScheme.primary, focusedLabelColor = MaterialTheme.colorScheme.primary, unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                TextField(value = tagsText, onValueChange = { tagsText = it }, label = { Text("标签（逗号分隔）") },
                    colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        cursorColor = MaterialTheme.colorScheme.primary, focusedLabelColor = MaterialTheme.colorScheme.primary, unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isNotBlank() && cmd.isNotBlank()) {
                    onConfirm(KnowledgeItem(title.trim(), cmd.trim(), desc.trim(),
                        tagsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }, "", "", ""))
                }
            }) { Text("确定", color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
    )
}

private fun copyToClipboard(context: android.content.Context, text: String) {
    val clip = android.content.ClipData.newPlainText("AIDev 命令", text)
    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)?.setPrimaryClip(clip)
}
