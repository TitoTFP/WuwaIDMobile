package com.titotfp.wuwaid.storage

import android.content.Intent
import android.content.pm.PackageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28])
class AccessSetupTest {
  @Test fun bootstrapPreservesMethodWithoutInventingAnInstalledGame() {
    val context = RuntimeEnvironment.getApplication()
    val facts = bootstrapDevice(context, "root", null)
    assertEquals("root", facts["accessMethod"])
    assertEquals(
      mapOf("installed" to false, "version" to "", "packageName" to GamePackages.GLOBAL),
      facts["game"]
    )
  }

  @Test fun bootstrapUsesInstalledMetadataAndHonorsTheSessionPackage() {
    val context = RuntimeEnvironment.getApplication()
    val pm = shadowOf(context.packageManager)
    pm.installPackage(PackageInfo().apply {
      packageName = GamePackages.SAMSUNG
      versionName = "3.7.2"
    })
    assertEquals(
      mapOf("installed" to true, "version" to "3.7.2", "packageName" to GamePackages.SAMSUNG),
      bootstrapDevice(context, "shizuku", null)["game"]
    )
    pm.installPackage(PackageInfo().apply {
      packageName = GamePackages.GLOBAL
      versionName = "3.7.1"
    })
    assertEquals(
      mapOf("installed" to true, "version" to "3.7.1", "packageName" to GamePackages.GLOBAL),
      bootstrapDevice(context, "axmanager", null)["game"]
    )
    val selected = bootstrapDevice(context, "axmanager", GamePackages.SAMSUNG)
    assertEquals("axmanager", selected["accessMethod"])
    assertEquals(
      mapOf("installed" to true, "version" to "3.7.2", "packageName" to GamePackages.SAMSUNG),
      selected["game"]
    )
  }

  @Test fun missingManagerOpensItsOfficialDownloadInsteadOfAnApplicationLaunch() {
    val context = RuntimeEnvironment.getApplication()
    for (url in listOf("https://shizuku.rikka.app/download/", "https://github.com/fahrez182/AxManager/releases")) {
      val intent = managerSetupIntent(context, null, url)
      assertEquals(Intent.ACTION_VIEW, intent.action)
      assertEquals(url, intent.data.toString())
    }
  }

  @Test fun installedManagerOpensItsActivityInsteadOfTheDownloadPage() {
    val context = RuntimeEnvironment.getApplication()
    assertTrue(isPackageInstalled(context, context.packageName))
    assertFalse(isPackageInstalled(context, "com.example.missing.manager"))
    val intent = managerSetupIntent(context, context.packageName, "https://shizuku.rikka.app/download/")
    assertEquals(context.packageName, intent.component?.packageName)
    assertEquals(Intent.ACTION_MAIN, intent.action)
  }
}
