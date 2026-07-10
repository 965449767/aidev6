package com.aidev.six.ui.pages

import androidx.compose.runtime.Immutable

@Immutable
data class KnowledgeCategory(
    val id: String,
    val name: String,
    val items: List<KnowledgeItem>,
)

@Immutable
data class KnowledgeItem(
    val title: String,
    val cmd: String,
    val desc: String,
    val tags: List<String>,
    val usage: String,
    val permissions: String,
    val notes: String,
)

@Immutable
data class KnowledgeBaseUiState(
    val categories: List<KnowledgeCategory> = emptyList(),
    val searchQuery: String = "",
    val selectedCategoryId: String? = null,
    val selectedItem: KnowledgeItem? = null,
    val customCommands: List<KnowledgeItem> = emptyList(),
    val showCustomManager: Boolean = false,
) {
    val allCategories: List<KnowledgeCategory>
        get() = if (customCommands.isEmpty()) categories
        else categories + KnowledgeCategory(
            id = "custom",
            name = "自定义",
            items = customCommands,
        )

    val filteredItems: List<Pair<KnowledgeItem, String>>
        get() {
            val filter = searchQuery.lowercase()
            return allCategories
                .filter { selectedCategoryId == null || it.id == selectedCategoryId }
                .flatMap { cat ->
                    val items = if (filter.isEmpty()) cat.items
                    else cat.items.filter {
                        it.title.lowercase().contains(filter) ||
                            it.cmd.lowercase().contains(filter) ||
                            it.desc.lowercase().contains(filter) ||
                            it.tags.any { tag -> tag.lowercase().contains(filter) }
                    }
                    items.map { it to cat.name }
                }
        }
}
