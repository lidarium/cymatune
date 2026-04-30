package com.cymatune.security

import android.os.Build
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object RootDetectionUtil {

    fun isDeviceRooted(): Boolean {
        return checkBuildTags() || checkSuperUserApk() || checkSuBinary()
    }

    private fun checkBuildTags(): Boolean {
        val buildTags = Build.TAGS
        return buildTags != null && buildTags.contains("test-keys")
    }

    private fun checkSuperUserApk(): Boolean {
        val knownRootAppsPackages = arrayOf(
            "com.noshufou.android.su",
            "com.noshufou.android.su.elite",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.thirdparty.superuser",
            "com.yellowes.su",
            "com.topjohnwu.magisk"
        )
        for (packageName in knownRootAppsPackages) {
            try {
                if (File("/data/app/$packageName-1.apk").exists() || 
                    File("/data/app/$packageName-2.apk").exists() ||
                    File("/data/dr/app/$packageName.apk").exists() || 
                    File("/system/app/$packageName.apk").exists() ||
                    File("/system/priv-app/$packageName.apk").exists()) {
                    return true
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        return false
    }

    private fun checkSuBinary(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        )
        for (path in paths) {
            if (File(path).exists()) return true
        }
        return false
    }
}
