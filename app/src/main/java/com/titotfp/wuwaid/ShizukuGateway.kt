package com.titotfp.wuwaid

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

internal interface UserServiceFiles : PrivilegedFiles {
    fun isAlive(): Boolean
}

internal interface ShizukuGateway {
    interface Listener {
        fun onBinderReceived()
        fun onBinderDead()
        fun onPermissionResult(requestCode: Int, granted: Boolean)
        fun onServiceConnected(service: UserServiceFiles?)
        fun onServiceDisconnected()
        fun onBindingDied()
        fun onNullBinding()
    }

    fun start(listener: Listener)
    fun stop()
    fun isAvailable(): Boolean
    fun hasPermission(): Boolean
    fun requestPermission(requestCode: Int)
    fun bindUserService()
    fun unbindUserService(remove: Boolean)
}

internal class AndroidShizukuGateway(
    context: Context,
) : ShizukuGateway {
    private val appContext = context.applicationContext
    private var listener: ShizukuGateway.Listener? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = runCatching {
                val validBinder = requireNotNull(binder) { "Binder UserService kosong" }
                check(validBinder.pingBinder()) { "Binder UserService tidak aktif" }
                val remote = checkNotNull(IFileService.Stub.asInterface(validBinder)) {
                    "Interface UserService tidak tersedia"
                }
                RemoteUserServiceFiles(remote)
            }.getOrNull()
            listener?.onServiceConnected(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            listener?.onServiceDisconnected()
        }

        override fun onBindingDied(name: ComponentName?) {
            listener?.onBindingDied()
        }

        override fun onNullBinding(name: ComponentName?) {
            listener?.onNullBinding()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        listener?.onBinderReceived()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        listener?.onBinderDead()
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        listener?.onPermissionResult(
            requestCode = requestCode,
            granted = grantResult == PackageManager.PERMISSION_GRANTED,
        )
    }

    override fun start(listener: ShizukuGateway.Listener) {
        check(this.listener == null) { "ShizukuGateway sudah dimulai" }
        this.listener = listener
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    override fun stop() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        listener = null
    }

    override fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    override fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    override fun requestPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
    }

    override fun bindUserService() {
        Shizuku.bindUserService(userServiceArgs(), connection)
    }

    override fun unbindUserService(remove: Boolean) {
        Shizuku.unbindUserService(userServiceArgs(), connection, remove)
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext.packageName, FileService::class.java.name),
    ).daemon(false)
        .processNameSuffix("file")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)
}

private class RemoteUserServiceFiles(
    private val remote: IFileService,
) : UserServiceFiles {
    override fun isAlive(): Boolean = runCatching {
        remote.asBinder().pingBinder()
    }.getOrDefault(false)

    override fun copyFile(source: String, destination: String): Boolean =
        remote.copyFile(source, destination)

    override fun replaceFile(source: String, destination: String): Boolean =
        remote.replaceFile(source, destination)

    override fun deleteFile(path: String): Boolean = remote.deleteFile(path)
    override fun exists(path: String): Boolean = remote.exists(path)
    override fun mkdirs(path: String): Boolean = remote.mkdirs(path)
    override fun listFiles(path: String): Array<String> = remote.listFiles(path)
    override fun readText(path: String): String = remote.readText(path)

    override fun writeTextAtomic(path: String, content: String): Boolean =
        remote.writeTextAtomic(path, content)

    override fun sha1(path: String): String = remote.sha1(path)
    override fun sha256(path: String): String = remote.sha256(path)
    override fun lastError(): String = remote.lastError()
}
