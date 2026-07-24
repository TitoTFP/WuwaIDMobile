package com.titotfp.wuwaid

import android.content.Context

class ShizukuFileClient internal constructor(
    private val gateway: ShizukuGateway,
    private val scheduler: TaskScheduler,
    private val onStateChanged: () -> Unit,
) : PrivilegedFiles, ShizukuGateway.Listener {
    constructor(
        context: Context,
        onStateChanged: () -> Unit,
    ) : this(
        gateway = AndroidShizukuGateway(context),
        scheduler = HandlerTaskScheduler(),
        onStateChanged = onStateChanged,
    )

    @Volatile
    private var service: UserServiceFiles? = null

    @Volatile
    private var binding = false

    @Volatile
    private var localError = ""

    private var started = false
    private var nextAttemptId = 0
    private var activeAttemptId: Int? = null
    private var consecutiveFailures = 0
    private var pendingBind: ScheduledTask? = null
    private var pendingTimeout: ScheduledTask? = null

    override fun onBinderReceived() {
        if (!started) return
        consecutiveFailures = 0
        if (hasPermission()) bind()
        onStateChanged()
    }

    override fun onBinderDead() {
        if (!started) return
        cancelPendingTasks()
        activeAttemptId = null
        service = null
        binding = false
        consecutiveFailures = 0
        localError = "Layanan Shizuku terputus"
        onStateChanged()
    }

    override fun onPermissionResult(requestCode: Int, granted: Boolean) {
        if (!started || requestCode != REQUEST_CODE) return
        if (granted) {
            consecutiveFailures = 0
            localError = ""
            bind()
        } else {
            localError = "Izin Shizuku ditolak"
        }
        onStateChanged()
    }

    override fun onServiceConnected(attemptId: Int, service: UserServiceFiles?) {
        if (!started || attemptId != activeAttemptId) {
            runCatching { gateway.unbindUserService(attemptId, true) }
            return
        }

        pendingTimeout?.cancel()
        pendingTimeout = null

        if (service == null || !service.isAlive()) {
            failBinding(attemptId, "UserService tidak mengembalikan binder yang aktif")
            return
        }

        this.service = service
        binding = false
        consecutiveFailures = 0
        localError = ""
        onStateChanged()
    }

    override fun onServiceDisconnected(attemptId: Int) {
        if (!started || attemptId != activeAttemptId) return
        failBinding(attemptId, "UserService terputus", unbind = false)
    }

    override fun onBindingDied(attemptId: Int) {
        if (!started || attemptId != activeAttemptId) return
        failBinding(attemptId, "Binding UserService berhenti", unbind = false)
    }

    override fun onNullBinding(attemptId: Int) {
        if (!started || attemptId != activeAttemptId) return
        failBinding(attemptId, "UserService tidak mengembalikan binder", unbind = false)
    }

    fun start() {
        if (started) return
        started = true
        gateway.start(this)
        if (isAvailable() && hasPermission()) bind()
    }

    fun stop() {
        if (!started) return
        started = false
        cancelPendingTasks()
        activeAttemptId?.let { attemptId ->
            runCatching { gateway.unbindUserService(attemptId, true) }
        }
        gateway.stop()
        activeAttemptId = null
        service = null
        binding = false
        consecutiveFailures = 0
    }

    fun isAvailable(): Boolean = gateway.isAvailable()

    fun hasPermission(): Boolean = gateway.hasPermission()

    fun isReady(): Boolean = service?.isAlive() == true

    fun isBinding(): Boolean = binding

    fun requestPermission() {
        runCatching { gateway.requestPermission(REQUEST_CODE) }
            .onFailure {
                localError = errorMessage(it)
                onStateChanged()
            }
    }

    fun bind() {
        if (!started || !isAvailable() || !hasPermission() || isReady() || binding) return
        scheduleBind(INITIAL_BIND_DELAY_MS)
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

    private fun scheduleBind(delayMillis: Long) {
        service = null
        binding = true
        val attemptId = ++nextAttemptId
        activeAttemptId = attemptId
        pendingBind?.cancel()
        pendingTimeout?.cancel()
        pendingTimeout = null

        pendingBind = scheduler.schedule(delayMillis) {
            pendingBind = null
            if (!started || attemptId != activeAttemptId || !binding || isReady()) return@schedule

            if (!isAvailable() || !hasPermission()) {
                activeAttemptId = null
                binding = false
                onStateChanged()
                return@schedule
            }

            localError = ""
            runCatching { gateway.bindUserService(attemptId) }
                .onSuccess {
                    if (started && attemptId == activeAttemptId && binding && !isReady()) {
                        scheduleBindTimeout(attemptId)
                    }
                }
                .onFailure {
                    failBinding(attemptId, "Gagal memulai UserService: ${errorMessage(it)}")
                }
        }
    }

    private fun scheduleBindTimeout(attemptId: Int) {
        pendingTimeout?.cancel()
        pendingTimeout = scheduler.schedule(BIND_TIMEOUT_MS) {
            pendingTimeout = null
            if (!started || attemptId != activeAttemptId || !binding || isReady()) return@schedule
            failBinding(
                attemptId = attemptId,
                message = "UserService tidak merespons dalam ${BIND_TIMEOUT_MS / 1_000} detik",
            )
        }
    }

    private fun failBinding(attemptId: Int, message: String, unbind: Boolean = true) {
        if (!started || attemptId != activeAttemptId) return

        cancelPendingTasks()
        activeAttemptId = null
        service = null
        binding = false
        localError = message

        if (unbind) {
            runCatching { gateway.unbindUserService(attemptId, true) }
        }

        scheduleReconnect()
        onStateChanged()
    }

    private fun scheduleReconnect() {
        if (!started || !isAvailable() || !hasPermission() || isReady()) return

        val index = consecutiveFailures.coerceAtMost(RETRY_DELAYS_MS.lastIndex)
        val delay = RETRY_DELAYS_MS[index]
        consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(RETRY_DELAYS_MS.size)
        scheduleBind(delay)
    }

    private fun cancelPendingTasks() {
        pendingBind?.cancel()
        pendingTimeout?.cancel()
        pendingBind = null
        pendingTimeout = null
    }

    private fun <T> call(fallback: T, block: (UserServiceFiles) -> T): T {
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
        internal const val INITIAL_BIND_DELAY_MS = 300L
        internal const val BIND_TIMEOUT_MS = 10_000L
        internal val RETRY_DELAYS_MS = longArrayOf(500L, 1_500L, 4_000L, 8_000L)
        const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    }
}
