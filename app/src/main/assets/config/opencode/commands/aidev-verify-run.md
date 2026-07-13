---
description: 运行验证黑盒 — 窗口内监控目标包是否崩溃/ANR，返回确定结论
---

当你（OpenCode 智能体）完成「构建 → 部署」后，用它确认应用**运行不崩**。

这是「改码 → 构建 → 运行 → 确认不崩」闭环的标准黑盒之一：入口固定、出口确定，
内部通过 Shizuku 桥抓 logcat，不依赖你手算等待时间或手查日志文件。

用法:
- `aidev-verify-run --pkg <包名>` — 默认监控 8 秒
- `aidev-verify-run --pkg <包名> --window 15` — 监控 15 秒
- `aidev-verify-run --pkg <包名> --launch` — 监控前先尝试启动应用

标准出口（stdout JSON，直接解析）:
```
{
  "pkg": "<包名>",
  "running": true,            # 窗口内应用是否在跑
  "crashed": true,            # 是否捕获到崩溃/ANR
  "crash_log_path": "<绝对路径>|null",  # 崩溃日志路径（crashed=true 时有效）
  "window_ms": 8000,
  "error": "..."|null
}
```

闭环用法:
```
build  → 若 success=false，读 log_path 改码重来
deploy → 安装并启动
verify → 若 crashed=true，读 crash_log_path 改码重来；否则本轮完成
```
