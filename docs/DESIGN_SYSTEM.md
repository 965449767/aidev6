# DESIGN_SYSTEM.md（aidev6 设计系统，single source of truth）

> 本文件是设计系统的**完整依据**。AI 写 UI 时的「必须」见 [`rules/core/UI.md`](../rules/core/UI.md)；本文件解释「为什么」与「具体规格」。
> 任何 UI 改动若与本文件冲突，以 `rules/core/UI.md` 为准。

## 1. 设计语言

- 采用 **Material 3 + Material You**；不自制基础控件（按钮/输入框/对话框/导航）。
- 产品定位：**开发工具（DevCenter）**。优先级 `稳定 > 信息密度 > 层级 > 效率 > 动画`。
- 参考而非照搬：Cursor / Android Studio / VSCode / GitHub Desktop / Raycast / Warp / Arc / Material 3。

## 2. Color Tokens

语义色（映射到 Material 3 槽位）：

| Token | 深色 | 浅色 | M3 槽位 |
|---|---|---|---|
| Primary | `#4C8DFF` | `#1A73E8` | `primary` |
| Success | `#3FB950` | `#1E8E3E` | `secondary` |
| Warning | `#E3B341` | `#B8860B` | `tertiary` |
| Error | `#F85149` | `#C0392B` | `error` |

表面阶（中性）：

| Token | 深色 | 浅色 |
|---|---|---|
| Background | `#101114` | `#F8F9FA` |
| Surface | `#17191D` | `#FFFFFF` |
| SurfaceVariant | `#24262B` | `#E7EAEE` |
| Outline | `#4A4D52` | `#C2C7CD` |
| OnSurface | `#FFFFFF` | `#1F2937` |
| OnSurfaceVariant | `#CCCCCC` | `#5F6B7A` |
| OnPrimary | `#FFFFFF` | `#FFFFFF` |

**约束：全项目 ≤ 8 种颜色**。禁止紫/黄/粉/青等杂色；所有中性统一走表面阶。代码侧所有颜色来自 `AIDevTheme.kt` 的 scheme，业务代码禁止 `Color(0xFF…)`。

## 3. Typography

- **JetBrains Mono**：数字、代码、终端（资源就位前用 `FontFamily.Monospace`，常量 `CodeFont`）。
- **Roboto**：正文（Material 3 默认）。
- 字号阶沿用 Material 3 默认（`labelSmall` / `bodyMedium` / `titleSmall` / `displayMedium` …）。

## 4. Spacing Scale

只取：`4 / 8 / 12 / 16 / 24 / 32 dp`。常量 `Spacing.s4 … s32`（`AIDevTheme.kt`）。
典型用法：Padding 16、Card Gap 12、Page Margin 24。

## 5. Radius

- Button `12dp` · Card `16dp` · Dialog `28dp`。常量 `Radius.button / card / dialog`。
- 禁止 8/18/22/30/13 等散值。

## 6. Iconography

- 统一 **Material Symbols Rounded**。
- 禁止 `Outlined` + `Filled` + `Sharp` 混用。

## 7. Motion

- 仅 `fade` / `scale` / `expand` / `shared transition`。
- 时长 `150–250ms`，统一 Material Motion。

## 8. 组件库（可拼装，禁止重新设计页面）

| 组件 | 用途 |
|---|---|
| `InfoCard` | 展示一条信息（标题 + 副文） |
| `StatusCard` | 状态块（Build/AI/Crash：状态 + 指标） |
| `ActionCard` | 可点击动作入口（Analyze / Generate Task / Review / Debug） |
| `MetricChip` | 小指标（Tasks/Bugs/Knowledge 计数） |
| `SectionHeader` | 分区标题 |
| `TerminalPanel` | 终端视图容器 |
| `LogViewer` | 日志查看器 |
| `Timeline` | 时间线（构建/任务历史） |
| `LoadingState` / `EmptyState` / `ErrorState` / `SuccessState` | 统一四态 |

## 9. 页面模板

`Dashboard` / `Detail` / `Editor` / `Console` / `Settings`。均以组件库拼装。

## 10. 状态设计

每个数据区必须有 `Loading / Empty / Error / Success` 四态，不裸奔。

## 11. 响应式

- 竖屏手机：单列 Card 流。
- 横屏 / 平板：2 列 `LazyVerticalGrid` + 左侧 `NavigationRail`。

## 12. 无障碍

- 文本对比度达标；触摸目标 ≥ 48dp；关键图形加内容描述（`contentDescription`）。
