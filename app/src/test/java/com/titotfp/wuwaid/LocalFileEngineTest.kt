package com.titotfp.wuwaid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalFileEngineTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun copyHashAndAtomicReplaceStayInsideAllowlist() {
        val root = temporary.newFolder("allowed")
        val engine = LocalFileEngine(listOf(root))
        val source = File(root, "source.bin").apply { writeText("versi-baru") }
        val staged = File(root, "nested/patch.new")
        val destination = File(root, "nested/patch.pak").apply {
            parentFile!!.mkdirs()
            writeText("versi-lama")
        }

        assertTrue(engine.copyFile(source.path, staged.path))
        assertEquals("bf10936d74008cef2a78e90ddf6e66fcd182bd14c25cd33788d7e7f50330e333", engine.hash(staged.path, "SHA-256"))
        assertTrue(engine.replaceFile(staged.path, destination.path))
        assertEquals("versi-baru", destination.readText())
        assertFalse(staged.exists())
    }

    @Test
    fun failedReplaceKeepsExistingDestination() {
        val root = temporary.newFolder("allowed")
        val engine = LocalFileEngine(listOf(root))
        val destination = File(root, "patch.pak").apply { writeText("tetap") }

        assertThrows(IllegalArgumentException::class.java) {
            engine.replaceFile(File(root, "missing.new").path, destination.path)
        }
        assertEquals("tetap", destination.readText())
    }

    @Test
    fun rejectsTraversalAndOutsidePath() {
        val root = temporary.newFolder("allowed")
        val outside = temporary.newFile("outside.txt")
        val engine = LocalFileEngine(listOf(root))

        assertFalse(engine.isAllowedPath(outside.path))
        assertFalse(engine.isAllowedPath(File(root, "../outside.txt").path))
        assertThrows(IllegalArgumentException::class.java) { engine.readText(outside.path) }
    }

    @Test
    fun writesTextAtomically() {
        val root = temporary.newFolder("allowed")
        val engine = LocalFileEngine(listOf(root))
        val destination = File(root, "Mount/wuwaindonesia.txt")

        assertTrue(engine.writeTextAtomic(destination.path, "::Mount::\n::Del::\n"))
        assertEquals("::Mount::\n::Del::\n", destination.readText())
        assertFalse(File("${destination.path}.tmp").exists())
    }
}
