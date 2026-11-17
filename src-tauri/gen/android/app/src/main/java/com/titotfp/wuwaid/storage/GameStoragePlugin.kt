package com.titotfp.wuwaid.storage

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import android.system.Os
import android.system.OsConstants
import androidx.appcompat.app.AppCompatActivity
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.Plugin
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import rikka.shizuku.Shizuku

@InvokeArg
class StorageArgs {
  var operation: String? = null
  var path: String? = null
  var source: String? = null
  var target: String? = null
  var content: String? = null
  var algorithm: String? = null
  var paths: List<String>? = null
  var requestId: Long = 0
}

@InvokeArg
class ControlArgs {
  var action: String? = null
  var requestId: Long = 0
}

@InvokeArg
class ProbeArgs {
  var requestId: Long = 0
}

@TauriPlugin
class GameStoragePlugin(private val activity: Activity) : Plugin(activity) {
  private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "wuwaid-storage").apply { isDaemon = true }
  }
  private val serviceLock = Any()
  private val destroyed = AtomicBoolean(false)
  private val rootSession = RootSession(activity)
  private val axManagerSession = AxManagerSession(activity)
  private val accessPreferences = activity.getSharedPreferences("storage-access", Activity.MODE_PRIVATE)
  private var accessMethod = loadStorageAccess(activity, accessPreferences)
  @Volatile private var selectedGamePackage: String? = null
  @Volatile private var userService: IStorageUserService? = null
  private var serviceBinding = false
  private var serviceReady: CountDownLatch? = null
  private var requestId = 0L
  private var permissionRequestId = 0L
  // Increment when privileged storage code changes so Shizuku recreates stale services.
  private val userServiceArgs = Shizuku.UserServiceArgs(
    ComponentName(activity.packageName, StorageUserService::class.java.name)
  ).daemon(false).tag("wuwaid-storage").version(4).processNameSuffix("wuwaid-storage")
  private var serviceConnection: ServiceConnection? = null
  private fun newServiceConnection() = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
      synchronized(serviceLock) {
        if (!destroyed.get() && serviceConnection === this && serviceBinding) {
          userService = IStorageUserService.Stub.asInterface(binder)
          serviceBinding = false
          serviceReady?.countDown()
        }
      }
    }

    override fun onServiceDisconnected(name: ComponentName) {
      synchronized(serviceLock) {
        if (serviceConnection === this) {
          userService = null
          serviceBinding = false
          serviceConnection = null
          serviceReady?.countDown()
          serviceReady = null
        }
      }
    }
  }
  private val pendingPermission = AtomicReference<Invoke?>(null)
  private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
    if (requestCode == REQUEST_PERMISSION) {
      val invoke = pendingPermission.getAndSet(null) ?: return@OnRequestPermissionResultListener
      StorageTrace.mark(permissionRequestId, "permission.wait_user", "end")
      try {
        if (grantResult == PackageManager.PERMISSION_GRANTED && Shizuku.pingBinder() &&
          Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        ) {
          invoke.resolve()
        } else {
          invoke.reject("Shizuku permission was denied or is no longer available")
        }
      } catch (error: Exception) {
        invoke.reject("Unable to verify Shizuku permission: ${error.message ?: error.javaClass.simpleName}")
      }
    }
  }
  private val binderDeadListener = Shizuku.OnBinderDeadListener {
    pendingPermission.getAndSet(null)?.reject("Shizuku stopped while permission was being requested; start Shizuku and try again")
    StorageTrace.mark(permissionRequestId, "permission.wait_user", "cancel")
    synchronized(serviceLock) {
      userService = null
      serviceBinding = false
      serviceConnection = null
      serviceReady?.countDown()
      serviceReady = null
    }
  }

  init {
    Shizuku.addRequestPermissionResultListener(permissionListener)
    Shizuku.addBinderDeadListener(binderDeadListener)
  }

  @Command
  fun probe(invoke: Invoke) {
    val args = invoke.parseArgs(ProbeArgs::class.java)
    dispatch(invoke) {
      requestId = args.requestId
      StorageTrace.phase(requestId, "probe") { probeDevice() }
    }
  }

  @Command
  fun control(invoke: Invoke) {
    val args = try {
      invoke.parseArgs(ControlArgs::class.java)
    } catch (error: Exception) {
      invoke.reject("Invalid control request: ${error.message ?: error.javaClass.simpleName}")
      return
    }

    when (args.action) {
      "request_permission" -> requestPermission(invoke, args.requestId)
      "launch_game" -> runOnUi(invoke) {
        launchPackage(selectedGamePackage ?: GamePackages.supported.firstOrNull { installedGameVersion(it) != null } ?: GAME_PACKAGE)
      }
      "request_root" -> dispatch(invoke) {
        check(accessMethod == "root") { "Pilih metode Root terlebih dahulu." }
        rootSession.request(args.requestId)
        null
      }
      "request_axmanager" -> dispatch(invoke) {
        check(accessMethod == "axmanager") { "Pilih metode AxManager terlebih dahulu." }
        axManagerSession.request(args.requestId)
        null
      }
      "use_root", "use_shizuku", "use_axmanager" -> dispatch(invoke) {
        if (accessMethod == "axmanager") axManagerSession.disconnect()
        accessMethod = when (args.action) {
          "use_root" -> "root"
          "use_axmanager" -> "axmanager"
          else -> "shizuku"
        }
        accessPreferences.edit().putString("method", accessMethod).apply()
        null
      }
      "open_shizuku" -> runOnUi(invoke) { launchPackage(SHIZUKU_PACKAGE) }
      "open_axmanager" -> runOnUi(invoke) {
        val manager = axManagerSession.managerPackage()
        val intent = if (manager == null) {
          Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/fahrez182/AxManager/releases"))
        } else {
          activity.packageManager.getLaunchIntentForPackage(manager)
            ?: throw IllegalStateException("AxManager tidak dapat dibuka.")
        }
        activity.startActivity(intent)
      }
      "open_store" -> runOnUi(invoke) { openStore() }
      else -> invoke.reject("Unsupported app control action")
    }
  }

  @Command
  fun storage(invoke: Invoke) {
    val args = try {
      invoke.parseArgs(StorageArgs::class.java)
    } catch (error: Exception) {
      invoke.reject("Invalid storage request: ${error.message ?: error.javaClass.simpleName}")
      return
    }
    dispatch(invoke) {
      requestId = args.requestId
      storageOperation(args)
    }
  }

  override fun onDestroy(activity: AppCompatActivity) {
    if (!destroyed.compareAndSet(false, true)) return
    pendingPermission.getAndSet(null)?.reject("Android storage plugin was destroyed")
    StorageTrace.mark(permissionRequestId, "permission.wait_user", "cancel")
    rootSession.close()
    axManagerSession.close()
    Shizuku.removeRequestPermissionResultListener(permissionListener)
    Shizuku.removeBinderDeadListener(binderDeadListener)
    val connection = synchronized(serviceLock) {
      val connection = serviceConnection
      serviceConnection = null
      serviceBinding = false
      userService = null
      serviceReady?.countDown()
      serviceReady = null
      connection
    }
    if (connection != null) {
      try {
        Shizuku.unbindUserService(userServiceArgs, connection, true)
      } catch (_: Exception) {
      }
    }
    ioExecutor.shutdownNow()
  }

  private fun requestPermission(invoke: Invoke, id: Long) {
    try {
      check(accessMethod == "shizuku") { "Pilih metode Shizuku terlebih dahulu." }
      requireShizuku()
      if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
        invoke.resolve()
        return
      }
      if (Shizuku.shouldShowRequestPermissionRationale()) {
        invoke.reject("Izin ditolak. Buka Shizuku dan izinkan WuwaIDMobile di daftar aplikasi.")
        return
      }
    } catch (error: Exception) {
      invoke.reject("Unable to request Shizuku permission: ${error.message ?: error.javaClass.simpleName}")
      return
    }
    if (!pendingPermission.compareAndSet(null, invoke)) {
      invoke.reject("A Shizuku permission request is already in progress")
      return
    }
    permissionRequestId = id
    try {
      activity.runOnUiThread {
        try {
          if (pendingPermission.get() === invoke) {
            requireShizuku()
            StorageTrace.mark(permissionRequestId, "permission.wait_user", "begin")
            Shizuku.requestPermission(REQUEST_PERMISSION)
          }
        } catch (error: Exception) {
          if (pendingPermission.compareAndSet(invoke, null)) {
            StorageTrace.mark(permissionRequestId, "permission.wait_user", "cancel")
            invoke.reject("Unable to request Shizuku permission: ${error.message ?: error.javaClass.simpleName}")
          }
        }
      }
    } catch (error: Exception) {
      if (pendingPermission.compareAndSet(invoke, null)) {
        StorageTrace.mark(permissionRequestId, "permission.wait_user", "cancel")
        invoke.reject("Unable to request Shizuku permission: ${error.message ?: error.javaClass.simpleName}")
      }
    }
  }

  private fun runOnUi(invoke: Invoke, action: () -> Unit) {
    activity.runOnUiThread {
      try {
        action()
        invoke.resolve()
      } catch (error: Exception) {
        invoke.reject(error.message ?: error.javaClass.simpleName)
      }
    }
  }

  private fun launchPackage(packageName: String) {
    val intent = activity.packageManager.getLaunchIntentForPackage(packageName)
      ?: throw IllegalStateException("${when (packageName) {
        in GamePackages.supported -> "Wuthering Waves"
        else -> "Shizuku"
      }} is not installed")
    activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }

  private fun openStore() {
    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GAME_PACKAGE"))
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      activity.startActivity(marketIntent)
    } catch (_: ActivityNotFoundException) {
      val webIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://play.google.com/store/apps/details?id=$GAME_PACKAGE")
      ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      activity.startActivity(webIntent)
    }
  }

  private fun dispatch(invoke: Invoke, action: () -> Any?) {
    try {
      ioExecutor.execute {
        try {
          val result = action()
          if (result == null) invoke.resolve() else invoke.resolveObject(result)
        } catch (error: Exception) {
          invoke.reject(error.message ?: error.javaClass.simpleName)
        }
      }
    } catch (error: Exception) {
      invoke.reject("Android storage worker is unavailable: ${error.message ?: error.javaClass.simpleName}")
    }
  }

  private fun probeDevice(): Map<String, Any?> {
    val installed = GamePackages.supported.mapNotNull { packageName ->
      installedGameVersion(packageName)?.let { packageName to it }
    }.toMap()
    var gamePackage = selectedGamePackage ?: installed.keys.firstOrNull() ?: GAME_PACKAGE
    val shizukuRunning = try {
      Shizuku.pingBinder()
    } catch (_: Exception) {
      false
    }
    val shizukuGranted = if (shizukuRunning) {
      try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
      } catch (_: Exception) {
        false
      }
    } else {
      false
    }

    var rootReadable = false
    var rootWritable = false
    var storageError: String? = null
    val axmanager = axManagerSession.status(checkServer = accessMethod == "axmanager")
    val accessGranted = when (accessMethod) {
      "root" -> rootSession.granted()
      "shizuku" -> shizukuGranted
      "axmanager" -> axmanager["supported"] == true && axmanager["running"] == true && axManagerSession.granted()
      else -> false
    }
    if (accessGranted) {
      try {
        if (installed.isNotEmpty()) {
          val candidates = selectedGamePackage?.let { listOf(it) } ?: installed.keys.toList()
          val detected = JSONObject(metadata("detect_game", JSONArray(candidates).toString()).stdoutText()).getString("packageName")
          check(detected in installed) { "Selected game is no longer installed; reopen WuwaIDMobile." }
          selectedGamePackage = detected
          gamePackage = detected
        }
        val status = JSONObject(metadata("probe_root").stdoutText())
        rootReadable = status.getBoolean("readable")
        rootWritable = status.getBoolean("writable")
      } catch (error: Exception) {
        storageError = error.message ?: error.javaClass.simpleName
      }
    }

    return mapOf<String, Any?>(
      "gameInstalled" to installed.containsKey(gamePackage),
      "gameVersion" to (installed[gamePackage] ?: ""),
      "gamePackage" to gamePackage,
      "shizukuRunning" to shizukuRunning,
      "shizukuGranted" to shizukuGranted,
      "axmanager" to axmanager,
      "accessMethod" to accessMethod,
      "accessGranted" to accessGranted,
      "rootReadable" to rootReadable,
      "rootWritable" to rootWritable,
      "storageError" to storageError
    )
  }

  @Suppress("DEPRECATION")
  private fun installedGameVersion(packageName: String): String? = try {
    val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
      activity.packageManager.getPackageInfo(
        packageName,
        PackageManager.PackageInfoFlags.of(0L)
      )
    } else {
      activity.packageManager.getPackageInfo(packageName, 0)
    }
    info.versionName ?: "unknown"
  } catch (_: PackageManager.NameNotFoundException) {
    null
  }

  private fun storageOperation(args: StorageArgs): Any? {
    val operation = args.operation ?: throw IllegalArgumentException("Storage operation is required")
    return when (operation) {
      "list" -> jsonObjects(JSONArray(metadata("list", args.path ?: "").stdoutText()))
      "stat" -> statObject(JSONObject(metadata("stat", required(args.path, "path")).stdoutText()))
      "stat_many" -> {
        val paths = requireNotNull(args.paths) { "Stat batch is required" }
        require(paths.size <= 64) { "Stat batch exceeds 64 paths" }
        val result = JSONArray(metadata("stat_many", JSONArray(paths).toString()).stdoutText())
        require(result.length() == paths.size) { "Malformed stat batch response" }
        (0 until result.length()).map { statObject(result.getJSONObject(it)) }
      }
      "read_text" -> metadata("read_text", required(args.path, "path")).stdoutText()
      "hashes" -> {
        val result = JSONObject(metadata("hashes", required(args.path, "path"),
          required(args.algorithm, "algorithm")).stdoutText())
        val sha256 = result.getString("sha256")
        val sha1 = if (result.isNull("sha1")) null else result.getString("sha1")
        require(sha256.matches(Regex("[0-9a-f]{64}")) &&
          (sha1 == null || sha1.matches(Regex("[0-9a-f]{40}")))) { "Malformed digest response" }
        mapOf("size" to result.getLong("size"), "sha256" to sha256, "sha1" to sha1)
      }
      "mkdir" -> {
        val path = validateRelative(required(args.path, "path"), allowEmpty = false)
        requireWritableTarget(path, allowPatchRoot = true, allowMountDirectory = true, directoryOnly = true)
        runShell("mkdir", listOf(path))
        null
      }
      "copy" -> {
        val source = validateRelative(required(args.source, "source"), allowEmpty = false)
        val target = validateRelative(required(args.target, "target"), allowEmpty = false)
        requireWritableTarget(target, allowPatchRoot = false)
        runShell("copy", listOf(source, target))
        null
      }
      "move_file" -> {
        val source = validateRelative(required(args.source, "source"), allowEmpty = false)
        val target = validateRelative(required(args.target, "target"), allowEmpty = false)
        requireWritableTarget(source, allowPatchRoot = false)
        requireWritableTarget(target, allowPatchRoot = false)
        runShell("move_file", listOf(source, target))
        null
      }
      "remove" -> {
        val path = validateRelative(required(args.path, "path"), allowEmpty = false)
        requireWritableTarget(path, allowPatchRoot = true)
        runShell("remove", listOf(path))
        null
      }
      "write_text" -> {
        val path = validateRelative(required(args.path, "path"), allowEmpty = false)
        requireWritableTarget(path, allowPatchRoot = false)
        val content = required(args.content, "content").toByteArray(StandardCharsets.UTF_8)
        if (content.size > MAX_TEXT_BYTES) throw IllegalArgumentException("Text write exceeds the 256 KiB limit")
        runShell("write", listOf(path), ByteArrayInputStream(content), content.size.toLong())
        null
      }
      "write_from_file" -> {
        val path = validateRelative(required(args.path, "path"), allowEmpty = false)
        requireWritableTarget(path, allowPatchRoot = false)
        val source = cacheFile(required(args.source, "source"))
        source.inputStream().use { runShell("write", listOf(path), it, source.length()) }
        null
      }
      "force_stop" -> {
        runShell("force_stop")
        null
      }
      else -> throw IllegalArgumentException("Unsupported storage operation")
    }
  }

  private fun jsonObjects(array: JSONArray): List<Map<String, Any>> =
    (0 until array.length()).map {
      val entry = array.getJSONObject(it)
      mapOf("name" to entry.getString("name"), "isDir" to entry.getBoolean("isDir"))
    }

  private fun statObject(value: JSONObject): Map<String, Any> = mapOf(
    "exists" to value.getBoolean("exists"), "isDir" to value.getBoolean("isDir"),
    "size" to value.getLong("size")
  )

  private fun metadata(operation: String, first: String? = null, second: String? = null): ShellResult =
    StorageTrace.phase(requestId, when (operation) {
      "probe_root" -> "bridge.probe_root"
      "list" -> "bridge.list"
      "stat" -> "bridge.stat"
      "stat_many" -> "bridge.stat_many"
      "read_text" -> "bridge.read_text"
      "hashes" -> "bridge.hashes"
      else -> "bridge.invalid"
    }) {
      val service = StorageTrace.phase(requestId, "bind_service") { connectedStorageService() }
      response(service.metadata(operation, first, second, requestId), MAX_METADATA_BYTES)
    }


  private fun validateRelative(path: String, allowEmpty: Boolean): String {
    if (path.length > MAX_PATH_LENGTH || path.indexOf('\u0000') >= 0 || path.startsWith('/') || path.contains('\\')) {
      throw IllegalArgumentException("Invalid resource-relative path")
    }
    if (path.isEmpty()) {
      if (allowEmpty) return path
      throw IllegalArgumentException("Path cannot be empty")
    }
    val components = path.split('/')
    if (components.any { it.isEmpty() || it == "." || it == ".." }) {
      throw IllegalArgumentException("Invalid resource-relative path")
    }
    return path
  }


  private fun cacheFile(path: String): File {
    if (path.isEmpty() || path.indexOf('\u0000') >= 0) {
      throw IllegalArgumentException("Invalid app-cache source path")
    }
    val source = File(path)
    val cacheRoot = activity.cacheDir.canonicalFile
    val canonical = source.canonicalFile
    // Android resolves the app data directory through /data/user/0, so only the
    // canonical location is compared; the payload itself must not be a symlink.
    if (canonical == cacheRoot || !canonical.path.startsWith(cacheRoot.path + File.separator) ||
      OsConstants.S_ISLNK(Os.lstat(source.path).st_mode) || !canonical.isFile
    ) {
      throw SecurityException("write_from_file source must be a regular, non-symlink file inside this app's cache directory")
    }
    return canonical
  }

  private fun required(value: String?, name: String): String =
    value ?: throw IllegalArgumentException("Storage field '$name' is required")

  private data class ShellResult(val stdout: ByteArray, val stderr: ByteArray) {
    fun stdoutText(): String = decode(stdout)

    private fun decode(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(java.nio.ByteBuffer.wrap(bytes))
      .toString()
  }

  private fun requireShizuku() {
    if (destroyed.get()) throw IllegalStateException("Android storage plugin was destroyed")
    if (!Shizuku.pingBinder()) throw IllegalStateException("Shizuku is not running; start it from the Shizuku app first")
    if (Shizuku.isPreV11() || Shizuku.getVersion() < 12) {
      throw IllegalStateException("Shizuku API 12 or newer is required; update Shizuku")
    }
  }
  private fun connectedStorageService(): IStorageUserService {
    val service = when (accessMethod) {
      "root" -> rootSession.connected()
      "shizuku" -> connectedUserService()
      "axmanager" -> axManagerSession.connected()
      else -> throw IllegalStateException("Unknown storage access method")
    }
    selectedGamePackage?.let { service.selectGame(it) }
    return service
  }


  private fun connectedUserService(): IStorageUserService {
    requireShizuku()
    if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
      throw SecurityException("Shizuku permission has not been granted")
    }

    var startBinding = false
    val connection: ServiceConnection
    val ready = synchronized(serviceLock) {
      if (destroyed.get()) throw IllegalStateException("Android storage plugin was destroyed")
      userService?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
      userService = null
      if (!serviceBinding) {
        serviceBinding = true
        serviceReady = CountDownLatch(1)
        serviceConnection = newServiceConnection()
        startBinding = true
      }
      connection = serviceConnection ?: throw IllegalStateException("Shizuku service connection is not available")
      serviceReady ?: throw IllegalStateException("Shizuku user service is not available")
    }

    if (startBinding) {
      try {
        synchronized(serviceLock) {
          if (destroyed.get() || serviceConnection !== connection) {
            throw IllegalStateException("Shizuku service binding was cancelled")
          }
          Shizuku.bindUserService(userServiceArgs, connection)
        }
      } catch (error: Exception) {
        synchronized(serviceLock) {
          if (serviceConnection === connection) {
            serviceBinding = false
            serviceConnection = null
            serviceReady?.countDown()
            serviceReady = null
          }
        }
        try {
          Shizuku.unbindUserService(userServiceArgs, connection, true)
        } catch (_: Exception) {
        }
        throw IllegalStateException("Unable to bind the Shizuku storage service: ${error.message ?: error.javaClass.simpleName}", error)
      }
    }

    if (!ready.await(SERVICE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      var shouldUnbind = false
      synchronized(serviceLock) {
        if (serviceConnection === connection) {
          userService?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
          userService = null
          shouldUnbind = true
          serviceBinding = false
          serviceConnection = null
          serviceReady = null
        }
      }
      if (shouldUnbind) {
        try {
          Shizuku.unbindUserService(userServiceArgs, connection, true)
        } catch (_: Exception) {
        }
      }
      throw IllegalStateException("Timed out waiting for the Shizuku storage service")
    }
    return synchronized(serviceLock) {
      if (serviceConnection !== connection) throw IllegalStateException("Shizuku storage service disconnected")
      userService?.takeIf { it.asBinder().isBinderAlive }
        ?: throw IllegalStateException("Shizuku storage service disconnected")
    }
  }

  private fun runShell(
    operation: String,
    arguments: List<String> = emptyList(),
    input: InputStream? = null,
    expectedInputBytes: Long? = null,
    stdoutLimit: Int = MAX_METADATA_BYTES
  ): ShellResult {
    if (arguments.size > 2) throw IllegalArgumentException("Too many storage command arguments")
    val service = StorageTrace.phase(requestId, "bind_service") { connectedStorageService() }
    val pipe = ParcelFileDescriptor.createPipe()
    val readEnd = pipe[0]
    val writeEnd = pipe[1]
    val task = FutureTask {
      try {
        service.execute(operation, arguments.getOrNull(0), arguments.getOrNull(1), readEnd, requestId)
      } finally {
        // Release the local reader too: a failed remote call must unblock the writer.
        try { readEnd.close() } catch (_: Exception) {}
      }
    }
    Thread(task, "wuwaid-user-service-call").apply { isDaemon = true; start() }
    var copiedInputBytes = 0L
    var inputFailure: Exception? = null
    try {
      ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { pipeOutput ->
        if (input != null) {
          copiedInputBytes = input.use { source -> source.copyTo(pipeOutput, STREAM_BUFFER_BYTES) }
        }
      }
    } catch (error: Exception) {
      inputFailure = error
    }

    val response = try {
      task.get()
    } catch (error: ExecutionException) {
      throw IllegalStateException(
        "Storage service call failed: ${error.cause?.message ?: error.message}",
        error.cause ?: error
      )
    } catch (error: InterruptedException) {
      Thread.currentThread().interrupt()
      throw IllegalStateException("Storage operation was interrupted", error)
    } finally {
      try { readEnd.close() } catch (_: Exception) {}
      try { writeEnd.close() } catch (_: Exception) {}
    }

    inputFailure?.let { throw IllegalStateException("Unable to stream the app-cache source to the storage service", it) }
    if (expectedInputBytes != null && copiedInputBytes != expectedInputBytes) {
      throw IllegalStateException("App-cache source changed while it was being streamed")
    }
    return response(response, stdoutLimit)
  }

  private fun response(response: android.os.Bundle, stdoutLimit: Int): ShellResult {
    val stdout = response.getByteArray(KEY_STDOUT) ?: byteArrayOf()
    val stderr = response.getByteArray(KEY_STDERR) ?: byteArrayOf()
    if (stdout.size > stdoutLimit) throw IllegalStateException("Storage metadata response exceeded its limit")
    if (stderr.size > MAX_ERROR_BYTES) throw IllegalStateException("Storage error output exceeded its limit")
    val exitCode = response.getInt(KEY_EXIT_CODE, 255)
    if (exitCode != 0) {
      val detail = String(stderr, StandardCharsets.UTF_8).trim().take(1024)
      throw IllegalStateException(
        if (detail.isEmpty()) "Storage operation failed (exit $exitCode)" else "Storage operation failed: $detail"
      )
    }
    return ShellResult(stdout, stderr)
  }

  companion object {
    private const val REQUEST_PERMISSION = 7301
    private const val GAME_PACKAGE = "com.kurogame.wutheringwaves.global"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val MAX_PATH_LENGTH = 4096
    private const val MAX_TEXT_BYTES = 256 * 1024
    private const val MAX_METADATA_BYTES = 256 * 1024
    private const val MAX_ERROR_BYTES = 16 * 1024
    private const val STREAM_BUFFER_BYTES = 64 * 1024
    private const val SERVICE_CONNECT_TIMEOUT_SECONDS = 15L
    private const val KEY_EXIT_CODE = "exitCode"
    private const val KEY_STDOUT = "stdout"
    private const val KEY_STDERR = "stderr"
  }
}
