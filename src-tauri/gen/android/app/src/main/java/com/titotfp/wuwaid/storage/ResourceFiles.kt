package com.titotfp.wuwaid.storage

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal fun selectOwner(candidates: Iterable<Pair<Int, Int>>): Pair<Int, Int>? {
  var fallback: Pair<Int, Int>? = null
  for (owner in candidates) {
    if (fallback == null) fallback = owner
    if (owner.first != 0) return owner
  }
  return fallback
}

// Constructed only in the privileged storage service, never in the application process.
internal class ResourceFiles(private val gameFiles: File) {
  private var hashBuffer: ByteArray? = null
  // UserService serializes calls. Reuse engines, never their previous results.
  private var sha256Engine: MessageDigest? = null
  private var sha1Engine: MessageDigest? = null

  private fun root(): File {
    val files = gameFiles.canonicalFile
    require(files.isDirectory) { "Game files directory is unavailable" }
    val root = File(files, "UE4Game/Client/Client/Saved/Resources")
    require(root.canonicalFile == root && root.isDirectory) { "Resource root is not the expected game directory" }
    return root
  }

  private fun checked(root: File, relative: String, allowEmpty: Boolean = false): File {
    require(relative.length <= 4096 && !relative.startsWith('/') && !relative.contains('\\') &&
      !relative.contains('\u0000')) { "Invalid resource-relative path" }
    if (relative.isEmpty()) {
      require(allowEmpty) { "Resource path is required" }
      return root
    }
    var target = root
    for (component in relative.split('/')) {
      require(component.isNotEmpty() && component != "." && component != "..") { "Invalid path component" }
      target = File(target, component)
      val stat = lstat(target)
      if (stat != null && OsConstants.S_ISLNK(stat.st_mode)) throw SecurityException("Symlinks are not allowed")
      if (stat != null && target.canonicalFile != target) throw SecurityException("Resource path escapes the game root")
    }
    return target
  }

  private fun lstat(file: File): StructStat? = try {
    Os.lstat(file.path)
  } catch (error: ErrnoException) {
    if (error.errno == OsConstants.ENOENT) null else throw error
  }

  private fun status(file: File): JSONObject {
    val stat = lstat(file)
    if (stat != null && !OsConstants.S_ISDIR(stat.st_mode) && !OsConstants.S_ISREG(stat.st_mode)) {
      throw SecurityException("Resource is not a regular file or directory")
    }
    return JSONObject().put("exists", stat != null)
      .put("isDir", stat != null && OsConstants.S_ISDIR(stat.st_mode))
      .put("size", if (stat != null && OsConstants.S_ISREG(stat.st_mode)) stat.st_size else 0L)
  }

