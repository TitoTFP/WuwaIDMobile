package com.titotfp.wuwaid

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Root backend whose privileged filesystem boundary lives in the native helper. */
internal class RootFileClient internal constructor(
    context: Context,
    private val helper: HelperExecutor,
) : PrivilegedFiles {
    constructor(context: Context) : this(context, RuntimeHelperExecutor(context))

    internal fun interface HelperExecutor {
        @Throws(IOException::class)
        fun run(
            request: ByteArray,
            timeoutSeconds: Long,
        ): ProcessResult
    }

    internal data class ProcessResult(
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: String,
    )

    private enum class Op(
        val code: Int,
        val fields: Int,
        val longRunning: Boolean = false,
    ) {
        PING(0, 0),
        COPY(1, 2, true),
        REPLACE(2, 2, true),
        DELETE(3, 1),
        EXISTS(4, 1),
        MKDIRS(5, 1),
        LIST(6, 1),
        READ(7, 1, true),
        WRITE_ATOMIC(8, 2, true),
        SHA1(9, 1, true),
        SHA256(10, 1, true),
    }

    internal data class Response(
        val status: Int,
        val errno: Int,
        val payload: ByteArray,
    )

    private val allowedPrefixes =
        buildList {
            add("/storage/emulated/0/Android/data/${GamePaths.GAME_PACKAGE}")
            add("/data/data/${GamePaths.GAME_PACKAGE}")
            add("/data/user/0/${GamePaths.GAME_PACKAGE}")
            context.getExternalFilesDir(null)?.canonicalPath?.let { add(it.substringBefore("/files")) }
        }.distinct()

    @Volatile private var error = ""

    @Volatile private var availability: Boolean? = null

    override fun exists(path: String): Boolean = value(Op.EXISTS, path) { it.singleByteBoolean() } ?: false

    override fun listFiles(path: String): Array<String> = value(Op.LIST, path) { decodeStringList(it).toTypedArray() } ?: emptyArray()

    override fun readText(path: String): String = value(Op.READ, path) { decodeUtf8(it) } ?: ""

    override fun mkdirs(path: String): Boolean = action(Op.MKDIRS, path)

    override fun copyFile(
        source: String,
        destination: String,
    ): Boolean = action(Op.COPY, source, destination)

    override fun replaceFile(
        source: String,
        destination: String,
    ): Boolean = action(Op.REPLACE, source, destination)

    override fun deleteFile(path: String): Boolean = action(Op.DELETE, path)

    override fun sha1(path: String): String = value(Op.SHA1, path) { decodeUtf8(it) } ?: ""

    override fun sha256(path: String): String = value(Op.SHA256, path) { decodeUtf8(it) } ?: ""

    override fun writeTextAtomic(
        path: String,
        content: String,
    ): Boolean = actionBytes(Op.WRITE_ATOMIC, listOf(path.toProtocolField(), content.toByteArray(StandardCharsets.UTF_8)))

    override fun lastError(): String = error

    fun isAvailable(): Boolean = availability ?: probeRoot().also { availability = it }

    fun invalidateProbe() {
        availability = null
    }

    private fun probeRoot(): Boolean =
        try {
            exchange(Op.PING, emptyList())
            error = ""
            true
        } catch (e: Exception) {
            error = safeMessage(e, "Root tidak tersedia")
            false
        }

    private fun action(
        op: Op,
        vararg paths: String,
    ): Boolean = actionBytes(op, paths.map { it.toProtocolField() })

    private fun actionBytes(
        op: Op,
        fields: List<ByteArray>,
    ): Boolean =
        try {
            exchange(op, fields)
            error = ""
            true
        } catch (e: Exception) {
            error = safeMessage(e, "Operasi root gagal")
            false
        }

    private fun <T> value(
        op: Op,
        vararg paths: String,
        decode: (ByteArray) -> T,
    ): T? =
        try {
            decode(exchange(op, paths.map { it.toProtocolField() }).payload).also { error = "" }
        } catch (e: Exception) {
            error = safeMessage(e, "Operasi root gagal")
            null
        }

    private fun String.toProtocolField(): ByteArray {
        require(isNotEmpty() && indexOf('\u0000') < 0) { "Path tidak valid" }
        val absolute = File(this).absolutePath
        require(allowedPrefixes.any { absolute == it || absolute.startsWith("$it/") }) { "Path di luar direktori yang diizinkan" }
        val relative =
            allowedPrefixes
                .first { absolute == it || absolute.startsWith("$it/") }
                .let { absolute.removePrefix(it).removePrefix("/") }
        require(relative.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Path tidak valid" }
        return absolute.toByteArray(StandardCharsets.UTF_8).also {
            require(it.size <= MAX_FIELD) { "Path terlalu panjang" }
        }
    }

    private fun exchange(
        op: Op,
        fields: List<ByteArray>,
    ): Response {
        require(fields.size == op.fields) { "Jumlah field protocol salah" }
        val request = Protocol.encodeRequest(op.code, fields)
        val result = helper.run(request, if (op.longRunning) LONG_TIMEOUT else METADATA_TIMEOUT)
        if (result.exitCode != 0) throw IOException("Helper root berhenti tidak normal (${result.exitCode}): ${sanitize(result.stderr)}")
        val response = Protocol.decodeResponse(result.stdout)
        if (response.status != 0) {
            val detail = runCatching { decodeUtf8(response.payload) }.getOrDefault("operasi ditolak")
            throw IOException("Helper root gagal (${response.errno}): ${sanitize(detail)}")
        }
        return response
    }

    private fun ByteArray.singleByteBoolean(): Boolean {
        if (size != 1 || (this[0] != 0.toByte() && this[0] != 1.toByte())) throw IOException("Respons boolean tidak valid")
        return this[0] == 1.toByte()
    }

    private fun safeMessage(
        e: Exception,
        fallback: String,
    ): String = sanitize(e.message ?: fallback)

    private fun sanitize(value: String): String =
        value
            .replace(Regex("/[A-Za-z0-9_./-]+"), "<path>")
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .take(MAX_ERROR)
            .ifBlank { "Operasi root gagal" }

    internal object Protocol {
        private const val MAGIC = 0x57554944
        private const val VERSION = 1

        fun encodeRequest(
            opcode: Int,
            fields: List<ByteArray>,
        ): ByteArray {
            require(fields.size <= MAX_FIELDS && fields.all { it.size <= MAX_FIELD })
            val bodySize = 2 + fields.sumOf { 4 + it.size }
            require(bodySize <= MAX_FRAME)
            return ByteArrayOutputStream(14 + bodySize)
                .also { bytes ->
                    DataOutputStream(bytes).use { out ->
                        out.writeInt(MAGIC)
                        out.writeShort(VERSION)
                        out.writeShort(opcode)
                        out.writeInt(bodySize)
                        out.writeShort(fields.size)
                        fields.forEach {
                            out.writeInt(it.size)
                            out.write(it)
                        }
                    }
                }.toByteArray()
        }

        fun decodeResponse(bytes: ByteArray): Response {
            if (bytes.size > MAX_RESPONSE) throw IOException("Respons helper terlalu besar")
            try {
                DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                    if (input.readInt() != MAGIC ||
                        input.readUnsignedShort() != VERSION
                    ) {
                        throw IOException("Header respons helper tidak valid")
                    }
                    val status = input.readUnsignedShort()
                    val errno = input.readInt()
                    val length = input.readInt()
                    if (status !in 0..1 || length !in 0..MAX_PAYLOAD ||
                        length != input.available()
                    ) {
                        throw IOException("Frame respons helper tidak valid")
                    }
                    return Response(status, errno, ByteArray(length).also(input::readFully))
                }
            } catch (_: EOFException) {
                throw IOException("Respons helper terpotong")
            }
        }
    }

    private class RuntimeHelperExecutor(
        context: Context,
    ) : HelperExecutor {
        private val helperPath = File(context.applicationInfo.nativeLibraryDir, HELPER_NAME).canonicalPath

        override fun run(
            request: ByteArray,
            timeoutSeconds: Long,
        ): ProcessResult {
            if (!helperPath.endsWith("/$HELPER_NAME")) throw IOException("Lokasi helper tidak valid")
            // `exec` replaces su's command shell with the one-shot helper where the root
            // implementation supports normal sh semantics, minimizing orphan descendants.
            val command = "exec '${helperPath.replace("'", "'\\''")}' --stdio"
            val process = ProcessBuilder("su", "-c", command).start()
            val pool = Executors.newFixedThreadPool(3)
            val stdout = pool.submit(Callable { drain(process.inputStream, MAX_RESPONSE) })
            val stderr = pool.submit(Callable { drain(process.errorStream, MAX_STDERR).toString(StandardCharsets.UTF_8) })
            val stdin =
                pool.submit(
                    Callable {
                        process.outputStream.use {
                            it.write(request)
                            it.flush()
                        }
                    },
                )
            try {
                val exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!exited) {
                    process.destroy()
                    if (!process.waitFor(1, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        if (!process.waitFor(5, TimeUnit.SECONDS)) throw IOException("Helper root tidak dapat dihentikan")
                    }
                    throw IOException("Helper root melewati batas waktu")
                }
                stdin.get(5, TimeUnit.SECONDS)
                return ProcessResult(process.exitValue(), stdout.get(5, TimeUnit.SECONDS), stderr.get(5, TimeUnit.SECONDS))
            } catch (e: Exception) {
                if (process.isAlive) process.destroyForcibly()
                throw if (e is IOException) e else IOException("Helper root gagal dijalankan", e)
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    runCatching { process.waitFor(5, TimeUnit.SECONDS) }
                }
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                stdout.cancel(true)
                stderr.cancel(true)
                stdin.cancel(true)
                pool.shutdownNow()
                runCatching { pool.awaitTermination(5, TimeUnit.SECONDS) }
            }
        }

        private fun drain(
            input: java.io.InputStream,
            keep: Int,
        ): ByteArray {
            val out = ByteArrayOutputStream(minOf(keep, 8192))
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (total < keep) out.write(buffer, 0, minOf(count, keep - total))
                total += count
                if (total > MAX_STREAM) throw IOException("Output helper terlalu besar")
            }
            return out.toByteArray()
        }
    }

    companion object {
        internal const val HELPER_NAME = "libwuwa_root_helper.so"
        internal const val MAX_FIELD = 1024 * 1024
        internal const val MAX_FIELDS = 2
        internal const val MAX_FRAME = 8 * 1024 * 1024
        internal const val MAX_PAYLOAD = 16 * 1024 * 1024
        internal const val MAX_RESPONSE = MAX_PAYLOAD + 16
        private const val MAX_STDERR = 8 * 1024
        private const val MAX_STREAM = 32 * 1024 * 1024
        private const val MAX_ERROR = 512
        private const val METADATA_TIMEOUT = 10L
        private const val LONG_TIMEOUT = 120L

        internal fun decodeUtf8(bytes: ByteArray): String =
            try {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                throw IOException("Payload UTF-8 tidak valid")
            }

        internal fun decodeStringList(bytes: ByteArray): List<String> {
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            if (input.remaining() < 4) throw IOException("Daftar helper terpotong")
            val count = input.int
            if (count !in 0..100_000) throw IOException("Jumlah entry tidak valid")
            return List(count) {
                if (input.remaining() < 4) throw IOException("Daftar helper terpotong")
                val size = input.int
                if (size !in 0..MAX_FIELD || size > input.remaining()) throw IOException("Entry helper tidak valid")
                ByteArray(size).also(input::get).let(::decodeUtf8)
            }.also { if (input.hasRemaining()) throw IOException("Data tambahan pada daftar helper") }
        }
    }
}
