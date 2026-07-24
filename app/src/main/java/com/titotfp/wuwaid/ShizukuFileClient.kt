package com.titotfp.wuwaid

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import rikka.shizuku.Shizuku

class ShizukuFileClient(
    context: Context,
    private val onStateChanged: () -> Unit,
) : PrivilegedFiles {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var service: IFileService? = null

    @Volatile
    private var binding = false

    @Volatile
    private var localError = ""

    private var started = false
    private var bindGeneration = 0
    private var consecutiveFailures = 0
    private var retryNotBefore = 0L

    private val reconnectRunnable = Runnable { bind() }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!started) return

            val connected = runCatching {
                val validBinder = requireNotNull(binder) { "Binder UserService kosong" }
                check(validBinder.pingBinder()) { "Binder UserService tidak aktif" }
                checkNotNull(IFileService.Stub.asInterface(validBinder)) {
                    "Interface UserService tidak tersedia"
                }
            }.getOrElse { error ->
                failBinding("UserService tidak valid: ${errorMessage(error)}")
                return
            }

            service = connected
            binding = false
            consecutiveFailures = 0
            retryNotBefore = 0L
            localError = ""
            mainHandler.removeCallbacks(reconnectRunnable)
            onStateChanged()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            if (!started || (service == null && !binding)) return
            failBinding("UserService terputus", unbind = false)
        }

        override fun onBindingDied(name: ComponentName?) {
            if (!started || (service == null && !binding)) return
            failBinding("Binding UserService berhenti")
        }

        override fun onNullBinding(name: ComponentName?) {
            if (!started || (service == null && !binding)) return
            failBinding("UserService tidak mengembalikan binder")
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (hasPermission()) bind()
        onStateChanged()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        bindGeneration++
        mainHandler.removeCallbacks(reconnectRunnable)
        service = null
        binding = false
        consecutiveFailures = 0
        retryNotBefore = 0L
        localError = "Layanan Shizuku terputus"
        onStateChanged()
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
            consecutiveFailures = 0
            retryNotBefore = 0L
            bind()
        }
        onStateChanged()
    }

    fun start() {
        if (started) return
        started = true
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        if (isAvailable() && hasPermission()) bind()
    }

    fun stop() {
        if (!started) return
        started = false
        bindGeneration++
        mainHandler.removeCallbacks(reconnectRunnable)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        if (service != null || binding) {
            runCatching { Shizuku.unbindUserService(userServiceArgs(), connection, true) }
        }
        service = null
        binding = false
        consecutiveFailures = 0
        retryNotBefore = 0L
    }

    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isReady(): Boolean = runCatching { service?.asBinder()?.pingBinder() == true }.getOrDefault(false)

    fun isBinding(): Boolean = binding

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure {
                localError = errorMessage(it)
                onStateChanged()
            }
    }

    fun bind() {
        if (!started || !isAvailable() || !hasPermission() || isReady() || binding) return

        binding = true
        val generation = ++bindGeneration
        val now = SystemClock.elapsedRealtime()
        val retryDelay = (retryNotBefore - now).coerceAtLeast(0L)
        val delay = maxOf(INITIAL_BIND_DELAY_MS, retryDelay)

        mainHandler.postDelayed({
            if (!started || generation != bindGeneration || !binding || isReady()) return@postDelayed

            if (!isAvailable() || !hasPermission()) {
                binding = false
                onStateChanged()
                return@postDelayed
            }

            localError = ""
            runCatching { Shizuku.bindUserService(userServiceArgs(), connection) }
                .onSuccess { scheduleBindTimeout(generation) }
                .onFailure { failBinding("Gagal memulai UserService: ${errorMessage(it)}") }
        }, delay)
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

    private fun scheduleBindTimeout(generation: Int) {
        mainHandler.postDelayed({
            if (!started || generation != bindGeneration || !binding || isReady()) return@postDelayed
            failBinding("UserService tidak merespons dalam ${BIND_TIMEOUT_MS / 1_000} detik")
        }, BIND_TIMEOUT_MS)
    }

    private fun failBinding(message: String, unbind: Boolean = true) {
        if (!started) return

        bindGeneration++
        service = null
        binding = false
        localError = message

        if (unbind) {
            runCatching { Shizuku.unbindUserService(userServiceArgs(), connection, true) }
        }

        scheduleReconnect()
        onStateChanged()
    }

    private fun scheduleReconnect() {
        if (!started || !isAvailable() || !hasPermission() || isReady()) return

        val index = consecutiveFailures.coerceAtMost(RETRY_DELAYS_MS.lastIndex)
        val delay = RETRY_DELAYS_MS[index]
        consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(RETRY_DELAYS_MS.size)
        retryNotBefore = SystemClock.elapsedRealtime() + delay

        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, delay)
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext.packageName, FileService::class.java.name),
    ).daemon(false)
        .processNameSuffix("file")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private fun <T> call(fallback: T, block: (IFileService) -> T): T {
        val current = service ?: return fallback
        return try {
            block(current).also { localError = "" }
        } catch (error: Throwable) {
            localError = errorMessage(error)
            fallback
        }
    }

    private fun errorMessage(error: Throwable): String = error.message ?: error.javaClass.simpleName

    companion object {
        private const val REQUEST_CODE = 1001
        private const val INITIAL_BIND_DELAY_MS = 300L
        private const val BIND_TIMEOUT_MS = 10_000L
        private val RETRY_DELAYS_MS = longArrayOf(500L, 1_500L, 4_000L, 8_000L)
        const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    }
}
