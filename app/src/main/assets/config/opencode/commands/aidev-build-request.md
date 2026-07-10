---
description: 提交构建请求，触发宿主在编译器宇宙 B 中闭环构建 Android 项目
---

当你（OpenCode 智能体）完成代码修改、希望宿主编译并部署时，使用此命令。

它会在宿主侧写入一个结构化请求文件 `home/.aidev-build-bridge/req-<id>.json`，
宿主的 BuildBridge 监听到后会在**宇宙 B（纯编译器 rootfs）**中执行：

    cd /workspace/<project> && ./gradlew assembleDebug --no-daemon

编译产物经共享 workspace 零延迟映射。成功后宿主静默安装并拉起应用，
并通过 Logcat 抓取崩溃，回传供你（智能体）自我修正，形成进化闭环。

用法:
- `aidev-build-request` — 默认构建 MyAndroidProject，自动安装并拉起
- `aidev-build-request --project MyApp` — 指定 workspace 下项目名
- `aidev-build-request --project /workspace/MyApp` — 指定 workspace 内绝对路径
- `aidev-build-request --no-install` — 仅构建，不安装
- `aidev-build-request --no-launch` — 构建并安装但不自动拉起
- `aidev-build-request --launch-package com.example.app` — 指定拉起包名

注意：项目必须位于共享 workspace（/workspace/...），这是 OpenCode 宇宙 A 与编译器宇宙 B 的唯一共享盘。
