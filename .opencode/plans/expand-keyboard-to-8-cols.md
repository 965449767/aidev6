# 虚拟键盘扩展 6→8 列

## 改动文件

### 1. `TerminalPanel.kt:629`
```
- private const val KEYS_PER_ROW = 6
+ private const val KEYS_PER_ROW = 8
```

2 行 × 8 列 = 16 键。

### 2. `VirtualKeyboardManager.kt:366-383`

在 `defaults` 列表中插入 4 个新键（插在每行最后一个元素之前）：

```
// 行 0 — 在 / 和 - 之间插入 . ~
... "/", "/", "cd /", "slash"),
+ EmbeddedVirtualKey(".", ".", "", "dot"),
+ EmbeddedVirtualKey("~", "~", "", "tilde"),
... "-", "-", "cd -", "dash"),

// 行 1 — 在 → 和 SPC 之间插入 _ :
... "\u2192", "\u001B[C", "\u001B[F", "right"),
+ EmbeddedVirtualKey("_", "_", "", "underscore"),
+ EmbeddedVirtualKey(":", ":", "", "colon"),
... "SPC", " ", "pwd", "space"),
```

### 3. `VirtualKeyboardManager.kt:383`
```
- return (customizedDefaults + custom).take(12)
+ return (customizedDefaults + custom).take(16)
```

## 最终布局

```
ESC  CTRL  TAB  ↑    /    .    ~     -
C    ⏎     ←    ↓    →    _    :     SPC
```

## 验证

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew :app:assembleDebug --no-daemon
```
