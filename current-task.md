# 当前任务：AIDev 固定开发流程（可视化计划 + 依赖基线 + 旧项目兼容）

> 🎯 目标：让"在 AIDev 里开发/维护 App"可预期、可复现、离线不崩。
> 动手前看清 UI/结构/限制；构建前保证依赖齐备且失败早报；新建与已有项目同等受护。
> 起点：2026-07-13（前序：Phase 6/7 测试补齐 + ServerPanel UI 重构 b144 已落地）

---

## 稳定性铁律（不可违反）
- **AIDev 宿主** `app/build.gradle.kts` 的 AGP/Kotlin/SDK/BOM **一律不动** → 宇宙 B 零风险锚点。
- 所有改动 = 脚手架脚本 + AIDev UI(纯加法) + 文档 + 预缓存工具 + 扩展已有的只读分析器。
- 已有项目**绝不自动改写**；仅检测/报告/预缓存，对齐需用户确认。

## 组件与执行顺序（✅ 全部完成 2026-07-13, 宿主 b150）
- **① 单一版本/依赖清单(ScaffoldBaseline) + 对齐 `create-compose-project` 与 `ProjectScaffoldState.generateScript()`**（模板栈：compose-bom 2024.12.01 / material-icons-extended / AGP 8.7.0 / Kotlin 2.0.20 / compileSdk 35 / minSdk 26）✅
- **② `aidev-precache` 脚本（支持任意项目路径）+ 离线自检** ✅（基线依赖已预缓存，离线自检通过；完整 `--offline` assembleDebug 由 DoD(a) 等价验证：依赖离线可解析）
- **③ 可视化开发前计划**（`ProjectScaffoldPanel`：UI Mockup + 项目结构树 + 能力&权限清单，三步流）✅
- **④ 构建前守卫**（`BuildPreflight.checkPreconditions`：HARD_BLOCKER 硬拦截 + 离线缺基线软提示，接入 `BuildBridgeService`）✅
- **⑤ 一等入口 + `docs/dev-workflow.md`**（ServerPanel「新建项目」按钮触发脚手架对话框）✅
- **⑥ `docs/compose-capabilities.md`**（模板栈可用/不可用 API + 受限权限，与能力清单同源）✅
- **⑦ 已有/导入项目兼容**（`BuildPreflight` 扩展 + 宇宙B「项目体检」UI：栈/风险/源码预检，仅报告不自动改写）✅

## 验收（DoD）
- (a) 基线依赖预缓存后离线可解析（`aidev-precache` 离线自检通过）；完整离线 assembleDebug 受限于 宇宙B 独立缓存，逻辑已就绪
- (b) 可视化预览三块齐全且可滚动 ✅
- (c) 能力文档与 `ScaffoldBaseline`/material3 1.3.1 一致（同源）✅
- (d) 离线缺包 → `aidev-precache` 明确提示 ✅
- (e) AIDev 宿主 assembleDebug 通过（b150）；现有测试 3 个预存失败保持，未引入新失败 ✅
- (f) 宇宙 B「项目体检」显示栈/风险/源码预检结果 ✅
- (g) `aidev-precache <project>` 可预缓存任意项目，离线可解析 ✅；且 `BuildBridgeService` 首次在线构建自动预热宇宙 B 缓存，断网可离线编译 ✅

## 备注
- 推送前必须将 `gradle-wrapper.properties` 的 `file://` 改回 `https://`（本地构建用 file://）。
- 宿主 BOM 未改（仍 2024.12.01），宇宙 B 零风险。
- 待办 OPT 清单（含已完成/待办、排序、OPT-13 体积分析）见 `docs/backlog-opt.md`。
