package com.titotfp.wuwaid.storage

import android.app.Application
import android.os.Build
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 25, 26, 28])
class StorageAccessTest {
  private val context: Application get() = RuntimeEnvironment.getApplication()
  private val preferences get() = context.getSharedPreferences("storage-access", 0)

  @Before fun freshInstall() {
    preferences.edit().clear().commit()
    ShadowProcess.setUid(10123)
    setInstallTimes(1000, 1000)
  }

  @After fun resetIdentity() { ShadowProcess.reset() }

  @Suppress("DEPRECATION")
  private fun setInstallTimes(first: Long, last: Long) {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    info.firstInstallTime = first
    info.lastUpdateTime = last
    shadowOf(context.packageManager).installPackage(info)
  }

  @Test fun newInstallChoosesAndPersistsAccessWithoutAManagerOrPermissionRequest() {
    val expected = if (Build.VERSION.SDK_INT >= 26) "axmanager" else "shizuku"
    assertEquals(expected, loadStorageAccess(context, preferences))
    assertEquals(expected, preferences.getString("method", null))
    setInstallTimes(1000, 2000)
    assertEquals(expected, loadStorageAccess(context, preferences))
  }

  @Test fun secondaryUserStartsWithShizukuAndDoesNotSwitchWhenIdentityChanges() {
    ShadowProcess.setUid(1010123)
    assertEquals("shizuku", loadStorageAccess(context, preferences))
    ShadowProcess.setUid(10123)
    assertEquals("shizuku", loadStorageAccess(context, preferences))
  }

  @Test fun upgradePreservesTheImplicitLegacyShizukuChoice() {
    setInstallTimes(1000, 2000)
    assertEquals("shizuku", loadStorageAccess(context, preferences))
    assertEquals("shizuku", preferences.getString("method", null))
  }

  @Test fun savedChoicesSurviveUpgradeAndUnsupportedEnvironments() {
    setInstallTimes(1000, 2000)
    ShadowProcess.setUid(1010123)
    for (method in listOf("root", "shizuku", "axmanager")) {
      preferences.edit().putString("method", method).commit()
      assertEquals(method, loadStorageAccess(context, preferences))
      assertEquals(method, preferences.getString("method", null))
    }
  }
}
