---
name: debug
description: Use when the Android app crashes, throws exceptions, behaves unexpectedly, or the user asks to check logs. Covers logcat retrieval, crash analysis, and fix workflow.
---

# Android Debug Skill

## Tool Priority

ARM64 QEMU 环境下，按以下顺序使用调试工具：

| # | Tool | When |
|---|------|------|
| 1 | `aidev-shizuku exec 'logcat ...'` | 首选，灵活 grep |
| 2 | `aidev-logcat <pkg>` | 快速查看指定包日志 |
| 3 | `adb` (via QEMU) | ADB 不稳定，备选 |

## Crash Diagnosis Flow

### 1. 获取崩溃日志

```bash
aidev-shizuku exec 'logcat -d -v threadtime 2>&1 | grep -E "AndroidRuntime|FATAL|com\.aidev\.three" | tail -30'
```

### 2. 获取完整堆栈

```bash
aidev-shizuku exec 'logcat -d -v threadtime 2>&1 | grep -B5 -A30 "Caused by" | tail -60'
```

### 3. 搜索常见错误

```bash
aidev-error-why <keyword>
```

## App Restart After Fix

编译完成后通过 Shizuku 安装 APK：

```bash
aidev-shizuku install /sdcard/AIDev/app-debug.apk
```

## Logcat Filters

| Scenario | Command |
|---|---|
| 实时跟踪崩溃 | `aidev-shizuku exec 'logcat -v threadtime \| grep -E "AndroidRuntime\|Caused by"'` |
| 清除旧日志 | `aidev-shizuku exec 'logcat -c'` |
| 指定 PID | `aidev-shizuku exec 'logcat --pid=\$(pidof com.aidev.three) -d -v threadtime'` |
