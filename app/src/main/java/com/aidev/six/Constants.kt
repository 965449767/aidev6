package com.aidev.six

/**
 * AIDev Terminal 全局常量定义。
 * 集中管理所有魔法字符串，避免硬编码散落在各处。
 */
object Constants {

    // 应用包名相关
    const val PACKAGE_NAME = "com.aidev.six"

    // 通知渠道
    const val NOTIFICATION_CHANNEL_ID = "aidev_terminal"
    // 内部广播 Action（exported=false，仅本应用可用）
    object Actions {
        const val NOTIFY = "${PACKAGE_NAME}.internal.NOTIFY"
        const val CLIP = "${PACKAGE_NAME}.internal.CLIP"
        const val VOLUME = "${PACKAGE_NAME}.internal.VOLUME"
        const val BRIGHTNESS = "${PACKAGE_NAME}.internal.BRIGHTNESS"
    }

    // SharedPreferences 文件名
    const val PREFS_NAME = "aidev_ui"
    const val REPO_ROOT = "/sdcard/.AIDevRepo"

    // SharedPreferences Key 常量
    object PrefKeys {
        const val THEME_PRESET = "theme_preset"
        const val BG_MODE = "bg_mode"
        const val BG_IMAGE_URI = "bg_image_uri"
        const val HAPTIC_TAP = "haptic_tap"
        const val FONT_SP = "font_sp"
        const val WRITE_SETTINGS_PROMPTED = "write_settings_prompted"
        const val SYNC_TERMINAL_FILES = "sync_terminal_files"
        const val CURRENT_PROJECT_PATH = "current_project_path"
        const val BACKUP_DIR = "backup_dir"
        const val EXTERNAL_AIDEV_DIR = "external_aidev_dir"
        const val PROJECT_ACTION_HISTORY = "project_action_history"
        const val TERMINAL_CUSTOM_KEYS = "terminal_custom_keys"
        const val TERMINAL_KEY_OVERRIDES = "terminal_key_overrides"
        const val TERMINAL_KEY_ALIASES = "terminal_key_aliases"
        const val TERMINAL_KEY_ORDER = "terminal_key_order"
        const val TERMINAL_PINNED_COMPLETIONS = "terminal_pinned_completions"
        const val AUTO_SHOW_KEYBOARD = "auto_show_keyboard"
        const val FILE_FAVORITES = "file_favorites"
        const val FILE_RECENT_DIRS = "file_recent_dirs"
        const val FILE_LAYOUT_MODE = "file_layout_mode"
        const val TERMINAL_THEME = "terminal_theme"
        const val USE_TRASH = "use_trash"
        const val FILE_SORT_MODE = "file_sort_mode"
        const val FILE_SHOW_HIDDEN = "file_show_hidden"
        const val FILE_CONFIRM_DELETE = "file_confirm_delete"
        const val FILE_TRASH_RETENTION = "file_trash_retention"
        const val KEEPALIVE_AUTO = "keepalive_auto"
        const val SWIPE_SENSITIVITY = "swipe_sensitivity"
        const val TERMINAL_BG_OVERRIDE = "terminal_bg_override"
        const val PROJECT_MODE = "project_mode"
        const val PROJECT_VIEW_TAB = "project_view_tab"
        const val LAST_TAB = "last_tab"
        const val REPO_MODE = "repo_mode"
        // 桥接通信：true=Unix Domain Socket 主用（文件轮询兜底）；false=纯文件轮询（等价旧行为）
        const val BRIDGE_SOCKET_ENABLED = "bridge_socket_enabled"
        const val BRIDGE_TOKEN = "bridge_token"

    }

    // 桥接通信：本机 TCP loopback 端口（仅 127.0.0.1 绑定，属局部 IPC）
    const val BRIDGE_SOCKET_PORT = 14096

    // 桥接通信：动态共享密钥（首次启动随机生成，存 SharedPreferences + 写入文件供 shell 脚本读取）。
    // 仅作本机 IPC 源认证，防止其他本地 App 向宿主注入 build/deploy 请求帧。
    // 若调整此处，须同步 assets/scripts/aidev-bridge.sh 中的读取逻辑。
    @Deprecated("Use PreferencesManager.bridgeToken instead", ReplaceWith("PreferencesManager(ctx).bridgeToken"))
    const val BRIDGE_SOCKET_TOKEN = "aidev-bridge-2026"

}
