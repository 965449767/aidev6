# ============================================================
# AIDev6 ProGuard / R8 规则
# ============================================================
# 设计原则：只保留必需的入口点，其余让 R8 自由优化/内联/移除。
# 避免使用 `-keep class ** { *; }` 全量保留，那会抵消 R8 大部分收益。

# ── AndroidManifest 注册的组件（框架按名称反射实例化）────────────
-keep class com.aidev.six.AIDevApp { *; }
-keep class com.aidev.six.ShellActivity { *; }
-keep class com.aidev.six.KeepAliveService { *; }
-keep class com.aidev.six.KeepAliveBootReceiver { *; }
-keep class com.aidev.six.SysCommandReceiver { *; }

# ── Shizuku 库（通过 Binder 反射调用）────────────────────────────
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# ── Termux 终端库（关键类被 JNI / 反射引用）───────────────────────
-keep class com.termux.terminal.TerminalSession { *; }
-keep class com.termux.terminal.TerminalEmulator { *; }
-keep class com.termux.view.TerminalView { *; }
-dontwarn com.termux.**

# ── JSch（mwiede fork，使用反射加载 JCE 加密提供者）──────────────
-keep class com.jcraft.jsch.** { *; }
-keep class com.jcraft.jzlib.** { *; }
-dontwarn com.jcraft.jsch.**

# ── CommonMark（反射加载扩展）──────────────────────────────────────
-keep class org.commonmark.** { *; }
-dontwarn org.commonmark.**

# ── Jetpack Compose：R8 已知优化规则（Compose BOM 自带大部分，以下为补充）─
# 让 R8 识别 @Composable 注解并进行 function inlining / class merging
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable

# Compose runtime：避免 R8 移除 @Stable/@Immutable 注解
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.runtime.**

# ── Kotlin 协程：Continuation 恢复点需保留 ────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-dontwarn kotlinx.coroutines.**

# ── Kotlin 反射 ────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$DefaultImpls { *; }

# ── 枚举：保留 values()/valueOf() ─────────────────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Serializable（若有） ───────────────────────────────────────────
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ── 调试辅助：保留行号信息（release 也保留，便于崩溃堆栈定位）──────
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# 注意：不要加 -dontoptimize / -dontobfuscate
# release 构建通过 isMinifyEnabled = true 启用 R8，此处只定义 keep 规则。
