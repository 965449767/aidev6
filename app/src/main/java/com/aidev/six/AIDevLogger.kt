package com.aidev.six

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * AIDev 统一日志框架。
 * 所有日志通过此对象输出，便于统一开关、分级和标签管理。
 *
 * 功能：
 * - 日志等级过滤（Release 默认 INFO，Debug 默认 DEBUG）
 * - 运行时 Tag 过滤（仅输出匹配的 tag）
 * - Session ID 追踪（每次启动唯一）
 * - 文件日志（按天轮转，单文件限 512KB）
 */
object AIDevLogger {

    /** 日志等级。数值越小越详细。 */
    enum class Level(val value: Int, val tag: String) {
        VERBOSE(2, "V"),
        DEBUG(3, "D"),
        INFO(4, "I"),
        WARN(5, "W"),
        ERROR(6, "E");
    }

    private const val GLOBAL_TAG = "AIDev"
    private const val MAX_FILE_SIZE = 512 * 1024L // 512KB per log file

    /** 日志开关（Release 包可关闭）。 */
    var enabled: Boolean = true

    /** 全局最低输出等级。低于此等级的日志被丢弃。 */
    @Volatile var level: Level = if (BuildConfig.DEBUG) Level.DEBUG else Level.INFO

    /** 本次运行 Session ID（启动时生成）。 */
    val sessionId: String = UUID.randomUUID().toString().substring(0, 8)

    /**
     * Tag 过滤器。非空时仅输出包含任一已注册 tag 的日志。
     * 为空（默认）不过滤，输出全部。
     */
    private val tagFilter = ConcurrentHashMap.newKeySet<String>()

    /** 注册一个需要输出的 Tag。 */
    fun addTagFilter(tag: String) { tagFilter.add(tag) }

    /** 移除 Tag 过滤。 */
    fun removeTagFilter(tag: String) { tagFilter.remove(tag) }

    /** 清除所有 Tag 过滤（恢复输出全部）。 */
    fun clearTagFilter() { tagFilter.clear() }

    /** 检查 Tag 是否通过过滤。 */
    private fun isTagAllowed(tag: String): Boolean =
        tagFilter.isEmpty() || tagFilter.any { tag.contains(it, ignoreCase = true) }

    // ─── 文件日志 ────────────────────────────────────────

    @Volatile private var fileWriter: BufferedWriter? = null
    @Volatile private var currentLogFile: File? = null
    @Volatile private var currentFileSize: Long = 0L
    @Volatile private var currentFileDate: String = ""
    private val fileLock = Any()
    private val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /**
     * 初始化文件日志目录。在 Application.onCreate 中调用一次。
     * @param logsDir 日志根目录（如 /sdcard/AIDev/logs/app/）
     */
    fun initFileLogging(logsDir: File) {
        synchronized(fileLock) {
            this.currentLogFile = null
            this.currentFileSize = 0L
            rotateIfNeeded(logsDir)
        }
    }

    private fun rotateIfNeeded(logsDir: File) {
        val today = dateFmt.format(Date())
        if (today != currentFileDate) {
            runCatching { fileWriter?.flush(); fileWriter?.close() }
            logsDir.mkdirs()
            val file = File(logsDir, "aidev-$today.log")
            currentLogFile = file
            currentFileSize = if (file.isFile) file.length() else 0L
            currentFileDate = today
            fileWriter = null // lazy open
        }
    }

    private fun writeToLogFile(logsDir: File, level: Level, tag: String, message: String) {
        synchronized(fileLock) {
            runCatching {
                rotateIfNeeded(logsDir)
                val f = currentLogFile ?: return
                if (currentFileSize >= MAX_FILE_SIZE) {
                    val rotated = File(f.parent, f.nameWithoutExtension + "-1.log")
                    f.renameTo(rotated)
                    currentFileSize = 0L
                    fileWriter?.flush(); fileWriter?.close()
                    fileWriter = null
                }
                if (fileWriter == null) {
                    fileWriter = BufferedWriter(FileWriter(f, true))
                }
                val ts = tsFmt.format(Date())
                val line = "[$sessionId][$ts][${level.tag}] $tag: $message\n"
                fileWriter!!.write(line)
                fileWriter!!.flush()
                currentFileSize += line.toByteArray().size
            }
        }
    }

    // ─── 公共 API（保持向后兼容）───────────────────────────

    @JvmStatic
    fun d(tag: String, message: String) {
        if (enabled && level.value <= Level.DEBUG.value && isTagAllowed(tag)) {
            Log.d("$GLOBAL_TAG.$tag", message)
        }
    }

    @JvmStatic
    fun i(tag: String, message: String) {
        if (enabled && level.value <= Level.INFO.value && isTagAllowed(tag)) {
            Log.i("$GLOBAL_TAG.$tag", message)
        }
    }

    @JvmStatic
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (enabled && level.value <= Level.WARN.value && isTagAllowed(tag)) {
            if (throwable != null) Log.w("$GLOBAL_TAG.$tag", message, throwable)
            else Log.w("$GLOBAL_TAG.$tag", message)
        }
    }

    @JvmStatic
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (enabled && level.value <= Level.ERROR.value && isTagAllowed(tag)) {
            if (throwable != null) Log.e("$GLOBAL_TAG.$tag", message, throwable)
            else Log.e("$GLOBAL_TAG.$tag", message)
        }
    }

    /**
     * 带文件落盘的 verbose 级别日志。
     * @param logsDir 文件日志目录（null 则不写文件）
     */
    fun v(tag: String, message: String, logsDir: File? = null) {
        if (enabled && level.value <= Level.VERBOSE.value && isTagAllowed(tag)) {
            Log.v("$GLOBAL_TAG.$tag", message)
            if (logsDir != null) writeToLogFile(logsDir, Level.VERBOSE, tag, message)
        }
    }

    /**
     * 带文件落盘的 debug 级别日志。
     */
    fun dFile(tag: String, message: String, logsDir: File) {
        if (enabled && level.value <= Level.DEBUG.value && isTagAllowed(tag)) {
            Log.d("$GLOBAL_TAG.$tag", message)
            writeToLogFile(logsDir, Level.DEBUG, tag, message)
        }
    }

    /**
     * 带文件落盘的 info 级别日志。
     */
    fun iFile(tag: String, message: String, logsDir: File) {
        if (enabled && level.value <= Level.INFO.value && isTagAllowed(tag)) {
            Log.i("$GLOBAL_TAG.$tag", message)
            writeToLogFile(logsDir, Level.INFO, tag, message)
        }
    }

    /**
     * 带文件落盘的 error 级别日志。
     */
    fun eFile(tag: String, message: String, logsDir: File, throwable: Throwable? = null) {
        if (enabled && level.value <= Level.ERROR.value && isTagAllowed(tag)) {
            if (throwable != null) Log.e("$GLOBAL_TAG.$tag", message, throwable)
            else Log.e("$GLOBAL_TAG.$tag", message)
            val msg = if (throwable != null) "$message\n${throwable.stackTraceToString()}" else message
            writeToLogFile(logsDir, Level.ERROR, tag, msg)
        }
    }

    /** 关闭文件日志。 */
    fun closeFileLogging() {
        synchronized(fileLock) {
            runCatching { fileWriter?.flush(); fileWriter?.close() }
            fileWriter = null
        }
    }
}
