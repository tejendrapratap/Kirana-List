package com.grocerysync.app.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.grocerysync.app.text.Texts

/**
 * The line at the bottom of Settings, e.g. "Version 1.2", in the language shown; null (no line) when the name is
 * unknown.
 */
internal fun versionText(versionName: String?): String? =
    versionName?.trim()?.takeIf { it.isNotEmpty() }?.let(Texts.current.settings::version)

/**
 * The installed app's versionName, read from PackageManager rather than BuildConfig or a hard-coded text,
 * so it always matches the build that is on the phone (versionName is set in app/build.gradle.kts).
 * Null when it can't be read.
 */
internal fun installedVersionName(context: Context): String? =
    try {
        val packageManager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(context.packageName, 0)
        }
        info.versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    } catch (e: RuntimeException) {
        // The package manager is unavailable (system dying, layout previews): just no version line.
        null
    }
