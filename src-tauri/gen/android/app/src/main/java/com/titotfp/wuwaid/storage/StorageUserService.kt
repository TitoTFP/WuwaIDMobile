package com.titotfp.wuwaid.storage

import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

private val resourceVersion = Regex("""(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)""")
private val ownedFiles = buildSet {
  for (path in listOf("wuwaid/WuwaID_99_P.pak", "wuwaid/WuwaID_99_P.sig",
    "wuwaid/.owner.json", "wuwaid/.transaction.json", "Mount/wuwaid.txt")) {
    add(path)
    add("$path.new")
    add("$path.bak")
  }
  addAll(listOf("wuwaindonesia/WuWaID_99_P.pak", "wuwaindonesia/WuWaID_99_P.sig", "Mount/wuwaindonesia.txt"))
}

internal fun requireWritableTarget(
  path: String,
  allowPatchRoot: Boolean,
  allowMountDirectory: Boolean = false,
  directoryOnly: Boolean = false
) {
  val separator = path.indexOf('/')
  if (separator <= 0 || !resourceVersion.matches(path.substring(0, separator))) {
    throw SecurityException("Mutation requires a numeric game resource version")
  }
  val relative = path.substring(separator + 1)
  val directory = (allowPatchRoot && (relative == "wuwaid" || relative == "wuwaindonesia")) ||
    (allowMountDirectory && relative == "Mount")
  if (!directory && (directoryOnly || relative !in ownedFiles)) {
    throw SecurityException("Mutation is restricted to WuwaID-owned resource paths")
  }
}

class StorageUserService : IStorageUserService.Stub() {
  private var gamePackage = GamePackages.GLOBAL
  private var resourceRoot = GamePackages.resources(gamePackage).path
  private var files = ResourceFiles(GamePackages.files(gamePackage))

  @Synchronized
  override fun selectGame(packageName: String?) {
    require(packageName != null && packageName in GamePackages.supported) { "Unsupported Wuthering Waves package" }
    if (gamePackage != packageName) {
      gamePackage = packageName
      resourceRoot = GamePackages.resources(packageName).path
      files = ResourceFiles(GamePackages.files(packageName))
    }
  }

  @Synchronized
  override fun metadata(operation: String?, first: String?, second: String?, requestId: Long): Bundle =
    StorageTrace.phase(requestId, when (operation) {
      "probe_root" -> "storage.probe_root"
      "list" -> "storage.list"
      "stat" -> "storage.stat"
      "stat_many" -> "storage.stat_many"
      "read_text" -> "storage.read_text"
      "hashes" -> "storage.hashes"
      else -> "storage.invalid"
    }) {
      try {
        Bundle().apply {
          putInt(KEY_EXIT_CODE, 0)
          val stdout = if (operation == "detect_game") {
            require(second == null && first != null && first.length <= 256) { "Invalid game selection" }
            val candidates = org.json.JSONArray(first)
            require(candidates.length() in 1..GamePackages.supported.size) { "Invalid game selection count" }
            val selected = GamePackages.choose((0 until candidates.length()).map { candidates.getString(it) })
            selectGame(selected)
            org.json.JSONObject().put("packageName", selected).toString().toByteArray(Charsets.UTF_8)
          } else {
            files.metadata(requireNotNull(operation), first, second)
          }
          putByteArray(KEY_STDOUT, stdout)
          putByteArray(KEY_STDERR, byteArrayOf())
        }
      } catch (error: Exception) {
        failure(error.message ?: error.javaClass.simpleName)
      }
    }
  private val outputExecutor = Executors.newFixedThreadPool(2) { runnable ->
    Thread(runnable, "wuwaid-user-service-output").apply { isDaemon = true }
  }

  // Both stream readers must be available for the same process to avoid pipe deadlock.
  @Synchronized
  override fun execute(
    operation: String?,
    first: String?,
    second: String?,
    input: ParcelFileDescriptor?,
    requestId: Long
  ): Bundle = StorageTrace.phase(requestId, "storage.mutation") {
    executeCommand(operation, first, second, input)
  }

