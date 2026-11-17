package com.titotfp.wuwaid.storage

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28])
class AccessSetupTest {
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
