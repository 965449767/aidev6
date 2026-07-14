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
    const val PREFS_SHELL = "aidev_shell"
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
        const val PROJECTS_DIR_REL = "projects_dir_rel"
        const val EXTERNAL_AIDEV_DIR = "external_aidev_dir"
        const val PROJECT_ACTION_HISTORY = "project_action_history"
        const val RECENT_FILE_MORE = "recent_file_more"
        const val RECENT_TERMINAL_MORE = "recent_terminal_more"
        const val RECENT_AGENT_MORE = "recent_agent_more"
        const val TERMINAL_CUSTOM_KEYS = "terminal_custom_keys"
        const val TERMINAL_KEY_OVERRIDES = "terminal_key_overrides"
        const val TERMINAL_KEY_ALIASES = "terminal_key_aliases"
        const val TERMINAL_KEY_ORDER = "terminal_key_order"
        const val TERMINAL_PINNED_COMPLETIONS = "terminal_pinned_completions"
        const val AUTO_SHOW_KEYBOARD = "auto_show_keyboard"
        const val FILE_FAVORITES = "file_favorites"
        const val FILE_RECENT_DIRS = "file_recent_dirs"
        const val ONELINERS = "oneliners"
        const val SSH_CONNECTIONS = "connections"
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
        const val SELF_EVOLUTION_AUTONOMOUS = "self_evolution_autonomous"
        const val SELF_EVOLUTION_MODEL = "self_evolution_model"
        const val REPO_MODE = "repo_mode"
        // 桥接通信：true=Unix Domain Socket 主用（文件轮询兜底）；false=纯文件轮询（等价旧行为）
        const val BRIDGE_SOCKET_ENABLED = "bridge_socket_enabled"

    }
    // OpenCode HTTP API
    const val OPENCODE_BASE_URL = "http://127.0.0.1:4096"

    // 桥接通信：本机 TCP loopback 端口（仅 127.0.0.1 绑定，属局部 IPC）
    const val BRIDGE_SOCKET_PORT = 14096

    // 桥接 Socket 静态共享密钥：仅作本机 IPC 源认证，防止其他本地 App 向宿主注入 build/deploy/crash 请求帧。
    // 非密码学机密（APK 内可读），但可把「谁能发桥请求」限定为持有该 token 的客户端（宿主自带的 aidev-bridge）。
    // 若调整此处，须同步 assets/scripts/aidev-bridge.sh 中的 TOKEN。
    const val BRIDGE_SOCKET_TOKEN = "aidev-bridge-2026"

    // 自我进化「宇宙A 改码」可用的 OpenCode 免费模型（用户可在服务器中心手动切换）。
    // 额度耗尽时 opencode 会 exit 0 且空返回、不报错，故无法自动识别——由用户看对话内容判断后手动切换。
    val SELF_EVOLUTION_MODELS = listOf(
        "opencode/hy3-free",
        "opencode/deepseek-v4-flash-free",
        "opencode/mimo-v2.5-free",
        "opencode/north-mini-code-free",
        "opencode/nemotron-3-ultra-free",
        "opencode/big-pickle",
    )
    const val SELF_EVOLUTION_DEFAULT_MODEL = "opencode/hy3-free"

}
