---
description: 智能 Android 构建（已迁移至 aidev-build-request）
---

> **构建入口已统一为 `aidev-build-request`**（双宇宙离线编译流水线）。本命令不再使用旧式 `aidev-build`，请改用：

```sh
aidev-build-request --project /workspace/<应用名>
```

常用选项：
- `--project <路径>` — 待构建项目根目录（必须位于 `/workspace/` 下，宇宙B 才能编译）
- `--install` / `--no-install` — 编译完成后是否静默安装（默认安装）
- `--launch` / `--no-launch` — 安装后是否自动拉起（默认拉起）
- `--full` / `--test` / `--compile` — 全量 / 构建+单元测试 / 仅 Kotlin 编译检查

构建请求提交后，宿主 `BuildBridge` 会认领并在聊天里实时显示「准备→编译→安装→拉起」进度；构建日志导出到手机 `/sdcard/AIDev/last-build.log`。失败时可用 `aidev-error-why` 诊断。

当用户需要「构建 / 编译 / 打包 / 出 APK / 安装到手机」时，**必须在终端真正执行上述 `aidev-build-request` 命令**，而非只在回复文本里描述。
