# Compose 能力边界（模板基线）

> 适用基线：`compose-bom:2024.12.01` → `material3 1.3.1`、`material-icons-extended 1.7.6`、
> `AGP 8.7.0` / `Kotlin 2.0.20` / `compileSdk 35`。
> 写代码前先看清「能用什么、不能用什么」，避免运行时/编译时才发现用不了。

## 一、Material 3 组件可用性（material3 1.3.1）

✅ 可用（常用）：
- 布局/容器：`Scaffold`、`Surface`、`Card`、`Column/Row/Box`、`LazyColumn/LazyRow`、`HorizontalPager`
- 输入：`TextField`、`OutlinedTextField`、`Checkbox`、`Switch`、`RadioButton`、`Slider`、`DropdownMenu`
- 按钮：`Button`、`OutlinedButton`、`TextButton`、`FilledTonalButton`、`ElevatedButton`
- 导航：`NavigationBar`、`NavigationRail`、`NavigationDrawer`、`TopAppBar`、`TabRow`/`Tab`、`Badge`
- 反馈：`CircularProgressIndicator`、`LinearProgressIndicator`、`Snackbar`/`SnackbarHost`、`AlertDialog`
- 文字/图标：`Text`、`Icon`（配合 `material-icons-extended`）、`Divider`→`HorizontalDivider`
- 弹层：`ModalBottomSheet`、`ExposedDropdownMenuBox`、`AssistChip`/`FilterChip`/`SuggestionChip`

⚠ 此版本**没有**（高版本才有，勿用）：
- `FilledButton`（material3 1.4.0+ 才提供）→ 用 `Button` 代替
- `DatePicker`/`TimePicker`（`material3 1.4.0+` 稳定）
- `SearchBar`（实验性，需更高版本）
- `SegmentedButton`、`AdaptiveNavigationSuite`（部分高版本才有）

## 二、图标库（material-icons-extended 1.7.6）

- 基线**默认含**该库，离线可用。
- 用法：`Icon(Icons.Filled.Xxx, ...)` / `Icons.Outlined.Xxx` / `Icons.Rounded.Xxx`。
- ⚠ 只有该库**已声明**的图标名才能编译；随便写 `Icons.Filled.Whatever` 会报
  `Unresolved reference`。不确定时先在源码里确认存在，或改用 `Text`/`Image` 占位。

## 三、Compose 与 Android 限制

- `compileSdk = 35`：只能用 API 35 及以下；引用更高 API 会编译失败。
- 不要引入 `androidx.compose.material`（旧版 `Material` 组件）与 `material3` 混用导致重复/冲突；统一用 `material3`。
- 宿主栈（AGP 9.0.1 / compileSdk 36）与此模板栈**故意不同**，互不干扰。

## 四、设备内受限 / 需谨慎的权限

以下权限在 AIDev 设备环境内**受限或无硬件**，构建/安装可能失败或无法自测；
若确需请单独评估并在 `AndroidManifest.xml` 声明：

| 权限 | 说明 |
|---|---|
| `CAMERA` | 无相机硬件，无法自测 |
| `RECORD_AUDIO` | 无麦克风，无法自测 |
| `READ_CONTACTS` / `WRITE_CONTACTS` | 敏感权限，上架需说明 |
| `SEND_SMS` / `RECEIVE_SMS` / `READ_SMS` | 敏感权限，谨慎使用 |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 敏感权限，需前台声明 |
| `READ_PHONE_STATE` / `CALL_PHONE` | 敏感权限，谨慎使用 |

> 命中 `BuildPreflight.HARD_BLOCKER_PERMISSIONS` 会在构建前被**硬拦截**并报错。

## 五、离线构建前置

- 断网构建需依赖已预缓存：`aidev-precache`（见 `docs/dev-workflow.md`）。
- 未预缓存且离线 → 报 `Could not resolve ...`，先联网 `aidev-precache` 再构建。
