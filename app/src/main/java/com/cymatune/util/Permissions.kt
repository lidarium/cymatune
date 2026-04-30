package com.cymatune.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

fun Context.isReadLogsPermissionGranted(): Boolean {
  return ContextCompat.checkSelfPermission(
    this,
    Manifest.permission.READ_LOGS
  ) == PackageManager.PERMISSION_GRANTED
} 