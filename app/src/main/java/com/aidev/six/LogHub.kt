package com.aidev.six

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一日志管理器（覆盖模式）。
 *
 * 每次构建/崩溃只保留最新一条日志，直接覆盖，无需轮转：
 *   logs/<project>/build.log
 *   logs/<package>/crash.log
 *   logs/<project>/latest-build-profile.json
 *   logs/<package>/latest-crash-profile.json
 */
object LogHub {

    private val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // ─── 日志写入 ────────────────────────────────

    class LogWriter(@JvmField var file: File, private val tag: String) {
        private val startTime = System.currentTimeMillis()

        fun append(line: String) {
            val elapsed = System.currentTimeMillis() - startTime
            val ts = tsFmt.format(Date())
            file.appendText("[$ts +${elapsed}ms] $line\n")
        }

        fun finish() {
            val total = System.currentTimeMillis() - startTime
            append("=== 完成 (${total}ms) ===")
        }

        /** 移动日志文件到指定目录（用于 crash 日志解析出 pkg 后归位）。 */
        fun moveTo(targetDir: File, newName: String): File {
            val oldFile = file
            if (!oldFile.exists()) return file
            if (!targetDir.isDirectory) targetDir.mkdirs()
            val target = File(targetDir, newName)
            if (target.absolutePath != oldFile.absolutePath) {
                oldFile.renameTo(target)
                file = target
            }
            return target
        }
    }

    // ─── Gradle 输出过滤 ────────────────────────────────

    /**
     * 过滤 Gradle 构建输出，减少噪音。
     * - 保留：error、FAILED、BUILD、Exception、关键 Task 行
     * - 折叠：WARNING 行合并为一条计数
     * - 丢弃：空行、纯空格行、Deprecated 警告
     */
    class GradleFilter {
        private var warningCount = 0
        private var lastWarning: String = ""

        fun shouldKeep(line: String): Boolean {
            val trimmed = line.trim()
            if (trimmed.contains("error:", ignoreCase = true)) return true
            if (trimmed.contains("FAILED")) return true
            if (trimmed.contains("BUILD")) return true
            if (trimmed.contains("Exception")) return true
            if (trimmed.contains("> Task")) return true
            if (trimmed.contains("FAILURE")) return true
            if (trimmed.contains("What went wrong")) return true
            if (trimmed.startsWith("[")) return true
            if (trimmed.startsWith("===")) return true
            if (trimmed.startsWith("▶") || trimmed.startsWith("◀")) return true
            if (trimmed.startsWith("→") || trimmed.startsWith("⚠") || trimmed.startsWith("✓") || trimmed.startsWith("✗")) return true
            if (trimmed.startsWith("💡")) return true
            if (trimmed.startsWith("WARNING:") || trimmed.startsWith("WARNING :")) {
                warningCount++
                lastWarning = trimmed.take(80)
                return false
            }
            if (trimmed.contains("Deprecated Gradle features")) return false
            if (trimmed.contains("warning-mode all")) return false
            if (trimmed.contains("command_line_interface")) return false
            if (trimmed.isEmpty()) return false
            return true
        }

        fun flushWarnings(): String? {
            if (warningCount == 0) return null
            val msg = "⚠ [已折叠 $warningCount 条 Gradle 警告] $lastWarning"
            warningCount = 0
            lastWarning = ""
            return msg
        }
    }

    // ─── 目录工具 ────────────────────────────────

    /** 获取子目录（logsDir/<name>/），不存在则创建。 */
    fun subdir(logsDir: File, name: String): File {
        val dir = File(logsDir, name)
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }

    // ─── 构建日志 ────────────────────────────────

    /** 打开构建日志（覆盖写入 logs/<project>/build.log）。 */
    fun openBuildLog(logsDir: File, project: String): LogWriter {
        val dir = subdir(logsDir, project)
        val file = File(dir, "build.log")
        file.writeText("=== 构建 $project 开始于 ${dateFmt.format(Date())} ===\n")
        return LogWriter(file, "build")
    }

    // ─── 崩溃日志 ────────────────────────────────

    /**
     * 打开崩溃日志（临时位置）。
     * 解析出 pkg 后调用 writer.moveTo(subdir(logsDir, pkg), "crash.log") 归位。
     */
    fun openCrashLog(logsDir: File, tempId: String): LogWriter {
        val file = File(logsDir, "crash-$tempId.log")
        file.writeText("=== 崩溃报告 开始于 ${dateFmt.format(Date())} ===\n")
        return LogWriter(file, "crash")
    }

    // ─── 分步计时 ────────────────────────────────

    class StepTimer(private val writer: LogWriter) {
        private val steps = mutableListOf<StepRecord>()
        private var currentStep: StepRecord? = null

        fun beginStep(name: String) {
            currentStep?.let { endStep() }
            currentStep = StepRecord(name, System.currentTimeMillis())
            writer.append("▶ 阶段开始: $name")
        }

        fun endStep(detail: String = "") {
            val step = currentStep ?: return
            step.endMs = System.currentTimeMillis()
            step.detail = detail
            steps.add(step)
            currentStep = null
            val dur = step.durationMs
            writer.append("◀ 阶段结束: ${step.name} (${dur}ms)${if (detail.isNotBlank()) " — $detail" else ""}")
        }

        fun profileJson(): String {
            val total = steps.sumOf { it.durationMs }
            val arr = org.json.JSONArray()
            steps.forEach { s ->
                arr.put(org.json.JSONObject().apply {
                    put("name", s.name)
                    put("durationMs", s.durationMs)
                    put("detail", s.detail)
                    put("percent", if (total > 0) s.durationMs * 100 / total else 0)
                })
            }
            return org.json.JSONObject().apply {
                put("totalMs", total)
                put("steps", arr)
            }.toString(2)
        }

        data class StepRecord(val name: String, val startMs: Long) {
            var endMs: Long = 0L
            var detail: String = ""
            val durationMs: Long get() = if (endMs > 0) endMs - startMs else 0L
        }
    }

    // ─── 性能分析保存 ────────────────────────────────

    /** 覆盖写入 latest-<type>-profile.json 到指定项目目录。 */
    fun saveProfile(logsDir: File, profileJson: String, type: String, project: String) {
        val dir = subdir(logsDir, project)
        val latest = File(dir, "latest-${type}-profile.json")
        latest.writeText(profileJson)
    }
}
