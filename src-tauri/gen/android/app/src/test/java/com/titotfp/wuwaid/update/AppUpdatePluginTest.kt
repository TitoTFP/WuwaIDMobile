package com.titotfp.wuwaid.update

import android.app.Activity
import android.content.Intent
import android.content.pm.ProviderInfo
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import app.tauri.plugin.Invoke
import app.tauri.plugin.PluginHandle
import app.tauri.plugin.PluginManager
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsonorg.JsonOrgModule
import com.titotfp.wuwaid.R
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [HostApkFileDataSource::class, HostSigningInfo::class, HostLinux::class])
class AppUpdatePluginTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: UpdateFixture
    private lateinit var activity: UpdateTestActivity
    private lateinit var repository: AppUpdateRepository
    private lateinit var plugin: AppUpdatePlugin
    private lateinit var handle: PluginHandle
    private lateinit var source: File
    private val responses = LinkedBlockingQueue<Pair<Long, JSONObject>>()
    private val mapper = ObjectMapper().registerModule(JsonOrgModule())
    private var sequence = 0L

    @Before fun prepare() {
        fixture = UpdateFixture(temporary.newFolder())
        val controller = Robolectric.buildActivity(UpdateTestActivity::class.java).create()
        activity = controller.get()
        PluginManager.onCreate(activity)
        controller.start().resume().visible()
        fixture.installBaseline(activity)
        File(activity.cacheDir, "app-updates").deleteRecursively()
        repository = fixture.repository(activity)
        source = fixture.apk()
        fixture.enqueueRelease(fixture.release(source))
        repository.check()
        fixture.enqueueApk(source)
        assertTrue(repository.download { _, _ -> }.getBoolean("ready"))

        val authority = "$UPDATE_PACKAGE.fileprovider"
        val providerInfo = ProviderInfo().apply {
            name = FileProvider::class.java.name
            // Resources belong to the actual test app; only the release identity is modeled.
            packageName = activity.application.packageName
            applicationInfo = activity.application.applicationInfo
            this.authority = authority
            exported = false
            grantUriPermissions = true
            metaData = Bundle().apply { putInt("android.support.FILE_PROVIDER_PATHS", R.xml.file_paths) }
        }
        Shadows.shadowOf(activity.packageManager).addOrUpdateProvider(providerInfo)
        val provider = FileProvider().apply { attachInfo(activity, providerInfo) }
        ShadowContentResolver.registerProviderInternal(authority, provider)
        plugin = AppUpdatePlugin(activity, repository)
        handle = PluginHandle(PluginManager, "AppUpdatePlugin", plugin, "{}", mapper)
        Shadows.shadowOf(activity.packageManager).setCanRequestPackageInstalls(false)
    }

    @After fun close() {
        if (::plugin.isInitialized) plugin.onDestroy(activity)
        if (::activity.isInitialized) PluginManager.onDestroy(activity)
        fixture.close()
    }

    private fun request(action: String): Long {
        val id = ++sequence
        handle.invoke(Invoke(id, "update", id * 2, id * 2 + 1,
            { callback, json -> responses.add(callback to JSONObject(json)) },
            JSONObject().put("action", action).toString(), mapper))
        return id
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (System.nanoTime() > deadline) fail("Updater worker did not complete the expected transition")
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5))
        }
    }

    private fun response(id: Long, rejected: Boolean = false): JSONObject {
        await { responses.isNotEmpty() }
        val (callback, json) = responses.remove()
        assertEquals(id * 2 + if (rejected) 1 else 0, callback)
        return json
    }

    private fun launch(): ShadowActivity.IntentForResult {
        var launched: ShadowActivity.IntentForResult? = null
        await {
            responses.peek()?.let { fail("Unexpected updater response before activity launch: $it") }
            launched = Shadows.shadowOf(activity).nextStartedActivityForResult
            launched != null
        }
        return launched!!
    }

    private fun result(launch: ShadowActivity.IntentForResult, code: Int) {
        assertTrue(activity.activityResultRegistry.dispatchResult(launch.requestCode, code, Intent()))
    }

    @Test fun deniedPermissionRetainsVerifiedApkAndReleasesTheOperationLock() {
        val id = request("install")
        val settings = launch()
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settings.intent.action)
        assertEquals("package:$UPDATE_PACKAGE", settings.intent.data.toString())
        result(settings, Activity.RESULT_OK) // A result code is not proof that permission was granted.
        response(id, rejected = true)
        assertArrayEquals(source.readBytes(), repository.verifyReady().readBytes())
        val status = response(request("status"))
        assertTrue(status.getBoolean("ready"))
        assertNull(Shadows.shadowOf(activity).nextStartedActivityForResult)
    }

    @Test fun actualGrantOverridesCancelledSettingsResultAndSharesOnlyTheVerifiedApk() {
        val id = request("install")
        val settings = launch()
        Shadows.shadowOf(activity.packageManager).setCanRequestPackageInstalls(true)
        result(settings, Activity.RESULT_CANCELED)
        val installer = launch()
        assertEquals(Intent.ACTION_INSTALL_PACKAGE, installer.intent.action)
        assertEquals("content", installer.intent.data!!.scheme)
        assertEquals("application/vnd.android.package-archive", installer.intent.type)
        assertTrue(installer.intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertArrayEquals(source.readBytes(), activity.contentResolver.openInputStream(installer.intent.data!!)!!.use { it.readBytes() })
        val privateFile = File(activity.cacheDir, "private.txt").apply { writeText("secret") }
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(activity, "$UPDATE_PACKAGE.fileprovider", privateFile)
        }
        result(installer, Activity.RESULT_CANCELED)
        assertTrue(response(id).getBoolean("ready"))
    }

    @Test fun cacheTamperingWhilePermissionScreenIsOpenPreventsLaunchingInstaller() {
        val id = request("install")
        val settings = launch()
        repository.apk.setWritable(true)
        repository.apk.writeBytes(fixture.tampered(source).readBytes())
        Shadows.shadowOf(activity.packageManager).setCanRequestPackageInstalls(true)
        result(settings, Activity.RESULT_OK)
        response(id, rejected = true)
        assertFalse(repository.snapshot().getBoolean("ready"))
        assertNull(Shadows.shadowOf(activity).nextStartedActivityForResult)
    }

    @Test fun cancellationKeepsCachedApkAndAllowsAnotherInstallationAttempt() {
        Shadows.shadowOf(activity.packageManager).setCanRequestPackageInstalls(true)
        val first = request("install")
        result(launch(), Activity.RESULT_CANCELED)
        assertTrue(response(first).getBoolean("ready"))
        val second = request("install")
        result(launch(), Activity.RESULT_CANCELED)
        assertTrue(response(second).getBoolean("ready"))
        assertArrayEquals(source.readBytes(), repository.verifyReady().readBytes())
    }

    @Test fun externalInstallerHoldsTheLockAndItsSuccessResultDoesNotInventAnInstalledVersion() {
        Shadows.shadowOf(activity.packageManager).setCanRequestPackageInstalls(true)
        val install = request("install")
        val installer = launch()
        response(request("check"), rejected = true)
        result(installer, Activity.RESULT_OK)
        val status = response(install)
        assertEquals("0.4.0", status.getString("currentVersion"))
        assertEquals(8L, status.getLong("currentVersionCode"))
        assertTrue(status.getBoolean("ready"))
        assertEquals("0.4.0", response(request("status")).getString("currentVersion"))
    }

    @Test fun installerFailureRetainsApkAndReleasesTheLock() {
        Shadows.shadowOf(activity.packageManager).setCanRequestPackageInstalls(true)
        val id = request("install")
        result(launch(), 42)
        assertTrue(response(id).getBoolean("ready"))
        assertTrue(response(request("status")).getBoolean("ready"))
    }

    @Test fun destroyRejectsPendingRequestOnceAndIgnoresItsLateInstallerResult() {
        Shadows.shadowOf(activity.packageManager).setCanRequestPackageInstalls(true)
        val id = request("install")
        val installer = launch()
        plugin.onDestroy(activity)
        response(id, rejected = true)
        result(installer, Activity.RESULT_CANCELED)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull(responses.poll(100, TimeUnit.MILLISECONDS))
        assertTrue(fixture.repository(activity).snapshot().getBoolean("ready"))
    }

    @Test fun invalidActionDoesNotPoisonTheOperationLock() {
        response(request("unknown"), rejected = true)
        assertTrue(response(request("status")).getBoolean("ready"))
        assertEquals(2, fixture.server.requestCount)
    }

    @Test @Config(sdk = [24], shadows = [HostLegacyArchiveParser::class])
    fun preOreoOpensInstallerWithoutAnUnknownSourcesPermissionScreen() {
        val id = request("install")
        val installer = launch()
        assertEquals(Intent.ACTION_INSTALL_PACKAGE, installer.intent.action)
        result(installer, Activity.RESULT_CANCELED)
        assertTrue(response(id).getBoolean("ready"))
    }
}