  private fun executeCommand(
    operation: String?,
    first: String?,
    second: String?,
    input: ParcelFileDescriptor?
  ): Bundle {
    val inputFd = input ?: return failure("Storage command input pipe is missing")
    val script = try {
      scriptFor(operation, first, second)
    } catch (error: Exception) {
      try { inputFd.close() } catch (_: Exception) {}
      return failure(error.message ?: error.javaClass.simpleName)
    }
    val arguments = buildList {
      if (first != null) add(first)
      if (second != null) add(second)
    }
    val command = arrayListOf("/system/bin/sh", "-c", script, "wuwaid-storage", resourceRoot)
    command.addAll(arguments)
    val process = try {
      ProcessBuilder(command).start()
    } catch (error: Exception) {
      try { inputFd.close() } catch (_: Exception) {}
      return failure(error.message ?: error.javaClass.simpleName)
    }

    return try {
      val stdout = OutputCapture(process.inputStream, MAX_METADATA_BYTES)
      val stderr = OutputCapture(process.errorStream, MAX_ERROR_BYTES)
      val stdoutTask = outputExecutor.submit(stdout)
      val stderrTask = outputExecutor.submit(stderr)
      val inputFailure = try {
        ParcelFileDescriptor.AutoCloseInputStream(inputFd).use { remoteInput ->
          process.outputStream.use { shellInput -> remoteInput.copyTo(shellInput, STREAM_BUFFER_BYTES) }
        }
        null
      } catch (error: Exception) {
        error
      }
      if (inputFailure != null) {
        process.destroy()
        return failure(inputFailure.message ?: inputFailure.javaClass.simpleName)
      }

      val exitCode = process.waitFor()
      stdoutTask.get()
      stderrTask.get()
      val stdoutBytes = stdout.bytes()
      val stderrBytes = stderr.bytes()
      if (stdoutBytes.size > MAX_METADATA_BYTES) {
        return failure("Storage metadata response exceeded its limit")
      }
      if (stderrBytes.size > MAX_ERROR_BYTES) {
        return failure("Storage error output exceeded its limit")
      }
      if (exitCode == 0) {
        when (operation) {
          "mkdir" -> files.applyOwnership(requireNotNull(first), isDirectory = true)
          "write" -> files.applyOwnership(requireNotNull(first), isDirectory = false)
          "copy", "move_file" -> files.applyOwnership(requireNotNull(second), isDirectory = false)
        }
      }
      Bundle().apply {
        putInt(KEY_EXIT_CODE, exitCode)
        putByteArray(KEY_STDOUT, stdoutBytes)
        putByteArray(KEY_STDERR, stderrBytes)
      }
    } catch (error: Exception) {
      process.destroy()
      failure(error.message ?: error.javaClass.simpleName)
    } finally {
      try { inputFd.close() } catch (_: Exception) {}
    }
  }
  internal fun close() {
    outputExecutor.shutdownNow()
  }

  // Bound-service adapters close workers through their own lifecycle, without the Shizuku exit command.
  override fun destroy() {
    close()
    System.exit(0)
  }

  private fun scriptFor(operation: String?, first: String?, second: String?): String {
    val op = operation ?: throw IllegalArgumentException("Storage operation is required")
    when (op) {
      "force_stop" -> require(first == null && second == null) { "Unexpected storage arguments" }
      "remove" -> {
        require(first != null && second == null) { "Resource path is required" }
        requireWritableTarget(validateRelative(first, allowEmpty = false), allowPatchRoot = true)
      }
      "mkdir" -> {
        require(first != null && second == null) { "Directory path is required" }
        val path = validateRelative(first, allowEmpty = false)
        requireWritableTarget(path, allowPatchRoot = true, allowMountDirectory = true, directoryOnly = true)
      }
      "copy" -> {
        require(first != null && second != null) { "Source and target paths are required" }
        validateRelative(first, allowEmpty = false)
        requireWritableTarget(validateRelative(second, allowEmpty = false), allowPatchRoot = false)
      }
      "move_file" -> {
        require(first != null && second != null) { "Source and target paths are required" }
        requireWritableTarget(validateRelative(first, allowEmpty = false), allowPatchRoot = false)
        requireWritableTarget(validateRelative(second, allowEmpty = false), allowPatchRoot = false)
      }
      "write" -> {
        require(first != null && second == null) { "Write target path is required" }
        requireWritableTarget(validateRelative(first, allowEmpty = false), allowPatchRoot = false)
      }
      else -> throw IllegalArgumentException("Unsupported storage operation")
    }
    return when (op) {
      "mkdir" -> MKDIR_SCRIPT
      "copy" -> COPY_SCRIPT
      "move_file" -> MOVE_SCRIPT
      "remove" -> REMOVE_SCRIPT
      "write" -> WRITE_SCRIPT
      "force_stop" -> FORCE_STOP_SCRIPT
      else -> throw IllegalArgumentException("Unsupported storage operation")
    }
  }


