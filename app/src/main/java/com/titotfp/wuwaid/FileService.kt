package com.titotfp.wuwaid

import java.io.File
import kotlin.system.exitProcess

class FileService : IFileService.Stub() {
    private val engine = LocalFileEngine(
        listOf(
            File(APP_EXTERNAL_ROOT),
            File(GamePaths.GAME_ROOT),
        ),
    )
    private var errorMessage = ""

    override fun copyFile(srcPath: String, destPath: String): Boolean = action {
        engine.copyFile(srcPath, destPath)
    }

    override fun replaceFile(srcPath: String, destPath: String): Boolean = action {
        engine.replaceFile(srcPath, destPath)
    }

    override fun deleteFile(path: String): Boolean = action { engine.deleteFile(path) }
    override fun exists(path: String): Boolean = action { engine.exists(path) }
    override fun mkdirs(path: String): Boolean = action { engine.mkdirs(path) }

    override fun listFiles(path: String): Array<String> = value(emptyArray()) { engine.listFiles(path) }
    override fun readText(path: String): String = value("") { engine.readText(path) }
    override fun writeTextAtomic(path: String, content: String): Boolean = action {
        engine.writeTextAtomic(path, content)
    }

    override fun sha1(path: String): String = value("") { engine.hash(path, "SHA-1") }
    override fun sha256(path: String): String = value("") { engine.hash(path, "SHA-256") }
    override fun lastError(): String = errorMessage

    override fun destroy() {
        exitProcess(0)
    }

    private fun action(block: () -> Boolean): Boolean = try {
        block().also { if (it) errorMessage = "" }
    } catch (error: Throwable) {
        errorMessage = error.message ?: error.javaClass.simpleName
        false
    }

    private fun <T> value(fallback: T, block: () -> T): T = try {
        block().also { errorMessage = "" }
    } catch (error: Throwable) {
        errorMessage = error.message ?: error.javaClass.simpleName
        fallback
    }

    companion object {
        val APP_EXTERNAL_ROOT = "/storage/emulated/0/Android/data/${BuildConfig.APPLICATION_ID}/files"
    }
}
