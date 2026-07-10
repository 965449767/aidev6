---
description: 读取最新崩溃报告（MCP JSON），用于自我修正闭环
---

当你（OpenCode 智能体）需要诊断刚刚构建并运行的应用崩溃时，使用此命令。

宿主在拉起新构建的 App 后，会通过 Shizuku 抓取该包 logcat，
过滤 FATAL EXCEPTION / 崩溃堆栈，写成标准 MCP 风格 JSON 到 `home/.aidev-mcp/latest.json`
（历史报告保留为 `home/.aidev-mcp/crash-<ts>.json`）。

用法:
- `aidev-crash-report` — 打印最新崩溃报告
- `aidev-crash-report --wait` — 等待最多 60s 直到报告生成
- `aidev-crash-report --package com.example.app` — 指定包名（由宿主抓取该包）

报告结构（MCP JSON）:
```
{
  "type": "mcp/crash-report",
  "package": "<包名>",
  "time": <毫秒时间戳>,
  "fatal": "<FATAL EXCEPTION: ...>",
  "stack": [ "<崩溃堆栈行...>" ],
  "raw": "<原始 logcat 片段>"
}
```

拿到报告后：分析根因 → 修改 `/workspace` 下源码 → 运行 `aidev-build-request` 重新编译部署，
即完成「写码→编译→运行→崩溃→进化」闭环。
