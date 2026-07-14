package com.aidev.six.context

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * Context Manager（M3）：把 [CodeIndexer] 抽取的符号持久化进 SQLite，
 * 提供按名称/类型/标签的检索与统计，替代 grep 式全文扫描。
 * 数据库位于应用私有 databases 目录，fail-safe：任何异常向上抛出由调用方处理。
 */
class ContextManager(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    data class IndexStats(
        val classes: Int = 0,
        val interfaces: Int = 0,
        val objects: Int = 0,
        val functions: Int = 0,
        val components: Int = 0,
    ) {
        val total: Int get() = classes + interfaces + objects + functions + components
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE symbols (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT, kind TEXT, file TEXT, line INTEGER,
                summary TEXT, tags TEXT, deps TEXT
            )"""
        )
        db.execSQL("CREATE INDEX idx_symbols_name ON symbols(name)")
        db.execSQL("CREATE INDEX idx_symbols_kind ON symbols(kind)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS symbols")
        onCreate(db)
    }

    /** 重建索引：清空后写入 [CodeIndexer] 的全量结果，返回统计。 */
    fun indexProject(rootDir: File): IndexStats {
        val symbols = CodeIndexer.indexDirectory(rootDir)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("symbols", null, null)
            for (s in symbols) {
                db.execSQL(
                    "INSERT INTO symbols (name,kind,file,line,summary,tags,deps) VALUES (?,?,?,?,?,?,?)",
                    arrayOf(s.name, s.kind, s.file, s.line, s.summary, s.tags.joinToString(","), s.deps.joinToString(",")),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return stats()
    }

    /** 按名称子串 / 类型 / 标签检索，返回前 [limit] 条。 */
    fun query(name: String? = null, kind: String? = null, tag: String? = null, limit: Int = 50): List<CodeSymbol> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        name?.let { clauses.add("name LIKE ?"); args.add("%$it%") }
        kind?.let { clauses.add("kind = ?"); args.add(it) }
        tag?.let { clauses.add("tags LIKE ?"); args.add("%$it%") }
        val where = if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")
        val cursor = readableDatabase.rawQuery(
            "SELECT name,kind,file,line,summary,tags,deps FROM symbols$where ORDER BY name LIMIT $limit",
            args.toTypedArray(),
        )
        val out = mutableListOf<CodeSymbol>()
        cursor.use {
            while (it.moveToNext()) {
                out.add(
                    CodeSymbol(
                        name = it.getString(0),
                        kind = it.getString(1),
                        file = it.getString(2),
                        line = it.getInt(3),
                        summary = it.getString(4),
                        tags = it.getString(5).split(',').filter { t -> t.isNotBlank() },
                        deps = it.getString(6).split(',').filter { d -> d.isNotBlank() },
                    )
                )
            }
        }
        return out
    }

    fun stats(): IndexStats {
        val cursor = readableDatabase.rawQuery("SELECT kind, COUNT(*) FROM symbols GROUP BY kind", null)
        var classes = 0
        var interfaces = 0
        var objects = 0
        var functions = 0
        var components = 0
        cursor.use {
            while (it.moveToNext()) {
                when (it.getString(0)) {
                    "class" -> classes = it.getInt(1)
                    "interface" -> interfaces = it.getInt(1)
                    "object" -> objects = it.getInt(1)
                    "function" -> functions = it.getInt(1)
                    "component" -> components = it.getInt(1)
                }
            }
        }
        return IndexStats(classes, interfaces, objects, functions, components)
    }

    fun clear() = writableDatabase.delete("symbols", null, null)

    companion object {
        const val DB_NAME = ".aidev-context.db"
        const val DB_VERSION = 1
    }
}
