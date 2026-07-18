package com.titotfp.wuwaid

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class LocalFileEngine(allowedRoots: List<File>) {
    private val roots = allowedRoots.map { it.canonicalFile }

    fun isAllowedPath(path: String): Boolean = runCatching { checked(path); true }.getOrDefault(false)

    fun copyFile(srcPath: String, destPath: String): Boolean {
        val source = checked(srcPath)
        val destination = checked(destPath)
        require(source.isFile) { "File sumber tidak ada" }
        destination.parentFile?.mkdirs()
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output, BUFFER_SIZE)
                output.fd.sync()
            }
        }
        return destination.isFile && destination.length() == source.length()
    }

    fun replaceFile(srcPath: String, destPath: String): Boolean {
        val source = checked(srcPath)
        val destination = checked(destPath)
        require(source.isFile) { "File sementara tidak ada" }
        destination.parentFile?.mkdirs()
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return destination.isFile
    }

    fun deleteFile(path: String): Boolean {
        val file = checked(path)
        return !file.exists() || file.delete()
    }

    fun exists(path: String): Boolean = checked(path).exists()

    fun mkdirs(path: String): Boolean {
        val directory = checked(path)
        return directory.isDirectory || directory.mkdirs()
    }

    fun listFiles(path: String): Array<String> = checked(path)
        .listFiles()
        ?.map(File::getName)
        ?.sorted()
        ?.toTypedArray()
        ?: emptyArray()

    fun readText(path: String): String {
        val file = checked(path)
        return if (file.isFile) file.readText(Charsets.UTF_8) else ""
    }

    fun writeTextAtomic(path: String, content: String): Boolean {
        val destination = checked(path)
        destination.parentFile?.mkdirs()
        val temporary = checked("${destination.path}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        return replaceFile(temporary.path, destination.path)
    }

    fun hash(path: String, algorithm: String): String {
        val file = checked(path)
        require(file.isFile) { "File untuk hash tidak ada" }
        val digest = MessageDigest.getInstance(algorithm)
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun checked(path: String): File {
        require(path.isNotBlank()) { "Path kosong" }
        val file = File(path).canonicalFile
        val allowed = roots.any { root -> file == root || file.path.startsWith(root.path + File.separator) }
        require(allowed) { "Path di luar allowlist: ${file.path}" }
        return file
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
    }
}
