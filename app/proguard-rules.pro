# AndroidManifest-registered components (referenced by framework by name)
-keep class com.aidev.six.AIDevApp { *; }
-keep class com.aidev.six.ShellActivity { *; }
-keep class com.aidev.six.KeepAliveService { *; }
-keep class com.aidev.six.KeepAliveBootReceiver { *; }
-keep class com.aidev.six.SysCommandReceiver { *; }

# Library reflection targets
-keep class rikka.shizuku.** { *; }
-keep class com.termux.terminal.TerminalSession { *; }
-keep class com.termux.terminal.TerminalEmulator { *; }

# JSch (mwiede fork, uses reflection for JCE providers)
-keep class com.jcraft.jsch.** { *; }
-keep class com.jcraft.jzlib.** { *; }
-dontwarn com.jcraft.jsch.**

# CommonMark (reflection for extension loading)
-keep class org.commonmark.** { *; }
-dontwarn org.commonmark.**
