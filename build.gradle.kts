// Root build file.
//
// AGP 9.4.1 ships with KGP 2.2.10. Ktor 3.6.0 publishes Kotlin 2.3.21 metadata,
// so we raise the Kotlin Gradle plugin on the buildscript classpath. This is the
// pattern documented in 安卓APK构建指南-macOS.md §4.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    }
}

plugins {
    // Do NOT add org.jetbrains.kotlin.android: AGP 9 has built-in Kotlin.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
