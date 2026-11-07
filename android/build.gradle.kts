// Top-level build file. Plugins are declared here (not applied) so every module
// resolves them from the same classloader and version catalog.
//
// AGP 9 has built-in Kotlin support, so org.jetbrains.kotlin.android is intentionally absent.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