  // Both digests and size describe the same opened regular file, in one stream.
  // No hash or metadata cache survives an operation or a mutation boundary.
  private fun <T> readRegular(root: File, relative: String, block: (FileDescriptor, StructStat) -> T): T {
    val target = checked(root, relative)
    val before = lstat(target) ?: throw IllegalStateException("Resource file is missing")
    require(OsConstants.S_ISREG(before.st_mode)) { "Resource is not a regular file" }
    val fd = Os.open(target.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
    try {
      val opened = Os.fstat(fd)
      require(sameFile(before, opened)) { "Resource changed while opening" }
      val result = block(fd, opened)
      val after = Os.fstat(fd)
      val pathAfter = lstat(checked(root, relative))
      require(pathAfter != null && sameFile(opened, after) && sameFile(after, pathAfter)) {
        "Resource changed while reading"
      }
      return result
    } finally {
      // Own the Os.open descriptor explicitly; Android streams borrow caller FDs.
      Os.close(fd)
    }
  }

  private fun sameFile(a: StructStat, b: StructStat): Boolean =
    a.st_dev == b.st_dev && a.st_ino == b.st_ino && a.st_mode == b.st_mode &&
      a.st_size == b.st_size && a.st_mtime == b.st_mtime && a.st_ctime == b.st_ctime &&
      (Build.VERSION.SDK_INT < 27 ||
        (a.st_mtim.tv_nsec == b.st_mtim.tv_nsec && a.st_ctim.tv_nsec == b.st_ctim.tv_nsec))

  fun metadata(operation: String, first: String?, second: String?): ByteArray {
    val root = root()
    val result: String = when (operation) {
      "probe_root" -> {
        require(first == null && second == null) { "Unexpected probe arguments" }
        val readable = Os.access(root.path, OsConstants.R_OK or OsConstants.X_OK) && root.list() != null
        val writable = readable && Os.access(root.path, OsConstants.W_OK or OsConstants.X_OK)
        JSONObject().put("readable", readable).put("writable", writable).toString()
      }
      "stat" -> {
        require(second == null) { "Unexpected stat arguments" }
        status(checked(root, requireNotNull(first))).toString()
      }
      "stat_many" -> {
        require(second == null && first != null && first.toByteArray(Charsets.UTF_8).size <= LIMIT) { "Invalid stat batch" }
        val paths = JSONArray(first)
        require(paths.length() <= 64) { "Stat batch exceeds 64 paths" }
        val entries = JSONArray()
        for (index in 0 until paths.length()) entries.put(status(checked(root, paths.getString(index))))
        entries.toString()
      }
      "list" -> {
        require(second == null) { "Unexpected list arguments" }
        val directory = checked(root, requireNotNull(first), allowEmpty = true)
        val names = directory.list() ?: throw IllegalStateException("Resource path is not a readable directory")
        names.sort()
        val entries = JSONArray()
        var bytes = 2
        for (name in names) {
          val entry = checked(root, if (first.isEmpty()) name else "$first/$name")
          val stat = lstat(entry) ?: throw IllegalStateException("Directory entry disappeared")
          val record = JSONObject().put("name", name).put("isDir", OsConstants.S_ISDIR(stat.st_mode))
          bytes += record.toString().toByteArray(Charsets.UTF_8).size + 1
          require(bytes <= LIMIT) { "Directory metadata exceeds the 256 KiB limit" }
          entries.put(record)
        }
        entries.toString()
      }
      "read_text" -> {
        require(second == null) { "Unexpected text arguments" }
        return readRegular(root, requireNotNull(first)) { input, stat ->
          require(stat.st_size <= LIMIT) { "Text resource exceeds the 256 KiB limit" }
          val output = ByteArray(stat.st_size.toInt())
          var size = 0
          while (size < output.size) {
            val count = Os.read(input, output, size, output.size - size)
            require(count > 0) { "Text resource changed while reading" }
            size += count
          }
          output
        }
      }
      "hashes" -> {
        require(second == "sha256" || second == "both") { "Unsupported hash request" }
        readRegular(root, requireNotNull(first)) { input, stat ->
          val buffer = hashBuffer ?: ByteArray(64 * 1024).also { hashBuffer = it }
          val sha256 = (sha256Engine ?: MessageDigest.getInstance("SHA-256").also { sha256Engine = it })
            .apply { reset() }
          val sha1 = if (second == "both") {
            (sha1Engine ?: MessageDigest.getInstance("SHA-1").also { sha1Engine = it }).apply { reset() }
          } else null
          var size = 0L
          while (true) {
            val count = Os.read(input, buffer, 0, buffer.size)
            if (count == 0) break
            sha256.update(buffer, 0, count)
            sha1?.update(buffer, 0, count)
            size += count
          }
          require(size == stat.st_size) { "Resource size changed while hashing" }
          JSONObject().put("size", size).put("sha256", hex(sha256.digest()))
            .put("sha1", sha1?.let { hex(it.digest()) } ?: JSONObject.NULL).toString()
        }
      }
      else -> throw IllegalArgumentException("Unsupported metadata operation")
    }
    return result.toByteArray(Charsets.UTF_8).also {
      require(it.size <= LIMIT) { "Storage metadata response exceeded its limit" }
    }
  }

  private fun hex(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    val output = CharArray(bytes.size * 2)
    for (index in bytes.indices) {
      val byte = bytes[index].toInt() and 255
      output[index * 2] = digits[byte ushr 4]
      output[index * 2 + 1] = digits[byte and 15]
    }
    return String(output)
  }

  fun applyOwnership(relative: String, isDirectory: Boolean) {
    val root = root()
    val target = checked(root, relative)
    val owner = resolveOwner(root, relative)
    val callerUid = Os.getuid()
    if (!isDirectory) {
      val parent = target.parentFile
      if (parent != null && (parent.name == "wuwaid" || parent.name == "wuwaindonesia")) {
        applyEntryMetadata(parent, expectedIsDir = true, owner.first, owner.second, callerUid)
      }
    }
    applyEntryMetadata(target, expectedIsDir = isDirectory, owner.first, owner.second, callerUid)
  }

  private fun resolveOwner(root: File, relative: String): Pair<Int, Int> {
    val version = relative.substringBefore('/', "")
    val candidates = buildList {
      if (version.isNotEmpty()) add(File(root, version))
      add(root)
      add(gameFiles)
      gameFiles.parentFile?.let(::add)
    }.mapNotNull { dir ->
      val stat = lstat(dir)
      if (stat != null && OsConstants.S_ISDIR(stat.st_mode)) stat.st_uid to stat.st_gid else null
    }
    return selectOwner(candidates) ?: throw IllegalStateException("Resource owner directory is unavailable")
  }

  private fun applyEntryMetadata(file: File, expectedIsDir: Boolean, uid: Int, gid: Int, callerUid: Int) {
    val before = lstat(file) ?: throw IllegalStateException("Resource target is missing")
    val actualIsDir = OsConstants.S_ISDIR(before.st_mode)
    require(actualIsDir == expectedIsDir && (actualIsDir || OsConstants.S_ISREG(before.st_mode))) {
      "Resource target has an unexpected file type"
    }
    val mode = if (expectedIsDir) DIR_MODE else FILE_MODE
    val flags = OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK
    val fd = Os.open(file.path, flags, 0)
    try {
      val opened = Os.fstat(fd)
      require(
        opened.st_dev == before.st_dev && opened.st_ino == before.st_ino &&
          OsConstants.S_ISDIR(opened.st_mode) == expectedIsDir
      ) {
        "Resource target changed while updating permissions"
      }
      var currentUid = opened.st_uid
      if (callerUid == 0 && (opened.st_uid != uid || opened.st_gid != gid)) {
        try {
          Os.fchown(fd, uid, gid)
          currentUid = uid
        } catch (error: ErrnoException) {
          val toleratesError = error.errno == OsConstants.ENOSYS ||
            error.errno == OsConstants.EOPNOTSUPP ||
            (error.errno == OsConstants.EPERM && opened.st_uid != 0)
          if (!toleratesError) throw error
        }
      }
      if ((callerUid == 0 || currentUid == callerUid) && (opened.st_mode and 0x1ff) != mode) {
        try {
          Os.fchmod(fd, mode)
        } catch (error: ErrnoException) {
          val toleratesError = error.errno == OsConstants.ENOSYS ||
            error.errno == OsConstants.EOPNOTSUPP ||
            (error.errno == OsConstants.EPERM && (callerUid != 0 || currentUid != 0 || uid == 0))
          if (!toleratesError) throw error
        }
      }
    } finally {
      Os.close(fd)
    }
  }

  companion object {
    private const val LIMIT = 256 * 1024
    private const val DIR_MODE = 493 // 0755
    private const val FILE_MODE = 420 // 0644
  }
}
