package com.titotfp.wuwaid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class GamePathsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun choosesHighestReadySemanticResourceVersion() {
        val fixture = fixture()
        fixture.readyVersion("3.9.0")
        fixture.readyVersion("3.10.0")
        File(fixture.resources, "4.0.0").mkdirs()

        assertEquals("3.10.0", fixture.paths.resolveResourceVersion())
    }

    @Test
    fun installsVerifiesMountAndCleansOnlyOwnedOldFiles() {
        val fixture = fixture()
        fixture.readyVersion("3.5.0")
        fixture.readyVersion("3.5.1")
        fixture.officialSignature("3.5.1", "official-signature")
        val externalPatch = fixture.externalPatch("pak-indonesia")
        val old = fixture.paths.paths("3.5.0")
        File(old.directory).mkdirs()
        File(old.pak).writeText("old")
        File(old.mount).apply { parentFile!!.mkdirs(); writeText("old mount") }
        val unrelated = File(fixture.resources, "3.5.0/othermod/keep.pak").apply {
            parentFile!!.mkdirs()
            writeText("keep")
        }
        val release = releaseFor(externalPatch)

        fixture.paths.install(externalPatch.path, release)

        val inspection = fixture.paths.inspect(release)
        assertTrue(inspection.currentHealthy)
        assertTrue(inspection.matchesLatest)
        assertFalse(File(old.pak).exists())
        assertFalse(File(old.mount).exists())
        assertTrue(unrelated.exists())

        val current = fixture.paths.paths("3.5.1")
        val pakSha1 = fixture.engineFiles.sha1(current.pak).uppercase()
        val sigSha1 = fixture.engineFiles.sha1(current.signature).uppercase()
        assertEquals(fixture.paths.mountContent(pakSha1, sigSha1), File(current.mount).readText())
        fixture.assertNoTemporaryArtifacts("3.5.1")
    }

    @Test
    fun hashMismatchPreservesExistingInstallationAndCleansStaging() {
        val fixture = fixture()
        fixture.readyVersion("3.5.1")
        fixture.officialSignature("3.5.1", "new-official-signature")
        val oldMount = fixture.seedHealthyInstall("3.5.1", "old-pak", "old-signature")
        val externalPatch = fixture.externalPatch("new-pak")
        val release = releaseFor(externalPatch, sha256 = "0".repeat(64))

        val error = assertThrows(IllegalStateException::class.java) {
            fixture.paths.install(externalPatch.path, release)
        }

        assertTrue(error.message.orEmpty().contains("SHA-256 berubah"))
        fixture.assertInstalledContent("3.5.1", "old-pak", "old-signature", oldMount)
        fixture.assertNoTemporaryArtifacts("3.5.1")
    }

    @Test
    fun missingOfficialSignaturePreservesExistingInstallation() {
        val fixture = fixture()
        fixture.readyVersion("3.5.1")
        val oldMount = fixture.seedHealthyInstall("3.5.1", "old-pak", "old-signature")
        val externalPatch = fixture.externalPatch("new-pak")

        val error = assertThrows(IllegalStateException::class.java) {
            fixture.paths.install(externalPatch.path, releaseFor(externalPatch))
        }

        assertTrue(error.message.orEmpty().contains("Tidak menemukan file .sig resmi"))
        fixture.assertInstalledContent("3.5.1", "old-pak", "old-signature", oldMount)
        fixture.assertNoTemporaryArtifacts("3.5.1")
    }

    @Test
    fun failedCommitRestoresExistingPakSignatureAndMount() {
        val fixture = fixture(withFaults = true)
        fixture.readyVersion("3.5.1")
        fixture.officialSignature("3.5.1", "new-official-signature")
        val oldMount = fixture.seedHealthyInstall("3.5.1", "old-pak", "old-signature")
        val externalPatch = fixture.externalPatch("new-pak")
        val target = fixture.paths.paths("3.5.1")
        fixture.faults!!.failNextReplaceTo = target.mount

        val error = assertThrows(IllegalStateException::class.java) {
            fixture.paths.install(externalPatch.path, releaseFor(externalPatch))
        }

        assertTrue(error.message.orEmpty().contains("Tidak bisa memasang"))
        fixture.assertInstalledContent("3.5.1", "old-pak", "old-signature", oldMount)
        fixture.assertNoTemporaryArtifacts("3.5.1")
    }

    @Test
    fun failedFirstInstallLeavesNoPartialOwnedArtifacts() {
        val fixture = fixture(withFaults = true)
        fixture.readyVersion("3.5.1")
        fixture.officialSignature("3.5.1", "official-signature")
        val externalPatch = fixture.externalPatch("new-pak")
        val target = fixture.paths.paths("3.5.1")
        fixture.faults!!.failNextReplaceTo = target.mount

        assertThrows(IllegalStateException::class.java) {
            fixture.paths.install(externalPatch.path, releaseFor(externalPatch))
        }

        assertFalse(File(target.pak).exists())
        assertFalse(File(target.signature).exists())
        assertFalse(File(target.mount).exists())
        fixture.assertNoTemporaryArtifacts("3.5.1")
    }

    @Test
    fun blocksVietnamAndHighPriorityCustomMounts() {
        val fixture = fixture()
        fixture.readyVersion("3.5.1")
        val mount = File(fixture.resources, "3.5.1/Mount/wuwaviethoa.txt").apply {
            parentFile!!.mkdirs()
            writeText("::Mount::\nwuwaviethoa/WuWaVH_99_P,99,A,B,,\n::Del::\n")
        }
        val externalPatch = fixture.externalPatch("pak")

        val conflicts = fixture.paths.detectConflicts("3.5.1")
        assertTrue(conflicts.any { it.contains(mount.name) })
        assertThrows(IllegalStateException::class.java) {
            fixture.paths.install(externalPatch.path, releaseFor(externalPatch))
        }
        assertTrue(mount.exists())
    }

    @Test
    fun officialLanguageMountIsIgnoredUnlessItContainsLegacyVietnamPatch() {
        val fixture = fixture()
        fixture.readyVersion("3.5.1")
        val officialMount = File(fixture.resources, "3.5.1/Mount/MountLang_en.txt").apply {
            parentFile!!.mkdirs()
            writeText("::Mount::\nOfficial/English,99,A,B,,\n::Del::\n")
        }

        assertTrue(fixture.paths.detectConflicts("3.5.1").isEmpty())

        officialMount.writeText("::Mount::\nwuwaviethoa/WuWaVH_99_P,99,A,B,,\n::Del::\n")
        assertTrue(
            fixture.paths.detectConflicts("3.5.1")
                .contains("Mount/MountLang_en.txt (WuWaVH lama)"),
        )
    }

    @Test
    fun inspectRequiresMatchingMountAndLatestHash() {
        val fixture = fixture()
        fixture.readyVersion("3.5.1")
        val target = fixture.paths.paths("3.5.1")
        File(target.pak).apply { parentFile!!.mkdirs(); writeText("pak") }
        File(target.signature).writeText("sig")
        File(target.mount).apply { parentFile!!.mkdirs(); writeText("invalid mount") }
        val exactRelease = releaseFor(File(target.pak))

        val invalid = fixture.paths.inspect(exactRelease)
        assertFalse(invalid.currentHealthy)
        assertFalse(invalid.matchesLatest)
        assertTrue(invalid.diagnostics.contains("Mount: tidak cocok"))

        val correctMount = fixture.paths.mountContent(
            fixture.engineFiles.sha1(target.pak).uppercase(),
            fixture.engineFiles.sha1(target.signature).uppercase(),
        )
        File(target.mount).writeText(correctMount)

        val healthyButOld = fixture.paths.inspect(
            exactRelease.copy(sha256 = "f".repeat(64)),
        )
        assertTrue(healthyButOld.currentHealthy)
        assertFalse(healthyButOld.matchesLatest)
    }

    @Test
    fun uninstallRemovesOwnedInstalledStagedAndBackupArtifactsOnly() {
        val fixture = fixture()
        fixture.readyVersion("3.5.1")
        val target = fixture.paths.paths("3.5.1")
        val owned = listOf(
            target.mount,
            target.pak,
            target.signature,
            target.stagedPak,
            "${target.signature}.new",
            "${target.mount}.new",
            "${target.stagedPak}.tmp",
            "${target.signature}.new.tmp",
            "${target.mount}.new.tmp",
            "${target.pak}.bak",
            "${target.signature}.bak",
            "${target.mount}.bak",
        )
        owned.forEach { path ->
            File(path).apply {
                parentFile!!.mkdirs()
                writeText("owned")
            }
        }
        val other = File(fixture.resources, "3.5.1/othermod/keep.pak").apply {
            parentFile!!.mkdirs()
            writeText("keep")
        }

        assertEquals(owned.size, fixture.paths.uninstall())
        assertTrue(owned.none { File(it).exists() })
        assertTrue(other.exists())
    }

    @Test
    fun parsesPriorityWithoutFalsePositiveOnHeaders() {
        assertTrue(GamePaths.isHighPriorityMountLine("wuwa/mod,99,A,B,,"))
        assertTrue(GamePaths.isHighPriorityMountLine("wuwa/mod,1000,A,B,,"))
        assertFalse(GamePaths.isHighPriorityMountLine("::Mount::"))
        assertFalse(GamePaths.isHighPriorityMountLine("wuwa/base,1,A,B,,"))
    }

    private fun fixture(withFaults: Boolean = false): Fixture {
        val root = temporary.newFolder("storage")
        val appRoot = File(root, "app").apply { mkdirs() }
        val gameRoot = File(root, "game").apply { mkdirs() }
        val resources = File(gameRoot, "Resources").apply { mkdirs() }
        val engineFiles = EngineFiles(LocalFileEngine(listOf(appRoot, gameRoot)))
        val faults = if (withFaults) FaultInjectingFiles(engineFiles) else null
        val files: PrivilegedFiles = faults ?: engineFiles
        return Fixture(
            appRoot = appRoot,
            resources = resources,
            engineFiles = engineFiles,
            faults = faults,
            paths = GamePaths(files, resources.path),
        )
    }

    private fun releaseFor(file: File, sha256: String = file.hash("SHA-256")): PatchRelease = PatchRelease(
        tag = "v-test",
        title = "Test",
        publishedAt = "2026-07-18T00:00:00Z",
        notes = "Test",
        assetUrl = "https://example.invalid/${ReleaseParser.PATCH_ASSET}",
        size = file.length(),
        sha256 = sha256,
    )

    private fun File.hash(algorithm: String): String = MessageDigest.getInstance(algorithm)
        .digest(readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class Fixture(
        val appRoot: File,
        val resources: File,
        val engineFiles: EngineFiles,
        val faults: FaultInjectingFiles?,
        val paths: GamePaths,
    ) {
        fun readyVersion(version: String) {
            File(resources, "$version/ResManifest").mkdirs()
        }

        fun officialSignature(version: String, content: String) {
            File(resources, "$version/Resource/Base/official.sig").apply {
                parentFile!!.mkdirs()
                writeText(content)
            }
        }

        fun externalPatch(content: String): File = File(appRoot, "patch/${ReleaseParser.PATCH_ASSET}").apply {
            parentFile!!.mkdirs()
            writeText(content)
        }

        fun seedHealthyInstall(version: String, pak: String, signature: String): String {
            val target = paths.paths(version)
            File(target.pak).apply { parentFile!!.mkdirs(); writeText(pak) }
            File(target.signature).writeText(signature)
            val mount = paths.mountContent(
                engineFiles.sha1(target.pak).uppercase(),
                engineFiles.sha1(target.signature).uppercase(),
            )
            File(target.mount).apply { parentFile!!.mkdirs(); writeText(mount) }
            return mount
        }

        fun assertInstalledContent(version: String, pak: String, signature: String, mount: String) {
            val target = paths.paths(version)
            assertEquals(pak, File(target.pak).readText())
            assertEquals(signature, File(target.signature).readText())
            assertEquals(mount, File(target.mount).readText())
        }

        fun assertNoTemporaryArtifacts(version: String) {
            val target = paths.paths(version)
            val temporaryPaths = listOf(
                target.stagedPak,
                "${target.signature}.new",
                "${target.mount}.new",
                "${target.stagedPak}.tmp",
                "${target.signature}.new.tmp",
                "${target.mount}.new.tmp",
                "${target.pak}.bak",
                "${target.signature}.bak",
                "${target.mount}.bak",
            )
            assertTrue(temporaryPaths.none { File(it).exists() })
        }
    }

    private class FaultInjectingFiles(
        private val delegate: PrivilegedFiles,
    ) : PrivilegedFiles {
        var failNextReplaceTo: String? = null
        private var error = ""

        override fun copyFile(source: String, destination: String): Boolean = delegate.copyFile(source, destination)

        override fun replaceFile(source: String, destination: String): Boolean {
            if (destination == failNextReplaceTo) {
                failNextReplaceTo = null
                error = "kegagalan replace yang disimulasikan"
                return false
            }
            return delegate.replaceFile(source, destination)
        }

        override fun deleteFile(path: String): Boolean = delegate.deleteFile(path)
        override fun exists(path: String): Boolean = delegate.exists(path)
        override fun mkdirs(path: String): Boolean = delegate.mkdirs(path)
        override fun listFiles(path: String): Array<String> = delegate.listFiles(path)
        override fun readText(path: String): String = delegate.readText(path)
        override fun writeTextAtomic(path: String, content: String): Boolean = delegate.writeTextAtomic(path, content)
        override fun sha1(path: String): String = delegate.sha1(path)
        override fun sha256(path: String): String = delegate.sha256(path)
        override fun lastError(): String = error.ifBlank(delegate::lastError)
    }

    private class EngineFiles(private val engine: LocalFileEngine) : PrivilegedFiles {
        private var error = ""

        override fun copyFile(source: String, destination: String) = action { engine.copyFile(source, destination) }
        override fun replaceFile(source: String, destination: String) = action { engine.replaceFile(source, destination) }
        override fun deleteFile(path: String) = action { engine.deleteFile(path) }
        override fun exists(path: String) = action { engine.exists(path) }
        override fun mkdirs(path: String) = action { engine.mkdirs(path) }
        override fun listFiles(path: String) = value(emptyArray()) { engine.listFiles(path) }
        override fun readText(path: String) = value("") { engine.readText(path) }
        override fun writeTextAtomic(path: String, content: String) = action { engine.writeTextAtomic(path, content) }
        override fun sha1(path: String) = value("") { engine.hash(path, "SHA-1") }
        override fun sha256(path: String) = value("") { engine.hash(path, "SHA-256") }
        override fun lastError() = error

        private fun action(block: () -> Boolean) = try {
            block()
        } catch (throwable: Throwable) {
            error = throwable.message.orEmpty()
            false
        }

        private fun <T> value(fallback: T, block: () -> T) = try {
            block()
        } catch (throwable: Throwable) {
            error = throwable.message.orEmpty()
            fallback
        }
    }
}
