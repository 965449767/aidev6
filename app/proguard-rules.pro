# AndroidManifest-registered components (referenced by framework by name)
-keep class com.aidev.three.AIDevApp { *; }
-keep class com.aidev.three.ShellActivity { *; }
-keep class com.aidev.three.KeepAliveService { *; }
-keep class com.aidev.three.OpenCodeMonitorService { *; }
-keep class com.aidev.three.KeepAliveBootReceiver { *; }
-keep class com.aidev.three.OpenCodeActionReceiver { *; }
-keep class com.aidev.three.SysCommandReceiver { *; }

# Library reflection targets
-keep class rikka.shizuku.** { *; }
-keep class com.termux.terminal.TerminalSession { *; }
-keep class com.termux.terminal.TerminalEmulator { *; }
