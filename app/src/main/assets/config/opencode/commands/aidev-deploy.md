---
description: 部署黑盒 — 安装 APK 到真机并（可选）启动，返回确定结论
---

当你（OpenCode 智能体）完成构建、需要把 APK 装到设备并拉起时，使用此命令。

这是「改码 → 构建 → 运行 → 确认不崩」闭环的标准黑盒之一：与构建黑盒解耦，
内部通过 Shizuku 桥静默安装、resolve-activity 启动，不依赖你手敲 pm/am 命令。

用法:
- `aidev-deploy --apk <绝对路径> --pkg <包名>` — 安装并启动（默认）
- `aidev-deploy --apk <绝对路径> --pkg <包名> --no-launch` — 仅安装

标准出口（stdout JSON，直接解析）:
```
{
  "apk": "<路径>",
  "pkg": "<包名>",
  "installed": true,        # 安装并 pm list packages 校验落地
  "launched": true,         # 是否成功拉起
  "activity": "<启动组件>|null",
  "error": "..."|null
}
```

闭环用法:
```
build   → 成功得到 apk_path
deploy  → 安装并启动（installed/launched 须为 true）
verify  → 监控是否崩溃
```
