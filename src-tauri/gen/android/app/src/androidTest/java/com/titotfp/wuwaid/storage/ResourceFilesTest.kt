package com.titotfp.wuwaid.storage

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResourceFilesTest {
  private fun withFixture(block: (ResourceFiles, File) -> Unit) {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val game = File(context.cacheDir, "resource-files-${System.nanoTime()}")
    val root = File(game, "UE4Game/Client/Client/Saved/Resources")
    check(root.mkdirs())
    try {
      block(ResourceFiles(game), File(root, "payload"))
    } finally {
      game.deleteRecursively()
    }
  }

  private fun openDescriptors(file: File): List<String> {
    val target = file.canonicalPath
    return checkNotNull(File("/proc/self/fd").list()).filter { name ->
      try {
        Os.readlink("/proc/self/fd/$name") == target
      } catch (error: ErrnoException) {
        if (error.errno != OsConstants.ENOENT) throw error
        false
      }
    }
  }

  @Test
  fun repeatedReadsAndHashesReleaseTheirDescriptors() = withFixture { files, payload ->
    payload.writeText("hello")
    repeat(32) {
      assertEquals("hello", String(files.metadata("read_text", "payload", null), Charsets.UTF_8))
      val hashes = JSONObject(String(files.metadata("hashes", "payload", "both"), Charsets.UTF_8))
      assertEquals(5L, hashes.getLong("size"))
      assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hashes.getString("sha256"))
      assertEquals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d", hashes.getString("sha1"))
    }
    assertEquals(emptyList<String>(), openDescriptors(payload))
  }

  @Test
  fun rejectedOversizedReadsReleaseTheirDescriptors() = withFixture { files, payload ->
    payload.writeBytes(ByteArray(256 * 1024 + 1))
    repeat(32) {
      assertThrows(IllegalArgumentException::class.java) {
        files.metadata("read_text", "payload", null)
      }
    }
    assertEquals(emptyList<String>(), openDescriptors(payload))
  }

  @Test
  fun acceptsExactTextLimitAndEmptyFilesWithoutLeakingDescriptors() = withFixture { files, payload ->
    for (bytes in listOf(ByteArray(256 * 1024) { 'a'.code.toByte() }, byteArrayOf())) {
      payload.writeBytes(bytes)
      assertArrayEquals(bytes, files.metadata("read_text", "payload", null))
      assertEquals(emptyList<String>(), openDescriptors(payload))
    }
  }

  @Test
  fun hashesAreFreshAcrossSameSizeMutationModesAndDeletion() = withFixture { files, payload ->
    payload.writeText("hello")
    files.metadata("hashes", "payload", "both")
    payload.writeText("world")
    val expected256 = MessageDigest.getInstance("SHA-256").digest("world".toByteArray())
      .joinToString("") { "%02x".format(it) }
    val expected1 = MessageDigest.getInstance("SHA-1").digest("world".toByteArray())
      .joinToString("") { "%02x".format(it) }
    val single = JSONObject(String(files.metadata("hashes", "payload", "sha256"), Charsets.UTF_8))
    assertEquals(5L, single.getLong("size"))
    assertEquals(expected256, single.getString("sha256"))
    assertTrue(single.isNull("sha1"))
    val both = JSONObject(String(files.metadata("hashes", "payload", "both"), Charsets.UTF_8))
    assertEquals(expected256, both.getString("sha256"))
    assertEquals(expected1, both.getString("sha1"))
    assertEquals(emptyList<String>(), openDescriptors(payload))
    check(payload.delete())
    assertThrows(IllegalStateException::class.java) { files.metadata("hashes", "payload", "both") }
  }

  @Test
  fun statBatchPreservesOrderAt64PathsRejects65AndReflectsFreshChanges() = withFixture { files, payload ->
    val paths = (0 until 64).map { "entry-$it" }
    for ((index, path) in paths.withIndex()) {
      val file = File(payload.parentFile, path)
      if (index % 3 == 0) check(file.mkdir())
      if (index % 3 == 1) file.writeBytes(ByteArray(index))
    }
    val request = JSONArray(paths).toString()
    val results = JSONArray(String(files.metadata("stat_many", request, null), Charsets.UTF_8))
    assertEquals(64, results.length())
    for (index in paths.indices) {
      val result = results.getJSONObject(index)
      assertEquals(index % 3 != 2, result.getBoolean("exists"))
      assertEquals(index % 3 == 0, result.getBoolean("isDir"))
      assertEquals(if (index % 3 == 1) index.toLong() else 0L, result.getLong("size"))
    }
    File(payload.parentFile, "entry-1").writeBytes(ByteArray(86))
    val refreshed = JSONArray(String(files.metadata("stat_many", request, null), Charsets.UTF_8))
    assertEquals(86L, refreshed.getJSONObject(1).getLong("size"))
    assertThrows(IllegalArgumentException::class.java) {
      files.metadata("stat_many", JSONArray(paths + "entry-64").toString(), null)
    }
    assertThrows(IllegalArgumentException::class.java) {
      files.metadata("stat_many", JSONArray(listOf("entry-1", "../outside")).toString(), null)
    }
  }

  @Test
  fun rejectsTraversalAbsoluteControlAndOversizePathsBeforeReading() = withFixture { files, payload ->
    payload.writeText("unchanged")
    for (path in listOf("../payload", "a/../payload", "./payload", "a//payload", "/payload",
      "a\\payload", "payload\u0000", "x".repeat(4097))) {
      for (operation in listOf("stat", "list", "read_text", "hashes")) {
        assertThrows("$operation $path", IllegalArgumentException::class.java) {
          files.metadata(operation, path, if (operation == "hashes") "both" else null)
        }
      }
    }
    assertEquals("unchanged", payload.readText())
    assertEquals(emptyList<String>(), openDescriptors(payload))
  }

  @Test
  fun refusesSymlinkFilesAndDirectoryComponentsWithoutOpeningTheirTargets() = withFixture { files, payload ->
    payload.writeText("private")
    val link = File(payload.parentFile, "link")
    val directoryLink = File(payload.parentFile, "directory-link")
    Os.symlink(payload.path, link.path)
    Os.symlink(payload.parentFile!!.path, directoryLink.path)
    try {
      for (path in listOf("link", "directory-link/payload")) {
        for (operation in listOf("stat", "read_text", "hashes")) {
          assertThrows(SecurityException::class.java) {
            files.metadata(operation, path, if (operation == "hashes") "both" else null)
          }
        }
      }
      assertEquals("private", payload.readText())
      assertEquals(emptyList<String>(), openDescriptors(payload))
    } finally {
      check(link.delete())
      check(directoryLink.delete())
    }
  }

  @Test
  fun rejectsSpecialFilesWithoutBlockingAndDirectoriesCannotBeReadAsText() = withFixture { files, payload ->
    Os.mkfifo(payload.path, 0x180)
    assertThrows(SecurityException::class.java) { files.metadata("stat", "payload", null) }
    assertThrows(IllegalArgumentException::class.java) { files.metadata("read_text", "payload", null) }
    assertThrows(IllegalArgumentException::class.java) { files.metadata("hashes", "payload", "both") }
    assertEquals(emptyList<String>(), openDescriptors(payload))
    check(payload.delete())
    check(payload.mkdir())
    val stat = JSONObject(String(files.metadata("stat", "payload", null), Charsets.UTF_8))
    assertTrue(stat.getBoolean("exists"))
    assertTrue(stat.getBoolean("isDir"))
    assertEquals(0L, stat.getLong("size"))
    assertThrows(IllegalArgumentException::class.java) { files.metadata("read_text", "payload", null) }
  }

  @Test
  fun applyOwnershipNormalizesModesAndRejectsSymlinks() = withFixture { files, payload ->
    val version = File(payload.parentFile, "3.8.0")
    val patchDir = File(version, "wuwaid")
    val mountDir = File(version, "Mount")
    check(patchDir.mkdirs())
    check(mountDir.mkdirs())
    val pak = File(patchDir, "WuwaID_99_P.pak")
    val mount = File(mountDir, "wuwaid.txt")
    pak.writeText("pak")
    mount.writeText("mount")
    Os.chmod(patchDir.path, 448) // 0700
    Os.chmod(pak.path, 384) // 0600
    Os.chmod(mount.path, 384) // 0600

    files.applyOwnership("3.8.0/wuwaid/WuwaID_99_P.pak", isDirectory = false)
    files.applyOwnership("3.8.0/Mount/wuwaid.txt", isDirectory = false)
    assertEquals(493, Os.lstat(patchDir.path).st_mode and 0x1ff)
    assertEquals(420, Os.lstat(pak.path).st_mode and 0x1ff)
    assertEquals(420, Os.lstat(mount.path).st_mode and 0x1ff)

    val link = File(patchDir, "WuwaID_99_P.sig")
    Os.symlink(pak.path, link.path)
    try {
      assertThrows(SecurityException::class.java) {
        files.applyOwnership("3.8.0/wuwaid/WuwaID_99_P.sig", isDirectory = false)
      }
    } finally {
      check(link.delete())
    }
  }
}
