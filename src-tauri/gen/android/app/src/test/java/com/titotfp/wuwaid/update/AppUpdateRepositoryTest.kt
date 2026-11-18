package com.titotfp.wuwaid.update

import com.android.apksig.ApkVerifier
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [HostApkFileDataSource::class, HostSigningInfo::class, HostLinux::class])
class AppUpdateRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: UpdateFixture
    private lateinit var activity: UpdateTestActivity
    private lateinit var repository: AppUpdateRepository

    @Before fun prepare() {
        fixture = UpdateFixture(temporary.newFolder())
        activity = Robolectric.buildActivity(UpdateTestActivity::class.java).setup().get()
        fixture.installBaseline(activity)
        File(activity.cacheDir, "app-updates").deleteRecursively()
        repository = fixture.repository(activity)
    }

    @After fun close() { fixture.close() }

    private fun selected(apk: File = fixture.apk(), release: JSONObject = fixture.release(apk)): File {
        fixture.enqueueRelease(release)
        assertEquals("0.4.1", repository.check().getString("availableVersion"))
        return apk
    }

    private fun ready(): File {
        val apk = selected()
        fixture.enqueueApk(apk)
        assertTrue(repository.download { _, _ -> }.getBoolean("ready"))
        return apk
    }

    @Test fun downloadsVerifiesAndRestoresARealSignedArm64Apk() {
        val source = selected()
        fixture.enqueueApk(source)
        val progress = mutableListOf<Int>()
        val downloaded = repository.download { percent, _ -> progress += percent }
        assertTrue(downloaded.getBoolean("ready"))
        assertEquals(100, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
        assertArrayEquals(source.readBytes(), repository.verifyReady().readBytes())
        val restored = fixture.repository(activity)
        assertTrue(restored.snapshot().getBoolean("ready"))
        assertArrayEquals(source.readBytes(), restored.verifyReady().readBytes())
    }

    @Test fun rejectsTamperedApkEvenWhenPublishedChecksumMatchesTamperedBytes() {
        val apk = fixture.tampered(fixture.apk())
        assertFalse("Fixture must have a cryptographically broken signature",
            ApkVerifier.Builder(apk).build().verify().isVerified)
        selected(apk)
        fixture.enqueueApk(apk)
        assertThrows(Exception::class.java) { repository.download { _, _ -> } }
        assertFalse(repository.snapshot().getBoolean("ready"))
        assertFalse(repository.apk.exists())
    }

    @Test fun rejectsIncorrectPackageVersionCodeVersionNameSignerSdkAndAbi() {
        val foreign = HeldCertificate.Builder().rsa2048().commonName("foreign signer").build()
        val bad = listOf(
            fixture.apk(packageName = "com.example.unrelated"),
            fixture.apk(version = "0.4.2"),
            fixture.apk(code = 8),
            fixture.apk(code = 7),
            fixture.apk(signers = listOf(foreign)),
            fixture.apk(signers = emptyList()),
            fixture.apk(signers = listOf(fixture.key, foreign)),
            fixture.apk(minSdk = 35),
            fixture.apk(abi = "x86_64"),
            fixture.apk(abi = null),
        )
        for (apk in bad) {
            selected(apk)
            fixture.enqueueApk(apk)
            assertThrows("Must reject ${apk.parentFile.name}/${apk.name}", Exception::class.java) {
                repository.download { _, _ -> }
            }
            assertFalse(repository.snapshot().getBoolean("ready"))
            assertFalse(File(repository.apk.parentFile, "update.part.apk").exists())
        }
    }

    @Test fun rejectsWrongDigestAndBothDownloadSizeBoundaries() {
        val apk = fixture.apk()
        for (release in listOf(
            fixture.release(apk, digest = "sha256:${"0".repeat(64)}"),
            fixture.release(apk, size = apk.length() - 1),
            fixture.release(apk, size = apk.length() + 1),
        )) {
            selected(apk, release)
            fixture.enqueueApk(apk)
            assertThrows(Exception::class.java) { repository.download { _, _ -> } }
            assertFalse(repository.snapshot().getBoolean("ready"))
            assertFalse(repository.apk.exists())
            assertFalse(File(repository.apk.parentFile, "update.part.apk").exists())
        }
    }

    @Test fun interruptedBodyDoesNotPublishAPartialAndAllowsAnotherDownload() {
        val apk = selected()
        fixture.server.enqueue(MockResponse().setBody(okio.Buffer().write(apk.readBytes()))
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        assertThrows(Exception::class.java) { repository.download { _, _ -> } }
        assertFalse(repository.snapshot().getBoolean("ready"))
        assertFalse(repository.apk.exists())
        fixture.enqueueApk(apk)
        assertTrue(repository.download { _, _ -> }.getBoolean("ready"))
        assertArrayEquals(apk.readBytes(), repository.verifyReady().readBytes())
    }

    @Test fun corruptedCachedApkIsInvalidatedBeforeInstallAndCanBeDownloadedAgain() {
        val source = ready()
        repository.apk.setWritable(true)
        repository.apk.writeBytes(fixture.tampered(source).readBytes())
        assertThrows(Exception::class.java) { repository.verifyReady() }
        assertFalse(repository.snapshot().getBoolean("ready"))
        fixture.enqueueApk(source)
        assertTrue(repository.download { _, _ -> }.getBoolean("ready"))
    }

    @Test fun corruptRestoredMetadataIsRemovedWithoutDeletingUnrelatedFiles() {
        ready()
        val sibling = File(activity.cacheDir, "unrelated-cache").apply { writeText("keep") }
        File(repository.apk.parentFile, "candidate.json").writeText("{invalid")
        val restored = fixture.repository(activity)
        assertThrows(Exception::class.java) { restored.snapshot() }
        assertFalse(restored.apk.exists())
        assertEquals("keep", sibling.readText())
        assertFalse(restored.snapshot().getBoolean("ready"))
    }

    @Test fun installedUpgradeDiscardsTheCachedCandidate() {
        ready()
        fixture.installBaseline(activity, version = "0.4.1", code = 9)
        val status = fixture.repository(activity).snapshot()
        assertFalse(status.getBoolean("ready"))
        assertTrue(status.isNull("availableVersion"))
        assertFalse(repository.apk.exists())
    }

    @Test fun numericVersionOrderingHandlesMultiDigitComponentsAndClearsStaleCache() {
        val apk = fixture.apk(version = "0.4.10")
        fixture.installBaseline(activity, version = "0.4.9")
        fixture.enqueueRelease(fixture.release(apk, version = "0.4.10"))
        assertEquals("0.4.10", repository.check().getString("availableVersion"))
        fixture.enqueueRelease(fixture.release(apk, version = "0.4.9"))
        val current = repository.check()
        assertTrue(current.isNull("availableVersion"))
        assertFalse(current.getBoolean("ready"))
    }

    @Test fun restartRemovesInterruptedDownloadsAndOrphanApkWithoutTouchingForeignCache() {
        val directory = repository.apk.parentFile!!
        directory.mkdirs()
        val partial = File(directory, "update.part.apk").apply { writeBytes(ByteArray(65536)) }
        val metadataPartial = File(directory, "candidate.json.part").apply { writeText("{}") }
        repository.apk.writeBytes(ByteArray(65536))
        val foreign = File(directory, "keep.txt").apply { writeText("keep") }
        assertFalse(fixture.repository(activity).snapshot().getBoolean("ready"))
        assertFalse(partial.exists())
        assertFalse(metadataPartial.exists())
        assertFalse(repository.apk.exists())
        assertEquals("keep", foreign.readText())
    }

    @Test fun selectingNewCandidateEvictsSupersededApkButFailedCheckKeepsReadyCache() {
        ready()
        fixture.server.enqueue(MockResponse().setResponseCode(500))
        assertThrows(Exception::class.java) { repository.check() }
        assertTrue(repository.verifyReady().isFile)
        val newer = fixture.apk(version = "0.4.2", code = 10)
        fixture.enqueueRelease(fixture.release(newer, version = "0.4.2"))
        val selected = repository.check()
        assertEquals("0.4.2", selected.getString("availableVersion"))
        assertFalse(selected.getBoolean("ready"))
        assertFalse(repository.apk.exists())
        assertFalse(File(repository.apk.parentFile, "candidate.json").exists())
    }

    @Test fun downloadingReadyCandidateReverifiesCacheWithoutAnotherTransferOrPartial() {
        val source = ready()
        val requests = fixture.server.requestCount
        val progress = mutableListOf<Int>()
        assertTrue(repository.download { percent, _ ->
            progress += percent
            assertFalse(File(repository.apk.parentFile, "update.part.apk").exists())
        }.getBoolean("ready"))
        assertEquals(requests, fixture.server.requestCount)
        assertEquals(100, progress.last())
        assertArrayEquals(source.readBytes(), repository.verifyReady().readBytes())
    }

    @Test fun readyCacheSurvivesRestartCleanupButTamperedReuseCannotSkipVerification() {
        val source = ready()
        val partial = File(repository.apk.parentFile, "update.part.apk").apply { writeBytes(ByteArray(65536)) }
        val metadataPartial = File(repository.apk.parentFile, "candidate.json.part").apply { writeText("{}") }
        val restored = fixture.repository(activity)
        assertTrue(restored.snapshot().getBoolean("ready"))
        assertFalse(partial.exists())
        assertFalse(metadataPartial.exists())
        assertArrayEquals(source.readBytes(), restored.verifyReady().readBytes())
        restored.apk.setWritable(true)
        restored.apk.writeBytes(fixture.tampered(source).readBytes())
        val requests = fixture.server.requestCount
        assertThrows(Exception::class.java) { restored.download { _, _ -> } }
        assertEquals(requests, fixture.server.requestCount)
        assertFalse(restored.apk.exists())
        assertFalse(restored.snapshot().getBoolean("ready"))
    }

    @Test fun missingStableReleaseRetainsAnAlreadyVerifiedOfflineCandidate() {
        ready()
        fixture.server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(repository.check().getBoolean("ready"))
        assertTrue(repository.verifyReady().isFile)
        assertTrue(repository.snapshot().getBoolean("checked"))
    }

    @Test fun missingFirstReleaseDoesNotOfferAnUpdate() {
        fixture.server.enqueue(MockResponse().setResponseCode(404))
        val status = repository.check()
        assertTrue(status.isNull("availableVersion"))
        assertFalse(status.getBoolean("ready"))
        assertTrue(status.getBoolean("checked"))
    }

    @Test fun unsupportedDebugPackageAbiOrInstalledSignerCannotFetchOrInstall() {
        val foreign = HeldCertificate.Builder().rsa2048().commonName("foreign installed key").build()
        fixture.installBaseline(activity, signer = foreign)
        assertFalse(repository.snapshot().getBoolean("supported"))
        assertThrows(Exception::class.java) { repository.check() }
        fixture.installBaseline(activity)
        ShadowBuild.setSupportedAbis(arrayOf("x86_64"))
        assertFalse(repository.snapshot().getBoolean("supported"))
        assertThrows(Exception::class.java) { repository.verifyReady() }
        ShadowBuild.setSupportedAbis(arrayOf("arm64-v8a"))
        activity.installedPackage = "$UPDATE_PACKAGE.debug"
        assertFalse(repository.snapshot().getBoolean("supported"))
        assertThrows(Exception::class.java) { repository.check() }
        assertEquals(0, fixture.server.requestCount)
    }

    @Test fun rejectsDraftPrereleaseMalformedTagAmbiguousAssetsAndUntrustedAssetUrl() {
        val apk = fixture.apk()
        val cases = listOf(
            fixture.release(apk).put("draft", true),
            fixture.release(apk).put("prerelease", true),
            fixture.release(apk).put("tag_name", "v0.4.1-beta"),
            fixture.release(apk).put("assets", JSONArray()),
            fixture.release(apk).apply { getJSONArray("assets").put(getJSONArray("assets").getJSONObject(0)) },
            fixture.release(apk).apply { getJSONArray("assets").getJSONObject(0).put("state", "new") },
            fixture.release(apk).apply { getJSONArray("assets").getJSONObject(0)
                .put("browser_download_url", "https://evil.example/update.apk") },
            fixture.release(apk, size = 0),
            fixture.release(apk, size = 256L * 1024 * 1024 + 1),
        )
        for (release in cases) {
            fixture.enqueueRelease(release)
            assertThrows(Exception::class.java) { repository.check() }
            assertTrue(repository.snapshot().isNull("availableVersion"))
        }
    }

    @Test fun checksumFallbackAcceptsGnuTextAndBinaryEntriesButRejectsAmbiguity() {
        val apk = fixture.apk()
        val name = "WuwaID-Mobile-v0.4.1.apk"
        for (marker in listOf(" ", "*")) {
            val release = fixture.release(apk, digest = null)
            release.getJSONArray("assets").put(JSONObject().put("name", "SHA256sums.txt").put("state", "uploaded")
                .put("browser_download_url", "$REPOSITORY_URL/v0.4.1/SHA256sums.txt"))
            fixture.enqueueRelease(release)
            fixture.server.enqueue(MockResponse().setBody("${UpdateFixture.sha256(apk.readBytes()).uppercase()} $marker$name\n"))
            assertEquals("0.4.1", repository.check().getString("availableVersion"))
            fixture.enqueueApk(apk)
            assertTrue(repository.download { _, _ -> }.getBoolean("ready"))
            assertArrayEquals(apk.readBytes(), repository.verifyReady().readBytes())
        }
        val release = fixture.release(apk, digest = null)
        release.getJSONArray("assets").put(JSONObject().put("name", "SHA256sums.txt").put("state", "uploaded")
            .put("browser_download_url", "$REPOSITORY_URL/v0.4.1/SHA256sums.txt"))
        fixture.enqueueRelease(release)
        val entry = "${UpdateFixture.sha256(apk.readBytes())}  $name\n"
        fixture.server.enqueue(MockResponse().setBody(entry + entry))
        assertThrows(Exception::class.java) { repository.check() }
        assertTrue(repository.snapshot().getBoolean("ready"))
    }

    @Test fun allowsTrustedHttpsRedirectButRejectsDowngradeForeignHostsCredentialsAndLoops() {
        val apk = fixture.apk()
        fixture.server.enqueue(MockResponse().setResponseCode(302)
            .setHeader("Location", "https://objects.githubusercontent.com/release.json"))
        fixture.enqueueRelease(fixture.release(apk))
        assertEquals("0.4.1", repository.check().getString("availableVersion"))
        for (url in listOf("http://api.github.com/releases", "https://evil.example/releases",
            "https://api.github.com:444/releases", "https://user@api.github.com/releases",
            "https://api.github.com/releases#fragment")) {
            fixture.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", url))
            assertThrows(Exception::class.java) { repository.check() }
        }
        repeat(6) { fixture.server.enqueue(MockResponse().setResponseCode(302)
            .setHeader("Location", "https://api.github.com/loop")) }
        assertThrows(Exception::class.java) { repository.check() }
        assertEquals(13, fixture.server.requestCount)
    }

    @Test fun httpFailuresAndOversizeMetadataDoNotDestroyAReadyCandidate() {
        ready()
        for (code in listOf(403, 429, 500)) {
            fixture.server.enqueue(MockResponse().setResponseCode(code))
            assertThrows(Exception::class.java) { repository.check() }
            assertTrue(repository.snapshot().getBoolean("ready"))
        }
        fixture.server.enqueue(MockResponse().setBody(" ".repeat(4 * 1024 * 1024 + 1)))
        assertThrows(Exception::class.java) { repository.check() }
        assertTrue(repository.snapshot().getBoolean("ready"))
    }

    @Test @Config(sdk = [24], shadows = [HostLegacyArchiveParser::class]) fun supportsLegacyPackageSignatureAndVersionApis() {
        val source = ready()
        assertArrayEquals(source.readBytes(), repository.verifyReady().readBytes())
    }

    @Test @Config(sdk = [36]) fun supportsCurrentAndroidPackageMetadataApis() {
        val source = ready()
        assertArrayEquals(source.readBytes(), repository.verifyReady().readBytes())
    }
}
