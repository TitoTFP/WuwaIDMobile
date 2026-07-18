package com.titotfp.wuwaid

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

class ShizukuFileClient(
    context: Context,
    private val onStateChanged: () -> Unit,
) : PrivilegedFiles {
    private val appContext = context.applicationContext
    private var service: IFileService? = null
    private var binding = false
    private var localError = ""

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IFileService.Stub.asInterface(binder)
            binding = false
            localError = ""
            onStateChanged()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            onStateChanged()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (hasPermission()) bind()
        onStateChanged()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        service = null
        binding = false
        onStateChanged()
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) bind()
        onStateChanged()
    }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        if (isAvailable() && hasPermission()) bind()
    }

    fun stop() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        if (service != null || binding) {
            runCatching { Shizuku.unbindUserService(userServiceArgs(), connection, true) }
        }
        service = null
        binding = false
    }

    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isReady(): Boolean = runCatching { service?.asBinder()?.pingBinder() == true }.getOrDefault(false)

    fun isBinding(): Boolean = binding

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure { localError = it.message ?: it.javaClass.simpleName }
    }

    fun bind() {
        if (!isAvailable() || !hasPermission() || isReady() || binding) return
        binding = true
        runCatching { Shizuku.bindUserService(userServiceArgs(), connection) }
            .onFailure {
                binding = false
                localError = it.message ?: it.javaClass.simpleName
                onStateChanged()
            }
    }

    override fun copyFile(source: String, destination: String): Boolean = call(false) {
        it.copyFile(source, destination)
    }

    override fun replaceFile(source: String, destination: String): Boolean = call(false) {
        it.replaceFile(source, destination)
    }

    override fun deleteFile(path: String): Boolean = call(false) { it.deleteFile(path) }
    override fun exists(path: String): Boolean = call(false) { it.exists(path) }
    override fun mkdirs(path: String): Boolean = call(false) { it.mkdirs(path) }
    override fun listFiles(path: String): Array<String> = call(emptyArray()) { it.listFiles(path) }
    override fun readText(path: String): String = call("") { it.readText(path) }
    override fun writeTextAtomic(path: String, content: String): Boolean = call(false) {
        it.writeTextAtomic(path, content)
    }

    override fun sha1(path: String): String = call("") { it.sha1(path) }
    override fun sha256(path: String): String = call("") { it.sha256(path) }
    override fun lastError(): String = localError.ifBlank { call("") { it.lastError() } }

    private fun userServiceArgs(): Shizuku.UserServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext.packageName, FileService::class.java.name),
    ).daemon(false)
        .processNameSuffix("file")
        .debuggable(BuildConfig.DEBUG)
        .version(1)

    private fun <T> call(fallback: T, block: (IFileService) -> T): T {
        val current = service ?: return fallback
        return try {
            block(current).also { localError = "" }
        } catch (error: Throwable) {
            localError = error.message ?: error.javaClass.simpleName
            fallback
        }
    }

    companion object {
        private const val REQUEST_CODE = 1001
        const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    }
}
