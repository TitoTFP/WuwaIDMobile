package com.titotfp.wuwaid.storage

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Process

internal fun loadStorageAccess(context: Context, preferences: SharedPreferences): String {
  preferences.getString("method", null)?.let { return it }

  @Suppress("DEPRECATION")
  val info = context.packageManager.getPackageInfo(context.packageName, 0)
  // Older installs used Shizuku implicitly without necessarily saving a preference.
  val freshInstall = info.firstInstallTime == info.lastUpdateTime
  val method = if (freshInstall && Build.VERSION.SDK_INT >= 26 && Process.myUid() / 100000 == 0)
    "axmanager" else "shizuku"
  preferences.edit().putString("method", method).apply()
  return method
}