  private fun validateRelative(path: String, allowEmpty: Boolean): String {
    if (path.length > MAX_PATH_LENGTH || path.indexOf('\u0000') >= 0 || path.startsWith('/') || path.contains('\\')) {
      throw IllegalArgumentException("Invalid resource-relative path")
    }
    if (path.isEmpty()) {
      if (allowEmpty) return path
      throw IllegalArgumentException("Path cannot be empty")
    }
    if (path.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
      throw IllegalArgumentException("Invalid resource-relative path")
    }
    return path
  }

  private fun failure(message: String): Bundle = Bundle().apply {
    putInt(KEY_EXIT_CODE, 255)
    putByteArray(KEY_STDOUT, byteArrayOf())
    putByteArray(KEY_STDERR, message.take(4096).toByteArray(StandardCharsets.UTF_8))
  }

  private class OutputCapture(private val stream: InputStream, private val limit: Int) : Runnable {
    private val output = ByteArrayOutputStream(minOf(limit + 1, 256))
    @Volatile private var failure: Exception? = null

    override fun run() {
      try {
        stream.use { input ->
          val bytes = readBuffers.get()!!
          while (true) {
            val count = input.read(bytes)
            if (count < 0) break
            val remaining = (limit + 1 - output.size()).coerceAtLeast(0)
            if (remaining > 0) output.write(bytes, 0, minOf(count, remaining))
          }
        }
      } catch (error: Exception) {
        failure = error
      }
    }

    fun bytes(): ByteArray {
      failure?.let { throw IllegalStateException("Unable to read storage process output", it) }
      return output.toByteArray()
    }
  }

