package com.titotfp.wuwaid.diagnostics

import android.app.Activity
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import app.tauri.plugin.Invoke
import app.tauri.plugin.PluginHandle
import app.tauri.plugin.PluginManager
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsonorg.JsonOrgModule
import com.titotfp.wuwaid.R
import com.titotfp.wuwaid.update.HostLinux
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
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

class DiagnosticTestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        super.onCreate(savedInstanceState)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [HostLinux::class])
class DiagnosticsPluginTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var activity: DiagnosticTestActivity
    private lateinit var plugin: DiagnosticsPlugin
    private lateinit var handle: PluginHandle
    private val mapper = ObjectMapper().registerModule(JsonOrgModule())
    private val responses = LinkedBlockingQueue<Pair<Long, JSONObject>>()
    private var sequence = 0L
    private val content = """{"schemaVersion":1,"privacy":{"customUid":"redacted"},"message":"Indonesia ✨"}"""
    private val destination = Uri.parse("content://diagnostic-documents/report.json")

    @Before fun prepare() {
        val controller = Robolectric.buildActivity(DiagnosticTestActivity::class.java).create()
        activity = controller.get()
        PluginManager.onCreate(activity)
        controller.start().resume().visible()
        val authority = "${activity.packageName}.fileprovider"
        val info = ProviderInfo().apply {
            name = FileProvider::class.java.name
            packageName = activity.application.packageName
            applicationInfo = activity.application.applicationInfo
            this.authority = authority
            exported = false
            grantUriPermissions = true
            metaData = Bundle().apply { putInt("android.support.FILE_PROVIDER_PATHS", R.xml.file_paths) }
        }
        Shadows.shadowOf(activity.packageManager).addOrUpdateProvider(info)
        val provider = FileProvider().apply { attachInfo(activity, info) }
        ShadowContentResolver.registerProviderInternal(authority, provider)
        plugin = DiagnosticsPlugin(activity)
        handle = PluginHandle(PluginManager, "DiagnosticsPlugin", plugin, "{}", mapper)
    }

    @After fun close() {
        if (::plugin.isInitialized) plugin.onDestroy(activity)
        if (::activity.isInitialized) PluginManager.onDestroy(activity)
    }

    private fun request(action: String, payload: String = content): Long {
        val id = ++sequence
        handle.invoke(Invoke(id, "exportReport", id * 2, id * 2 + 1,
            { callback, json -> responses.add(callback to JSONObject(json)) },
            JSONObject().put("action", action).put("content", payload).toString(), mapper))
        return id
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (System.nanoTime() > deadline) fail("Diagnostic transition did not complete")
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5))
        }
    }

    private fun response(id: Long, rejected: Boolean = false): JSONObject {
        await { responses.isNotEmpty() }
        val (callback, json) = responses.remove()
        assertEquals(id * 2 + if (rejected) 1 else 0, callback)
        return json
    }

    private fun picker(): ShadowActivity.IntentForResult {
        var result: ShadowActivity.IntentForResult? = null
        await { result = Shadows.shadowOf(activity).nextStartedActivityForResult; result != null }
        return result!!
    }

    private fun returned(picker: ShadowActivity.IntentForResult, code: Int, uri: Uri? = destination) {
        assertTrue(activity.activityResultRegistry.dispatchResult(picker.requestCode, code, Intent().setData(uri)))
    }

    private fun share(id: Long): Pair<JSONObject, Intent> {
        val response = response(id)
        val chooser = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        return response to chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
    }

    @Test fun successRequiresACompletedUtf8WriteAndCloseAfterThePickerResult() {
        val file = temporary.newFile()
        var closed = false
        val stream = object : FilterOutputStream(file.outputStream()) {
            override fun close() { super.close(); closed = true }
        }
        Shadows.shadowOf(activity.contentResolver).registerOutputStream(destination, stream)
        val id = request("save")
        val picker = picker()
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker.intent.action)
        assertEquals("application/json", picker.intent.type)
        assertTrue(picker.intent.categories.contains(Intent.CATEGORY_OPENABLE))
        assertEquals(0L, file.length())
        assertTrue(responses.isEmpty())
        returned(picker, Activity.RESULT_OK)
        assertEquals("saved", response(id).getString("status"))
        assertTrue(closed)
        assertArrayEquals(content.toByteArray(Charsets.UTF_8), file.readBytes())
    }

    @Test @Config(sdk = [24])
    fun cancellationLeavesTheDestinationUntouchedAndAllowsRetryOnMinimumSdk() {
        val file = temporary.newFile().apply { writeText("existing user document") }
        val id = request("save")
        returned(picker(), Activity.RESULT_CANCELED)
        assertEquals("cancelled", response(id).getString("status"))
        assertEquals("existing user document", file.readText())
        val retry = request("save")
        returned(picker(), Activity.RESULT_CANCELED)
        assertEquals("cancelled", response(retry).getString("status"))
    }

    @Test fun writeFailureNeverReportsSavedAndReleasesTheLock() {
        var closed = false
        val stream = object : OutputStream() {
            override fun write(value: Int) { throw IOException("provider write failed") }
            override fun close() { closed = true }
        }
        Shadows.shadowOf(activity.contentResolver).registerOutputStream(destination, stream)
        val id = request("save")
        returned(picker(), Activity.RESULT_OK)
        assertEquals("failed", response(id).getString("status"))
        assertTrue(closed)
        val retry = request("save")
        returned(picker(), Activity.RESULT_CANCELED)
        assertEquals("cancelled", response(retry).getString("status"))
    }

    @Test fun closeFailureIsFailureEvenAfterAllBytesWereWritten() {
        val file = temporary.newFile()
        val stream = object : FilterOutputStream(file.outputStream()) {
            override fun close() { super.close(); throw IOException("provider close failed") }
        }
        Shadows.shadowOf(activity.contentResolver).registerOutputStream(destination, stream)
        val id = request("save")
        returned(picker(), Activity.RESULT_OK)
        assertEquals("failed", response(id).getString("status"))
        assertArrayEquals(content.toByteArray(Charsets.UTF_8), file.readBytes())
    }

    @Test fun missingOrNonContentDestinationsAndFailedPickerCannotClaimSuccess() {
        for ((code, uri) in listOf(Activity.RESULT_OK to null, Activity.RESULT_OK to Uri.parse("file:///private.json"), 42 to destination)) {
            val id = request("save")
            returned(picker(), code, uri)
            assertEquals("failed", response(id).getString("status"))
        }
    }

    @Test fun pendingPickerRejectsOtherExportsWithoutReplacingItsSnapshot() {
        val first = request("save")
        val picker = picker()
        response(request("share", "different report"), rejected = true)
        assertTrue(responses.isEmpty())
        returned(picker, Activity.RESULT_CANCELED)
        assertEquals("cancelled", response(first).getString("status"))
    }

    @Test fun sharingGrantsOnlyReadAccessToImmutableReportUrisWithoutClaimingDelivery() {
        val (firstResult, firstIntent) = share(request("share"))
        assertEquals("share_opened", firstResult.getString("status"))
        assertEquals(Intent.ACTION_SEND, firstIntent.action)
        assertEquals("application/json", firstIntent.type)
        assertTrue(firstIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, firstIntent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val firstUri = firstIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", firstUri.scheme)
        assertEquals(firstUri, firstIntent.clipData!!.getItemAt(0).uri)
        val secondContent = """{"schemaVersion":1,"message":"second"}"""
        val (_, secondIntent) = share(request("share", secondContent))
        val secondUri = secondIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertNotEquals(firstUri, secondUri)
        assertArrayEquals(content.toByteArray(), activity.contentResolver.openInputStream(firstUri)!!.use { it.readBytes() })
        assertArrayEquals(secondContent.toByteArray(), activity.contentResolver.openInputStream(secondUri)!!.use { it.readBytes() })
        val privateFile = File(activity.cacheDir, "private-diagnostic-fixture.txt").apply { writeText("not shared") }
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", privateFile)
        }
    }

    @Test fun destroyRejectsPendingPickerOnceAndIgnoresItsLateResult() {
        val id = request("save")
        val picker = picker()
        plugin.onDestroy(activity)
        response(id, rejected = true)
        returned(picker, Activity.RESULT_OK)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull(responses.poll(50, TimeUnit.MILLISECONDS))
    }

    @Test fun failedApplicationMetadataDoesNotDiscardDeviceMetadata() {
        val packageInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
        val manager = Shadows.shadowOf(activity.packageManager)
        manager.removePackage(activity.packageName)
        try {
            val id = ++sequence
            handle.invoke(Invoke(id, "environment", id * 2, id * 2 + 1,
                { callback, json -> responses.add(callback to JSONObject(json)) }, "{}", mapper))
            val environment = response(id)
            assertEquals("error", environment.getJSONObject("application").getString("status"))
            assertTrue(environment.getJSONObject("application").isNull("data"))
            assertEquals("ok", environment.getJSONObject("device").getString("status"))
            assertEquals(28, environment.getJSONObject("device").getJSONObject("data").getInt("sdk"))
        } finally {
            manager.installPackage(packageInfo)
        }
    }
}
