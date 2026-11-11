plugins {
    id("com.android.application") version "9.5.1" apply false
    // AGP 9 compiles Kotlin itself (built-in Kotlin); this only pins the Kotlin Gradle plugin
    // version it uses, which must match the Compose compiler plugin below.
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
