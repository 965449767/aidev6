# UI 设计标准（宪法级，AI 强制）

> 定位：本文件定义 aidev6 **UI 工作的硬约束**。任何涉及 Compose 界面、组件、主题、颜色的改动，AI 必须无条件遵守，不可「按感觉来」。
> 详细依据与组件规范见 [`docs/DESIGN_SYSTEM.md`](../../docs/DESIGN_SYSTEM.md)；本文件是「必须」，文档是「为什么」。
> 与 `rules/core/ANDROID.md`（Android 工程标准）互补；冲突时以本文件 + 根 `AGENTS.md` 硬约束为准。

## 0. 产品定位（决定一切取舍）

aidev6 是**开发工具**（DevCenter），不是消费软件。优先级恒为：

```
稳定 > 信息密度 > 层级 > 效率 > 动画
```

不追求花哨动效与大面积留白；追求一眼可读、操作直达。

## 1. 从信息架构出发，不从「页面+按钮」出发

- 设计任何界面先问：**信息 → 状态 → 操作** 三层是什么，再决定布局。
- 禁止「首页 → 按钮 → 跳转 → 更多按钮」的平铺式堆砌。
- 入口收敛到单一 **Dashboard（控制中心）**：当前项目 / 任务 / Bug / Build / AI / 最近活动，以卡片网格呈现。

## 2. 导航

- 宽屏（横屏 / 平板）：用 **NavigationRail**（左侧竖排），条目可多于 5 个。
- 竖屏手机：可用底部导航，但**禁止多于 5 个 Tab 平铺**。
- 禁止用「5 个底部 Tab 平铺所有功能」代替 NavigationRail。

## 3. 组件：用卡片与网格，不重新发明基础控件

- 优先 **Card** 承载信息，优先 **LazyVerticalGrid（2 列）** 而非长 List，提升信息密度。
- 新页面由以下**组件库**拼装，禁止重新设计基础控件（按钮/输入框/对话框/导航）：
  - `InfoCard` / `StatusCard` / `ActionCard` / `MetricChip` / `SectionHeader`
  - `TerminalPanel` / `LogViewer` / `Timeline`
  - 统一状态：`LoadingState` / `EmptyState` / `ErrorState` / `SuccessState`
- 这些组件未实现前，先用 Material 3 原生 `Card`/`Surface` 按 §4–§6 的 Token 拼；禁止引入自定义样式偏离设计系统。

## 4. 颜色：只用 Token，禁止散落色值

- 全项目颜色**只能**来自主题 Token，禁止在业务代码写 `Color(0xFF…)`（主题定义处 `AIDevTheme.kt` 除外）。
- 语义色映射（Material 3 槽位）：
  - `Primary` = 蓝 `#4C8DFF`（深色）/ `#1A73E8`（浅色）
  - `Success` = `secondary` 槽 = `#3FB950` / `#1E8E3E`
  - `Warning` = `tertiary` 槽 = `#E3B341` / `#B8860B`
  - `Error` = `error` 槽 = `#F85149` / `#C0392B`
- 表面阶（中性）：
  - `Background` `#101114` / `#F8F9FA`
  - `Surface` `#17191D` / `#FFFFFF`
  - `SurfaceVariant` `#24262B` / `#E7EAEE`
  - `Outline` `#4A4D52` / `#C2C7CD`
  - 文本 `onSurface` / `onSurfaceVariant` / `onPrimary`
- **约束：全项目 ≤ 8 种颜色**。禁止紫/黄/粉/青等杂色；所有中性统一走表面阶。

## 5. 间距与尺寸（只取这些值）

- 间距：`4 / 8 / 12 / 16 / 24 / 32 dp`，其余不用。常量见 `AIDevTheme.kt` 的 `Spacing`（s4/s8/s12/s16/s24/s32）。
- 圆角：`Button 12dp` / `Card 16dp` / `Dialog 28dp`。常量见 `Radius`（button/card/dialog）。

## 6. 字体与图标

- 代码 / 数字 / 终端：等宽字体（目标 JetBrains Mono；资源未就位时用 `FontFamily.Monospace`）。常量 `CodeFont`。
- 正文：Roboto（Material 3 默认）。
- 图标：统一 **Material Symbols Rounded**；禁止 `Outlined` + `Filled` + `Sharp` 混用。

## 7. 动效（只用四种）

- 仅 `fade` / `scale` / `expand` / `shared transition`；时长 `150–250ms`，统一 Material Motion。
- 禁止自定义花哨转场与无限循环动画。

## 8. 自检清单（提交 UI 改动前）

- [ ] 颜色全部来自 Token，无散落 `Color(0xFF…)`
- [ ] 间距/圆角取自 `Spacing`/`Radius` 常量
- [ ] 导航符合 §2（Rail / ≤5 Tab）
- [ ] 信息密度优先（Card + Grid），非大留白
- [ ] 图标统一 Rounded；代码/数字用等宽
- [ ] 动效仅四种之一，时长 ≤250ms
- [ ] 有 Loading/Empty/Error 状态处理
