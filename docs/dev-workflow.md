# 固定开发流程（dev-workflow）

> 目标：**在 AIDev 内置开发环境里「动手前先看清楚、不突然用不了」**。
> 本流程把「模板依赖基线 / 离线预缓存 / 开发前可视化预览 / 构建前护栏 / 旧项目兼容」串成一条固定链路。

## 1. 单一真相源：模板依赖基线

所有「由 AIDev 脚手架/生成」的 Android 项目，依赖定义以
`app/src/main/java/com/aidev/six/ScaffoldBaseline.kt` 为准：

| 项 | 值 |
|---|---|
| AGP | 8.7.0 |
| Kotlin | 2.0.20 |
| Compose BOM | 2024.12.01（material3 1.3.1） |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |
| 图标库 | `androidx.compose.material:material-icons-extended`（基线默认含，离线可用） |

注意：**这是「模板栈」（目标 App），故意与 AIDev 宿主栈（AGP 9.0.1 / Kotlin 2.0.21 / compileSdk 36）不同。**
宿主 `app/build.gradle.kts` 的 BOM 保持不变（同样 2024.12.01）。

两处生成器必须与 `ScaffoldBaseline` 保持一致：
- `ProjectScaffoldState.generateScript()`（App 内「新建项目」生成的 shell 脚本）
- `/usr/local/bin/create-compose-project`（终端一键建项目）

## 2. 离线预缓存：aidev-precache

模板基线依赖先预缓存进 Gradle 缓存，确保断网也能构建：

```bash
aidev-precache                 # 预缓存模板基线（compose-bom 2024.12.01 + material-icons-extended 等）
aidev-precache <project-dir>   # 预缓存指定项目的真实依赖
```

- 原理：把依赖解析结果下载进 Gradle 缓存，离线构建不再缺包。
- **自动同步编译缓存**：脚本会探测编译环境的 gradle 缓存真实路径
  （即 `filesDir/home/gradle-cache`，对应 `/host-home/gradle-cache`），
  把基线依赖也同步过去。这样编译环境**断网也能离线构建**。
- 也可显式指定落点：`aidev-precache --gradle-home <DIR>`（即 GRADLE_USER_HOME）。
- **自动预热**：App 内提交构建时，`BuildBridgeService` 会在编译前检测编译缓存是否缺基线标记；
  若缺且联网，自动跑 `./gradlew dependencies` 预热其缓存（落点 `/host-home/gradle-cache`）。
  因此首次在线构建后，**断网也能离线编译**，无需手动跑 `aidev-precache`（除非要提前备货）。
- 自带离线自检：缓存可离线解析才报成功。
- **仅联网时有意义**；离线运行会明确提示「请联网后重试」。

> 命中历史坑：早期离线只有 `material-icons-core` 缓存，`material-icons-extended` 拉不到，
> 导致 `Icon(Icons.xxx)` 编译失败。现已把该库纳入基线并预缓存。

## 3. 开发前可视化预览

App 内「新建项目」对话框（ServerPanel → 构建 → 新建项目）走三步：

1. **表单**：项目名 / 包名 / 模板选择
2. **可视化预览（动手前先看清楚）**：
   - ① UI 模拟图（静态示意手机界面，非真实预览）
   - ② 项目结构树（将生成的文件骨架）
   - ③ 能力 / 权限：默认具备能力 + 设备内受限/需谨慎权限（相机/麦克风/定位/通讯录/短信/电话）
3. **脚本预览**：将生成并在 Ubuntu 终端执行的脚本

这样在写第一行代码前，就能看清「项目长什么样、含哪些文件、能做什么、不能做什么」。

## 4. 构建前护栏（vibe coding 护栏·硬层）

`BuildPreflight.checkPreconditions(projectDir)` 在构建前运行：

- **HARD_BLOCKER 权限**（Manifest 含相机/麦克风/通讯录/短信/定位/电话等）→ **硬拦截，直接报错**，不浪费编译时间。
- 离线且基线依赖未预缓存 → 软提示：先 `aidev-precache` 再构建。

（另：`BuildPreflight.inspect` 还会自动修正常见「宇宙 B 必失败」写法：模块级 repositories、compileSdk≠36、Compose 配置不全。）

## 5. 旧项目兼容（非破坏性）

已有 Android 项目**不强制改写**。AIDev 会：

- 检测：AGP / Kotlin / compileSdk / Compose 配置，`BuildPreflight` 给出中文报告。
- 报告：列出与基线的差异与风险提示（见 ServerPanel 体检 UI）。
- 可选对齐：用户确认后才执行「对齐到基线」操作（默认不改写，避免破坏既有工程）。

## 6. 标准操作顺序（推荐）

```bash
# 0) 联网时先预热基线缓存（一次即可；会自动同步到宇宙 B 缓存）
aidev-precache

# 1) App 内：构建 → 新建项目 → 可视化预览 → 生成脚本到终端
# 2) 终端执行脚本创建项目
# 3) App 内：构建提交（构建前护栏自动生效；首次联网构建也会把依赖补进宇宙 B 缓存）
```

## 7. 变更基线时

改 `ScaffoldBaseline.kt` 一处 → 同步 `create-compose-project` 与 `generateScript()` → 重新 `aidev-precache`。
**禁止修改**：AGP/Kotlin 版本号对齐规则、宿主 `app/build.gradle.kts` 的 BOM（见 AGENTS.md 版本锁定）。
