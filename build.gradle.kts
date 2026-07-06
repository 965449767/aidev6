plugins {
    id("com.android.application") version "9.0.1" apply false
    // Kotlin plugin kept for AGP 9 opt-out mode (android.builtInKotlin=false)
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
