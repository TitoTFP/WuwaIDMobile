package com.titotfp.wuwaid.storage

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

@Suppress("DEPRECATION")
internal fun isPackageInstalled(context: Context, packageName: String): Boolean = try {
  context.packageManager.getPackageInfo(packageName, 0)
  true
} catch (_: PackageManager.NameNotFoundException) {
  false
}

internal fun managerSetupIntent(context: Context, packageName: String?, downloadUrl: String): Intent =
  if (packageName == null) {
    Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
  } else {
    context.packageManager.getLaunchIntentForPackage(packageName)
      ?: throw IllegalStateException("Aplikasi pengelola tidak dapat dibuka. Periksa apakah aplikasinya dinonaktifkan.")
  }
