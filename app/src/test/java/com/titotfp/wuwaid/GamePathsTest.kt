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
        val externalPatch = File(fixture.appRoot, "patch/${ReleaseParser.PATCH_ASSET}").apply {
            parentFile!!.mkdirs()
            writeText("pak-indonesia")
        }
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
        val pakSha1 = fixture.files.sha1(current.pak).uppercase()
        val sigSha1 = fixture.files.sha1(current.signature).uppercase()
        assertEquals(fixture.paths.mountContent(pakSha1, sigSha1), File(current.mount).readText())
    }

    @Test
    fun blocksVietnamAndHighPriorityCustomMounts() {
        val fixture = fixture()
        fixture.readyVersion("3.5.1")
        val mount = File(fixture.resources, "3.5.1/Mount/wuwaviethoa.txt").apply {
            parentFile!!.mkdirs()
            writeText("::Mount::\nwuwaviethoa/WuWaVH_99_P,99,A,B,,\n::Del::\n")
        }
        val externalPatch = File(fixture.appRoot, "patch/${ReleaseParser.PATCH_ASSET}").apply {
            parentFile!!.mkdirs()
            writeText("pak")
        }

        val conflicts = fixture.paths.detectConflicts("3.5.1")
        assertTrue(conflicts.any { it.contains(mount.name) })
        assertThrows(IllegalStateException::class.java) {
            fixture.paths.install(externalPatch.path, releaseFor(externalPatch))
        }
        assertTrue(mount.exists())
    }

    @Test
    fun uninstallRemovesOnlyWuwaIdArtifacts() {
        val fixture = fixture()
        fixture.readyVersion("3.5.1")
        val target = fixture.paths.paths("3.5.1")
        listOf(target.pak, target.signature, target.mount).forEach {
            File(it).apply { parentFile!!.mkdirs(); writeText("owned") }
        }
        val other = File(fixture.resources, "3.5.1/othermod/keep.pak").apply {
            parentFile!!.mkdirs()
            writeText("keep")
        }

        assertEquals(3, fixture.paths.uninstall())
        assertTrue(other.exists())
    }

    @Test
    fun parsesPriorityWithoutFalsePositiveOnHeaders() {
        assertTrue(GamePaths.isHighPriorityMountLine("wuwa/mod,99,A,B,,"))
        assertTrue(GamePaths.isHighPriorityMountLine("wuwa/mod,1000,A,B,,"))
        assertFalse(GamePaths.isHighPriorityMountLine("::Mount::"))
        assertFalse(GamePaths.isHighPriorityMountLine("wuwa/base,1,A,B,,"))
    }

    private fun fixture(): Fixture {
        val root = temporary.newFolder("storage")
        val appRoot = File(root, "app").apply { mkdirs() }
        val gameRoot = File(root, "game").apply { mkdirs() }
        val resources = File(gameRoot, "Resources").apply { mkdirs() }
        val files = EngineFiles(LocalFileEngine(listOf(appRoot, gameRoot)))
        return Fixture(appRoot, resources, files, GamePaths(files, resources.path))
    }

    private fun releaseFor(file: File): PatchRelease = PatchRelease(
        tag = "v-test",
        title = "Test",
        publishedAt = "2026-07-18T00:00:00Z",
        notes = "Test",
        assetUrl = "https://example.invalid/${ReleaseParser.PATCH_ASSET}",
        size = file.length(),
        sha256 = file.hash("SHA-256"),
    )

    private fun File.hash(algorithm: String): String = MessageDigest.getInstance(algorithm)
        .digest(readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class Fixture(
        val appRoot: File,
        val resources: File,
        val files: EngineFiles,
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