  companion object {
    private const val MAX_PATH_LENGTH = 4096
    private const val MAX_METADATA_BYTES = 256 * 1024
    private const val MAX_ERROR_BYTES = 16 * 1024
    private const val STREAM_BUFFER_BYTES = 64 * 1024
    private val readBuffers = object : ThreadLocal<ByteArray>() {
      override fun initialValue(): ByteArray = ByteArray(STREAM_BUFFER_BYTES)
    }
    private const val KEY_EXIT_CODE = "exitCode"
    private const val KEY_STDOUT = "stdout"
    private const val KEY_STDERR = "stderr"

    private val ROOT_CHECK = shell("""
case "§1" in
  /storage/emulated/0/Android/data/com.kurogame.wutheringwaves.global/files/UE4Game/Client/Client/Saved/Resources|/storage/emulated/0/Android/data/com.kurogame.wutheringwaves.samsung/files/UE4Game/Client/Client/Saved/Resources) ;;
  *) echo 'unsupported game resource root' >&2; exit 1 ;;
esac
EXPECTED_FILES=$(readlink -f -- "§{1%/UE4Game/Client/Client/Saved/Resources}") || { echo 'game files directory is unavailable' >&2; exit 1; }
[ -d "§EXPECTED_FILES" ] && [ "§ROOT" = "§EXPECTED_FILES/UE4Game/Client/Client/Saved/Resources" ] || { echo 'resource root is not the expected game directory' >&2; exit 1; }
""")


    private val CHECK_PATH_FUNCTION = shell("""
set -f
ROOT=$(readlink -f -- "§1") || { echo 'resource root is unavailable' >&2; exit 1; }
""") + ROOT_CHECK + shell("""
check_rel() {
  rel="§1"
  [ -n "§rel" ] || return 0
  current="§ROOT"
  saved_ifs="§IFS"
  IFS='/'
  for component in §rel; do
    [ -n "§component" ] || { IFS="§saved_ifs"; echo 'invalid path component' >&2; return 1; }
    current="§current/§component"
    [ ! -L "§current" ] || { IFS="§saved_ifs"; echo 'symlinks are not allowed' >&2; return 1; }
    if [ -e "§current" ]; then
      resolved=$(readlink -f -- "§current") || { IFS="§saved_ifs"; echo 'unable to resolve resource path' >&2; return 1; }
      case "§resolved" in "§ROOT"|"§ROOT"/*) ;; *) IFS="§saved_ifs"; echo 'resource path escapes the game root' >&2; return 1 ;; esac
    fi
  done
  IFS="§saved_ifs"
  return 0
}
""")

    private fun checked(body: String): String = CHECK_PATH_FUNCTION + shell(body)


    private val MKDIR_SCRIPT = checked("""
REL="§2"
check_rel "§REL" || exit 1
TARGET="§ROOT/§REL"
mkdir -p "§TARGET" || { echo 'unable to create owned resource directory' >&2; exit 1; }
check_rel "§REL" || exit 1
[ -d "§TARGET" ] || { echo 'created resource path is not a directory' >&2; exit 1; }
""")

    private val COPY_SCRIPT = checked("""
SOURCE_REL="§2"
TARGET_REL="§3"
check_rel "§SOURCE_REL" || exit 1
check_rel "§TARGET_REL" || exit 1
SOURCE="§ROOT/§SOURCE_REL"
TARGET="§ROOT/§TARGET_REL"
[ -f "§SOURCE" ] || { echo 'copy source is not a regular file' >&2; exit 1; }
[ ! -e "§TARGET" ] && [ ! -L "§TARGET" ] || { echo 'copy target already exists' >&2; exit 1; }
PARENT=§{TARGET%/*}
[ -d "§PARENT" ] || { echo 'copy target parent is not a directory' >&2; exit 1; }
cp "§SOURCE" "§TARGET" || { echo 'resource copy failed' >&2; exit 1; }
check_rel "§TARGET_REL" || exit 1
""")

    private val MOVE_SCRIPT = checked("""
SOURCE_REL="§2"
TARGET_REL="§3"
check_rel "§SOURCE_REL" || exit 1
check_rel "§TARGET_REL" || exit 1
SOURCE="§ROOT/§SOURCE_REL"
TARGET="§ROOT/§TARGET_REL"
[ -e "§SOURCE" ] || { echo 'move source does not exist' >&2; exit 1; }
[ ! -e "§TARGET" ] && [ ! -L "§TARGET" ] || { echo 'move target already exists' >&2; exit 1; }
PARENT=§{TARGET%/*}
[ -d "§PARENT" ] || { echo 'move target parent is not a directory' >&2; exit 1; }
mv "§SOURCE" "§TARGET" || { echo 'resource rename failed' >&2; exit 1; }
check_rel "§TARGET_REL" || exit 1
""")

    private val REMOVE_SCRIPT = checked("""
REL="§2"
check_rel "§REL" || exit 1
TARGET="§ROOT/§REL"
if [ -d "§TARGET" ]; then
  rmdir "§TARGET" || { echo 'owned directory is not empty' >&2; exit 1; }
else
  rm -f "§TARGET" || { echo 'resource removal failed' >&2; exit 1; }
fi
""")
    private val WRITE_SCRIPT = checked("""
REL="§2"
check_rel "§REL" || exit 1
TARGET="§ROOT/§REL"
PARENT=§{TARGET%/*}
[ -d "§PARENT" ] || { echo 'write target parent is not a directory' >&2; exit 1; }
[ ! -d "§TARGET" ] || { echo 'write target is a directory' >&2; exit 1; }
cat > "§TARGET" || { echo 'resource write failed' >&2; exit 1; }
check_rel "§REL" || exit 1
""")

    private val FORCE_STOP_SCRIPT = shell("""
set -f
case "§1" in
  /storage/emulated/0/Android/data/com.kurogame.wutheringwaves.global/files/UE4Game/Client/Client/Saved/Resources) GAME_PACKAGE=com.kurogame.wutheringwaves.global ;;
  /storage/emulated/0/Android/data/com.kurogame.wutheringwaves.samsung/files/UE4Game/Client/Client/Saved/Resources) GAME_PACKAGE=com.kurogame.wutheringwaves.samsung ;;
  *) echo 'unsupported game resource root' >&2; exit 1 ;;
esac
am force-stop "§GAME_PACKAGE" || { echo 'unable to force-stop Wuthering Waves' >&2; exit 1; }
""")

    private fun shell(script: String): String = script.replace('§', '$')
  }
}