package com.aidev.four

import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Immutable
data class ScaffoldTemplate(
    val name: String,
    val label: String,
    val desc: String,
    val dirName: String,
)

@Immutable
data class ProjectForm(
    val projectName: String = "",
    val packageName: String = "com.example.app",
    val templateName: String = "empty-activity",
)

object ProjectScaffoldState {
    val templates = mutableStateListOf(
        ScaffoldTemplate("empty-activity", "Empty Activity", "单页面 Compose 项目", "android-empty"),
        ScaffoldTemplate("nav-activity", "Navigation + 3 Tabs", "底部导航三标签样板", "android-nav"),
        ScaffoldTemplate("basic-terminal", "Basic Terminal App", "最小终端嵌入示例", "terminal-minimal"),
    )

    var form by mutableStateOf(ProjectForm())
    var onSendToTerminal: ((String) -> Unit)? = null

    fun generateScript(): String {
        val f = form
        if (f.projectName.isBlank()) return ""
        val tmpl = templates.find { it.name == f.templateName } ?: return ""
        val pkgPath = f.packageName.replace('.', '/')
        return buildString {
            appendLine("#!/system/bin/sh")
            appendLine("# AIDev 脚手架生成器 — ${tmpl.label}")
            appendLine("set -e")
            appendLine("")
            appendLine("BASE_DIR=\"\$HOME/projects\"")
            appendLine("PROJECT_DIR=\"\$BASE_DIR/${f.projectName}\"")
            appendLine("if [ -d \"\$PROJECT_DIR\" ]; then")
            appendLine("  echo \"错误: 项目目录已存在: \$PROJECT_DIR\"")
            appendLine("  exit 1")
            appendLine("fi")
            appendLine("mkdir -p \"\$PROJECT_DIR/app/src/main/java/$pkgPath\"")
            appendLine("mkdir -p \"\$PROJECT_DIR/app/src/main/res/values\"")
            appendLine("mkdir -p \"\$PROJECT_DIR/gradle/wrapper\"")
            appendLine("")
            appendLine("cat > \"\$PROJECT_DIR/settings.gradle.kts\" << 'EOF'")
            appendLine("pluginManagement {")
            appendLine("    repositories {")
            appendLine("        google()")
            appendLine("        mavenCentral()")
            appendLine("        gradlePluginPortal()")
            appendLine("    }")
            appendLine("}")
            appendLine("dependencyResolutionManagement {")
            appendLine("    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)")
            appendLine("    repositories {")
            appendLine("        google()")
            appendLine("        mavenCentral()")
            appendLine("    }")
            appendLine("}")
            appendLine("rootProject.name = \"${f.projectName}\"")
            appendLine("include(\":app\")")
            appendLine("EOF")
            appendLine("")
            appendLine("cat > \"\$PROJECT_DIR/build.gradle.kts\" << 'EOF'")
            appendLine("plugins {")
            appendLine("    id(\"com.android.application\") version \"8.7.3\" apply false")
            appendLine("    id(\"org.jetbrains.kotlin.android\") version \"2.0.21\" apply false")
            appendLine("}")
            appendLine("EOF")
            appendLine("")
            appendLine("cat > \"\$PROJECT_DIR/app/build.gradle.kts\" << 'EOF'")
            appendLine("plugins {")
            appendLine("    id(\"com.android.application\")")
            appendLine("    id(\"org.jetbrains.kotlin.android\")")
            appendLine("}")
            appendLine("android {")
            appendLine("    namespace = \"${f.packageName}\"")
            appendLine("    compileSdk = 35")
            appendLine("    defaultConfig {")
            appendLine("        applicationId = \"${f.packageName}\"")
            appendLine("        minSdk = 26")
            appendLine("        targetSdk = 35")
            appendLine("        versionCode = 1")
            appendLine("        versionName = \"1.0\"")
            appendLine("    }")
            appendLine("    buildFeatures { compose = true }")
            appendLine("    composeOptions { kotlinCompilerExtensionVersion = \"1.5.15\" }")
            appendLine("}")
            appendLine("dependencies {")
            appendLine("    implementation(platform(\"androidx.compose:compose-bom:2024.12.01\"))")
            appendLine("    implementation(\"androidx.compose.ui:ui\")")
            appendLine("    implementation(\"androidx.compose.material3:material3\")")
            appendLine("    implementation(\"androidx.activity:activity-compose:1.9.3\")")
            appendLine("}")
            appendLine("EOF")
            appendLine("")
            appendLine("cat > \"\$PROJECT_DIR/app/src/main/AndroidManifest.xml\" << 'EOF'")
            appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            appendLine("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">")
            appendLine("    <application android:label=\"${f.projectName}\">")
            appendLine("        <activity android:name=\".MainActivity\" android:exported=\"true\">")
            appendLine("            <intent-filter>")
            appendLine("                <action android:name=\"android.intent.action.MAIN\" />")
            appendLine("                <category android:name=\"android.intent.category.LAUNCHER\" />")
            appendLine("            </intent-filter>")
            appendLine("        </activity>")
            appendLine("    </application>")
            appendLine("</manifest>")
            appendLine("EOF")
            appendLine("")
            appendLine("cat > \"\$PROJECT_DIR/app/src/main/java/$pkgPath/MainActivity.kt\" << 'EOF'")
            appendLine("package ${f.packageName}")
            appendLine("")
            appendLine("import android.os.Bundle")
            appendLine("import androidx.activity.ComponentActivity")
            appendLine("import androidx.activity.compose.setContent")
            appendLine("import androidx.compose.material3.Text")
            appendLine("")
            appendLine("class MainActivity : ComponentActivity() {")
            appendLine("    override fun onCreate(savedInstanceState: Bundle?) {")
            appendLine("        super.onCreate(savedInstanceState)")
            appendLine("        setContent { Text(\"Hello from ${f.projectName}!\") }")
            appendLine("    }")
            appendLine("}")
            appendLine("EOF")
            appendLine("")
            appendLine("echo \"✅ 项目已创建: \$PROJECT_DIR\"")
            appendLine("echo \"Next: cd \$PROJECT_DIR && gradle wrapper && ./gradlew assembleDebug\"")
        }
    }
}
