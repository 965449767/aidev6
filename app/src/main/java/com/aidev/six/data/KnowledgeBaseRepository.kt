package com.aidev.six.data

import android.content.Context
import com.aidev.six.R
import com.aidev.six.ui.pages.KnowledgeCategory
import com.aidev.six.ui.pages.KnowledgeItem
import org.json.JSONArray
import org.json.JSONObject

class KnowledgeBaseRepository(private val context: Context) {

    fun loadKnowledgeBase(): List<KnowledgeCategory> {
        return try {
            val json = context.resources.openRawResource(R.raw.knowledge_base)
                .bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val cats = root.getJSONArray("categories")
            (0 until cats.length()).map { i ->
                val cat = cats.getJSONObject(i)
                val items = cat.getJSONArray("items")
                KnowledgeCategory(
                    id = cat.getString("id"),
                    name = cat.getString("name"),
                    items = (0 until items.length()).map { j ->
                        val item = items.getJSONObject(j)
                        KnowledgeItem(
                            title = item.getString("title"),
                            cmd = item.getString("cmd"),
                            desc = item.getString("desc"),
                            tags = item.optJSONArray("tags")?.let { arr ->
                                (0 until arr.length()).map { arr.getString(it) }
                            } ?: emptyList(),
                            usage = item.optString("usage", ""),
                            permissions = item.optString("permissions", ""),
                            notes = item.optString("notes", ""),
                        )
                    },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun loadCustomCommands(): List<KnowledgeItem> {
        val json = context.getSharedPreferences("aidev_ui", Context.MODE_PRIVATE)
            .getString("custom_kb_commands", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                KnowledgeItem(
                    title = obj.getString("title"),
                    cmd = obj.getString("cmd"),
                    desc = obj.optString("desc", ""),
                    tags = obj.optJSONArray("tags")?.let { a ->
                        (0 until a.length()).map { a.getString(it) }
                    } ?: emptyList(),
                    usage = "", permissions = "", notes = "",
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun saveCustomCommands(commands: List<KnowledgeItem>) {
        val arr = JSONArray()
        for (item in commands) {
            val obj = JSONObject()
            obj.put("title", item.title)
            obj.put("cmd", item.cmd)
            obj.put("desc", item.desc)
            val tags = JSONArray()
            for (tag in item.tags) tags.put(tag)
            obj.put("tags", tags)
            arr.put(obj)
        }
        context.getSharedPreferences("aidev_ui", Context.MODE_PRIVATE).edit()
            .putString("custom_kb_commands", arr.toString()).apply()
    }
}
