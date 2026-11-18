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

/** Package-manager metadata only: no manager Binder, shell, or game filesystem access. */
@Suppress("DEPRECATION")
internal fun bootstrapDevice(context: Context, method: String, selectedPackage: String?): Map<String, Any> {
  val candidates = selectedPackage?.let { listOf(it) } ?: GamePackages.supported
  val info = candidates.firstNotNullOfOrNull { packageName ->
    try {
      context.packageManager.getPackageInfo(packageName, 0)
    } catch (_: PackageManager.NameNotFoundException) {
      null
    }
  }
  return mapOf(
    "game" to mapOf(
      "installed" to (info != null),
      "version" to (info?.versionName ?: ""),
      "packageName" to (info?.packageName ?: selectedPackage ?: GamePackages.GLOBAL)
    ),
    "accessMethod" to method
  )
}
