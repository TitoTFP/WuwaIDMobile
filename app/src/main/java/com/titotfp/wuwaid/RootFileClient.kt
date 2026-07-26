package com.titotfp.wuwaid

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Implements [PrivilegedFiles] by executing shell commands through `su` (root).
 *
 * Every public method validates paths against an allowlist before interpolation
 * into a shell command, preventing shell injection.
 */
internal class RootFileClient internal constructor(
    private val context: Context,
    private val su: SuExecutor,
) : PrivilegedFiles {
    constructor(context: Context) : this(context, RuntimeSuExecutor())

    internal fun interface SuExecutor {
        fun run(command: String, stdin: ByteArray?, timeoutSeconds: Long): SuResult
    }

    internal data class SuResult(val exitCode: Int, val stdout: String, val stderr: String)

    private class RuntimeSuExecutor : SuExecutor {
        override fun run(command: String, stdin: ByteArray?, timeoutSeconds: Long): SuResult {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            try {
                stdin?.let { data ->
                    process.outputStream.use { it.write(data); it.flush() }
                } ?: process.outputStream.close()

                val exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!exited) {
                    process.destroyForcibly()
                    throw IOException("su command timed out after ${timeoutSeconds}s: $command")
                }

                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                return SuResult(process.exitValue(), stdout, stderr)
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    @Volatile
    private var _lastError: String = ""

    // region allowlist

    private val allowedPrefixes: List<String> by lazy {
        buildList {
            add("/data/data/${GamePaths.GAME_PACKAGE}/")
            add("/storage/emulated/0/Android/data/${GamePaths.GAME_PACKAGE}/")
            val appExternal = context.getExternalFilesDir(null)
            if (appExternal != null) {
                add(canonicalOrAbsolute(appExternal).let { if (it.endsWith("/")) it else "$it/" })
            }
        }
    }

    private fun canonicalOrAbsolute(file: File): String =
        try { file.canonicalPath } catch (_: IOException) { file.absolutePath }

    private fun validatePath(path: String) {
        val resolved = canonicalOrAbsolute(File(path))
        require(allowedPrefixes.any { resolved.startsWith(it) || resolved == it.removeSuffix("/") }) {
            "Path not in allowlist: $path"
        }
    }

    // endregion

    // region shell helpers

    /**
     * Wraps [path] in single quotes, escaping any embedded single-quote as `'\''`.
     */
    private fun quote(path: String): String = "'${path.replace("'", "'\\''")}'"

    private fun runSu(command: String, stdin: ByteArray? = null, timeoutSeconds: Long = 5): SuResult =
        su.run(command, stdin, timeoutSeconds)

    private fun runSuChecked(command: String, stdin: ByteArray? = null): SuResult {
        val result = runSu(command, stdin = stdin)
        if (result.exitCode != 0) {
            val detail = result.stderr.trim().ifBlank { "exit code ${result.exitCode}" }
            throw IOException("su failed ($command): $detail")
        }
        return result
    }

    // endregion

    // region PrivilegedFiles

    override fun exists(path: String): Boolean {
        validatePath(path)
        val result = runSu("test -e ${quote(path)} && echo 1 || echo 0")
        return result.stdout.trim() == "1"
    }

    override fun listFiles(path: String): Array<String> {
        validatePath(path)
        val result = runSuChecked("ls -1 ${quote(path)}")
        return result.stdout.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toTypedArray()
    }

    override fun readText(path: String): String {
        validatePath(path)
        val result = runSuChecked("cat ${quote(path)}")
        return result.stdout
    }

    override fun mkdirs(path: String): Boolean {
        validatePath(path)
        return try {
            runSuChecked("mkdir -p ${quote(path)}")
            fixPermissionsAndOwner(path, isDirectory = true)
            _lastError = ""
            true
        } catch (e: IOException) {
            _lastError = e.message ?: "mkdirs failed"
            false
        }
    }

    override fun copyFile(source: String, destination: String): Boolean {
        validatePath(source)
        validatePath(destination)
        return try {
            runSuChecked("cp ${quote(source)} ${quote(destination)}")
            fixPermissionsAndOwner(destination, isDirectory = false)
            _lastError = ""
            true
        } catch (e: IOException) {
            _lastError = e.message ?: "cp failed"
            false
        }
    }

    override fun replaceFile(source: String, destination: String): Boolean {
        validatePath(source)
        validatePath(destination)
        return try {
            // mv is atomic on the same filesystem; verify source exists first
            runSuChecked("test -e ${quote(source)} || { echo 'source missing' >&2; exit 1; }")
            runSuChecked("mv ${quote(source)} ${quote(destination)}")
            fixPermissionsAndOwner(destination, isDirectory = false)
            _lastError = ""
            true
        } catch (e: IOException) {
            _lastError = e.message ?: "mv failed"
            false
        }
    }

    override fun deleteFile(path: String): Boolean {
        validatePath(path)
        return try {
            runSuChecked("rm -f ${quote(path)}")
            _lastError = ""
            true
        } catch (e: IOException) {
            _lastError = e.message ?: "rm failed"
            false
        }
    }

    override fun sha1(path: String): String {
        validatePath(path)
        // Try toybox first (AOSP), then busybox / coreutils
        val result = runSuChecked("toybox sha1sum ${quote(path)} 2>/dev/null || sha1sum ${quote(path)}")
        return result.stdout.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
    }

    override fun sha256(path: String): String {
        validatePath(path)
        val result = runSuChecked("toybox sha256sum ${quote(path)} 2>/dev/null || sha256sum ${quote(path)}")
        return result.stdout.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
    }

    override fun writeTextAtomic(path: String, content: String): Boolean {
        validatePath(path)
        val tmp = "$path.tmp.${UUID.randomUUID()}"
        validatePath(tmp)
        return try {
            val data = content.toByteArray()
            runSuChecked("cat > ${quote(tmp)}", stdin = data)
            runSuChecked("mv ${quote(tmp)} ${quote(path)}")
            fixPermissionsAndOwner(path, isDirectory = false)
            _lastError = ""
            true
        } catch (e: IOException) {
            _lastError = e.message ?: "writeTextAtomic failed"
            // Best-effort cleanup of temp file
            try { runSu("rm -f ${quote(tmp)}") } catch (_: Exception) { }
            false
        }
    }

    @Volatile
    private var cachedOwner: String? = null

    private fun fixPermissionsAndOwner(path: String, isDirectory: Boolean) {
        try {
            val owner = resolveOwner(path)
            val targetPath = canonicalOrAbsolute(File(path))
            if (owner != null && owner != "0:0") {
                runSu("chown $owner ${quote(targetPath)}")
            }
            val mode = if (isDirectory) "775" else "664"
            runSu("chmod $mode ${quote(targetPath)}")
        } catch (_: Exception) {
            // Best-effort ownership and permission adjustment
        }
    }

    private fun resolveOwner(path: String): String? {
        cachedOwner?.let { return it }

        try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(GamePaths.GAME_PACKAGE, 0)
            if (info.uid > 0) {
                return "${info.uid}:${info.uid}".also { cachedOwner = it }
            }
        } catch (_: Exception) {
        }

        var dir: File? = File(path).parentFile
        while (dir != null) {
            val dirPath = canonicalOrAbsolute(dir)
            if (allowedPrefixes.any { dirPath.startsWith(it) || dirPath == it.removeSuffix("/") }) {
                val statResult = runSu("stat -c '%u:%g' ${quote(dirPath)} 2>/dev/null")
                val owner = statResult.stdout.trim()
                if (owner.contains(":") && owner != "0:0" && owner.isNotBlank()) {
                    return owner.also { cachedOwner = it }
                }
            }
            dir = dir.parentFile
        }

        val appUid = context.applicationInfo.uid
        if (appUid > 0) {
            return "$appUid:$appUid".also { cachedOwner = it }
        }

        return null
    }

    override fun lastError(): String = _lastError

    // endregion

    // region availability

    @Volatile
    private var availabilityCache: Boolean? = null

    /**
     * Returns `true` if a `su` binary is available and responding. The probe
     * blocks up to two seconds, so call it from a background thread; the
     * result is cached until [invalidateProbe].
     */
    fun isAvailable(): Boolean = availabilityCache ?: probeRoot().also { availabilityCache = it }

    fun invalidateProbe() {
        availabilityCache = null
    }

    private fun probeRoot(): Boolean = try {
        runSu("id", timeoutSeconds = 2).exitCode == 0
    } catch (_: Exception) {
        false
    }

    // endregion
}
