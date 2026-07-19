package com.titotfp.wuwaid

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import com.titotfp.wuwaid.databinding.ActivityMainBinding
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var shizuku: ShizukuFileClient
    private lateinit var releaseStore: ReleaseStore
    private val executor = Executors.newSingleThreadExecutor()
    private val releaseClient = GitHubReleaseClient()
    private val refreshGeneration = AtomicInteger()

    @Volatile
    private var latestRelease: PatchRelease? = null

    @Volatile
    private var releaseVerifiedOnline = false

    @Volatile
    private var latestAppRelease: AppRelease? = null

    @Volatile
    private var appUpdateChecked = false

    @Volatile
    private var busy = false

    private var currentStatus = LauncherStatus.ERROR
    private var canUninstall = false
    private var diagnostics = "Belum diperiksa"
    private var firstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        releaseStore = ReleaseStore(this)
        latestRelease = releaseStore.load()
        shizuku = ShizukuFileClient(this) {
            runOnUiThread { refresh(fetchNetwork = false) }
        }

        binding.primaryButton.setOnClickListener { performPrimaryAction() }
        binding.refreshButton.setOnClickListener { refresh(fetchNetwork = true) }
        binding.uninstallButton.setOnClickListener { confirmUninstall() }
        binding.appUpdateButton.setOnClickListener { confirmAppUpdate() }

        renderRelease(latestRelease, latestRelease?.let { "Cache rilis terakhir" })
        renderAppUpdate(null, checked = false, warning = null)
        shizuku.start()
        refresh(fetchNetwork = true)
    }

    override fun onResume() {
        super.onResume()
        if (firstResume) {
            firstResume = false
        } else {
            refresh(fetchNetwork = false)
        }
    }

    override fun onDestroy() {
        refreshGeneration.incrementAndGet()
        shizuku.stop()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun refresh(fetchNetwork: Boolean) {
        if (busy || executor.isShutdown) return
        val generation = refreshGeneration.incrementAndGet()
        binding.refreshButton.isEnabled = false
        executor.execute {
            var release = latestRelease ?: releaseStore.load()
            var networkMessage = ""
            var appUpdate = latestAppRelease
            var appUpdateMessage = ""
            var updateChecked = appUpdateChecked
            if (fetchNetwork) {
                try {
                    release = releaseClient.fetchLatest()
                    latestRelease = release
                    releaseVerifiedOnline = true
                    releaseStore.save(release)
                } catch (error: Throwable) {
                    networkMessage = "GitHub: ${readableError(error)}"
                }
                try {
                    appUpdate = releaseClient.fetchLatestAppUpdate(BuildConfig.VERSION_NAME)
                    latestAppRelease = appUpdate
                    appUpdateChecked = true
                    updateChecked = true
                    if (appUpdate == null) clearAppUpdateCache()
                } catch (error: Throwable) {
                    appUpdateMessage = "Update aplikasi: ${readableError(error)}"
                }
            }

            val available = shizuku.isAvailable()
            val permission = available && shizuku.hasPermission()
            val serviceReady = permission && shizuku.isReady()
            if (permission && !serviceReady) runOnUiThread { shizuku.bind() }

            val inspection = if (serviceReady) {
                runCatching { GamePaths(shizuku).inspect(release) }.getOrElse { error ->
                    if (generation == refreshGeneration.get()) {
                        runOnUiThread { renderError(readableError(error)) }
                    }
                    return@execute
                }
            } else {
                null
            }

            val inputs = StateInputs(
                shizukuAvailable = available,
                shizukuPermission = permission,
                serviceReady = serviceReady,
                resourceVersion = inspection?.resourceVersion,
                conflicts = inspection?.conflicts.orEmpty(),
                anyOwnedPatch = inspection?.anyOwnedPatch == true,
                currentHealthy = inspection?.currentHealthy == true,
                releaseAvailable = release != null,
                matchesLatest = inspection?.matchesLatest == true,
            )
            val state = LauncherStateResolver.resolve(inputs)
            val diagnosticLines = buildList {
                add("Aplikasi: ${BuildConfig.VERSION_NAME}")
                appUpdate?.let { add("Update aplikasi: ${it.tag} (${formatBytes(it.size)})") }
                if (appUpdate == null && updateChecked) add("Update aplikasi: versi terbaru")
                if (appUpdateMessage.isNotBlank()) add(appUpdateMessage)
                add("Shizuku: ${shizukuSummary(available, permission, serviceReady)}")
                if (shizuku.isBinding()) add("UserService: sedang menghubungkan")
                shizuku.lastError().takeIf(String::isNotBlank)?.let { add("File service: $it") }
                addAll(inspection?.diagnostics.orEmpty())
                release?.let {
                    add("Release: ${it.tag}")
                    add("Asset: ${ReleaseParser.PATCH_ASSET} (${formatBytes(it.size)})")
                    add("Expected SHA-256: ${it.sha256}")
                }
                if (networkMessage.isNotBlank()) add(networkMessage)
            }

            if (generation != refreshGeneration.get()) return@execute
            runOnUiThread {
                currentStatus = state
                canUninstall = inspection?.anyOwnedPatch == true
                diagnostics = diagnosticLines.joinToString("\n")
                renderState(state, inputs, networkMessage)
                renderRelease(release, networkMessage.takeIf(String::isNotBlank))
                renderAppUpdate(appUpdate, updateChecked, appUpdateMessage.takeIf(String::isNotBlank))
                binding.diagnosticsText.text = diagnostics
                binding.uninstallButton.isEnabled = canUninstall && !busy
                binding.refreshButton.isEnabled = !busy
            }
        }
    }

    private fun performPrimaryAction() {
        when (currentStatus) {
            LauncherStatus.NEEDS_SHIZUKU -> handleShizukuAction()
            LauncherStatus.GAME_NOT_READY, LauncherStatus.READY -> launchGame()
            LauncherStatus.NOT_INSTALLED, LauncherStatus.UPDATE_AVAILABLE -> installPatch()
            LauncherStatus.CONFLICT -> toast("Lepas patch yang tercantum pada diagnostik terlebih dahulu")
            LauncherStatus.ERROR -> refresh(fetchNetwork = true)
            LauncherStatus.BUSY -> Unit
        }
    }

    private fun handleShizukuAction() {
        when {
            !shizuku.isAvailable() -> openShizukuManager()
            !shizuku.hasPermission() -> shizuku.requestPermission()
            else -> shizuku.bind()
        }
        refresh(fetchNetwork = false)
    }

    private fun installPatch() {
        val release = latestRelease
        if (release == null || !releaseVerifiedOnline) {
            toast("Metadata patch belum tersedia. Periksa koneksi lalu coba lagi.")
            refresh(fetchNetwork = true)
            return
        }
        if (!shizuku.isReady()) {
            currentStatus = LauncherStatus.NEEDS_SHIZUKU
            handleShizukuAction()
            return
        }

        busy = true
        currentStatus = LauncherStatus.BUSY
        renderBusy("Sedang memasang patch", "Menyiapkan unduhan…", 0)
        executor.execute {
            val patchDirectory = getExternalFilesDir("patch")
                ?: return@execute runOnUiThread { finishWithError("External files directory tidak tersedia") }
            val partial = File(patchDirectory, "${ReleaseParser.PATCH_ASSET}.part")
            var lastPercent = -1
            try {
                releaseClient.download(release, partial) { downloaded, total ->
                    val percent = if (total > 0) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
                    if (percent != lastPercent) {
                        lastPercent = percent
                        runOnUiThread {
                            renderBusy(
                                "Sedang memasang patch",
                                "Mengunduh ${formatBytes(downloaded)} / ${formatBytes(total)}",
                                percent,
                            )
                        }
                    }
                }
                runOnUiThread {
                    renderBusy("Sedang memasang patch", "Memasang patch melalui Shizuku…", 100)
                }
                GamePaths(shizuku).install(partial.absolutePath, release)
                partial.delete()
                busy = false
                runOnUiThread {
                    toast("Patch Bahasa Indonesia berhasil dipasang")
                    hideProgress()
                    refresh(fetchNetwork = false)
                }
            } catch (error: Throwable) {
                partial.delete()
                runOnUiThread { finishWithError(readableError(error)) }
            }
        }
    }

    private fun confirmAppUpdate() {
        val release = latestAppRelease ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            toast("Izinkan WuwaID Mobile memasang aplikasi, lalu tekan Perbarui lagi")
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName"),
                    ),
                )
            } catch (_: ActivityNotFoundException) {
                toast("Pengaturan instalasi aplikasi tidak ditemukan")
            }
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Perbarui ke ${release.tag}?")
            .setMessage(buildString {
                append("${release.title}\n${formatBytes(release.size)}")
                release.notes.takeIf(String::isNotBlank)?.let { append("\n\n$it") }
                append("\n\nAndroid akan meminta konfirmasi instalasi.")
            })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.update_app) { _, _ -> downloadAppUpdate(release) }
            .show()
    }

    private fun downloadAppUpdate(release: AppRelease) {
        if (busy) return
        busy = true
        currentStatus = LauncherStatus.BUSY
        renderBusy(
            "Mengunduh update aplikasi",
            "Menyiapkan unduhan…",
            0,
            "APK diverifikasi sebelum installer Android dibuka.",
        )
        executor.execute {
            val directory = getExternalFilesDir(UpdateApkProvider.DIRECTORY)
                ?: return@execute runOnUiThread { finishWithError("External files directory tidak tersedia") }
            val partial = File(directory, "${UpdateApkProvider.FILE_NAME}.part")
            val apk = File(directory, UpdateApkProvider.FILE_NAME)
            var lastPercent = -1
            try {
                releaseClient.download(release, partial) { downloaded, total ->
                    val percent = if (total > 0) {
                        ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }
                    if (percent != lastPercent) {
                        lastPercent = percent
                        runOnUiThread {
                            renderBusy(
                                "Mengunduh update aplikasi",
                                "${formatBytes(downloaded)} / ${formatBytes(total)}",
                                percent,
                                "APK diverifikasi sebelum installer Android dibuka.",
                            )
                        }
                    }
                }
                if (apk.exists()) check(apk.delete()) { "Cache APK lama tidak dapat dihapus" }
                check(partial.renameTo(apk)) { "APK update tidak dapat disiapkan" }
                verifyUpdateApk(apk)
                busy = false
                runOnUiThread {
                    hideProgress()
                    launchAppInstaller()
                    refresh(fetchNetwork = false)
                }
            } catch (error: Throwable) {
                partial.delete()
                apk.delete()
                busy = false
                runOnUiThread {
                    hideProgress()
                    toast(readableError(error))
                    refresh(fetchNetwork = false)
                }
            }
        }
    }

    private fun verifyUpdateApk(file: File) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("APK update tidak valid")
        val installed = packageManager.getPackageInfo(packageName, flags)
        check(archive.packageName == packageName) { "Package APK update tidak cocok" }
        check(versionCode(archive) > versionCode(installed)) { "versionCode APK update tidak lebih baru" }
        check(signatures(archive) == signatures(installed)) { "Sertifikat APK update tidak cocok" }
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        val values = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        return values.map { it.toCharsString() }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        info.versionCode.toLong()
    }

    private fun launchAppInstaller() {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(UpdateApkProvider.uri(this), UpdateApkProvider.MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast("Installer APK tidak ditemukan")
        }
    }

    private fun clearAppUpdateCache() {
        getExternalFilesDir(UpdateApkProvider.DIRECTORY)?.let { directory ->
            File(directory, UpdateApkProvider.FILE_NAME).delete()
            File(directory, "${UpdateApkProvider.FILE_NAME}.part").delete()
        }
    }

    private fun confirmUninstall() {
        if (!canUninstall || busy) return
        AlertDialog.Builder(this)
            .setTitle(R.string.uninstall_title)
            .setMessage(R.string.uninstall_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.uninstall) { _, _ -> uninstallPatch() }
            .show()
    }

    private fun uninstallPatch() {
        if (!shizuku.isReady()) {
            handleShizukuAction()
            return
        }
        busy = true
        currentStatus = LauncherStatus.BUSY
        renderBusy("Sedang menghapus patch", "Menghapus file WuwaID…", 0)
        executor.execute {
            try {
                val removed = GamePaths(shizuku).uninstall()
                busy = false
                runOnUiThread {
                    toast("Patch dihapus ($removed file)")
                    hideProgress()
                    refresh(fetchNetwork = false)
                }
            } catch (error: Throwable) {
                runOnUiThread { finishWithError(readableError(error)) }
            }
        }
    }

    private fun launchGame() {
        val intent = packageManager.getLaunchIntentForPackage(GamePaths.GAME_PACKAGE)
        if (intent == null) {
            toast("Wuthering Waves Global tidak ditemukan")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun openShizukuManager() {
        try {
            val managerIntent = packageManager.getLaunchIntentForPackage(ShizukuFileClient.MANAGER_PACKAGE)
            if (managerIntent != null) {
                startActivity(managerIntent)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_URL)))
            }
        } catch (_: ActivityNotFoundException) {
            toast("Tidak ada aplikasi untuk membuka panduan Shizuku")
        }
    }

    private fun renderState(state: LauncherStatus, inputs: StateInputs, networkMessage: String) {
        val (title, detail, action, color) = when (state) {
            LauncherStatus.NEEDS_SHIZUKU -> when {
                !inputs.shizukuAvailable -> StatusUi(
                    "Shizuku belum berjalan",
                    "Pasang atau aktifkan Shizuku, lalu kembali ke aplikasi.",
                    "Buka Shizuku",
                    R.color.danger,
                )
                !inputs.shizukuPermission -> StatusUi(
                    "Izin Shizuku diperlukan",
                    "WuwaID hanya memakai izin untuk folder data Wuthering Waves.",
                    "Beri izin Shizuku",
                    R.color.warning,
                )
                else -> StatusUi(
                    "Menghubungkan Shizuku",
                    "UserService belum siap. Tekan tombol untuk mencoba lagi.",
                    "Hubungkan ulang",
                    R.color.warning,
                )
            }
            LauncherStatus.GAME_NOT_READY -> StatusUi(
                "Data game belum siap",
                "Buka Wuthering Waves dan selesaikan unduhan resource terlebih dahulu.",
                "Buka Wuthering Waves",
                R.color.warning,
            )
            LauncherStatus.CONFLICT -> StatusUi(
                "Patch lain terdeteksi",
                "Instalasi diblokir agar mod lain tidak tertimpa. Lihat diagnostik.",
                "Lihat diagnostik",
                R.color.danger,
            )
            LauncherStatus.NOT_INSTALLED -> StatusUi(
                "Patch belum dipasang",
                networkMessage.ifBlank { getString(R.string.close_game_hint) },
                if (!releaseVerifiedOnline) "Periksa GitHub dahulu" else "Pasang Bahasa Indonesia",
                R.color.warning,
            )
            LauncherStatus.UPDATE_AVAILABLE -> StatusUi(
                "Pembaruan diperlukan",
                networkMessage.ifBlank { "Versi game atau patch terbaru berbeda dari instalasi saat ini." },
                if (!releaseVerifiedOnline) "Periksa GitHub dahulu" else "Perbarui patch",
                R.color.warning,
            )
            LauncherStatus.READY -> StatusUi(
                "Siap dimainkan",
                if (networkMessage.isBlank()) "Patch terpasang dan mount valid." else "Patch valid. Pemeriksaan update offline.",
                "Mainkan",
                R.color.success,
            )
            LauncherStatus.BUSY -> StatusUi("Sedang bekerja", "Jangan tutup aplikasi.", "Mohon tunggu", R.color.accent)
            LauncherStatus.ERROR -> StatusUi("Pemeriksaan gagal", "Tekan tombol untuk mencoba lagi.", "Coba lagi", R.color.danger)
        }

        binding.statusTitle.text = title
        binding.statusDetail.text = detail
        binding.primaryButton.text = action
        binding.primaryButton.isEnabled = when (state) {
            LauncherStatus.BUSY, LauncherStatus.CONFLICT -> false
            LauncherStatus.NOT_INSTALLED, LauncherStatus.UPDATE_AVAILABLE -> releaseVerifiedOnline
            else -> true
        }
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(getColor(color))
    }

    private fun renderRelease(release: PatchRelease?, warning: String?) {
        if (release == null) {
            binding.releaseMeta.text = warning ?: getString(R.string.release_loading)
            binding.releaseNotes.text = ""
            return
        }
        val date = release.publishedAt.take(10)
        binding.releaseMeta.text = "${release.tag}${if (date.isNotBlank()) " · $date" else ""}"
        binding.releaseNotes.text = buildString {
            if (warning != null) append("$warning\n\n")
            append(release.notes.replace("\r", "").ifBlank { release.title })
        }
    }

    private fun renderAppUpdate(release: AppRelease?, checked: Boolean, warning: String?) {
        binding.appUpdateText.text = when {
            release != null -> buildString {
                append("${release.tag} tersedia · ${formatBytes(release.size)}")
                append("\n${warning ?: release.title}")
            }
            warning != null -> "Versi ${BuildConfig.VERSION_NAME}\n$warning"
            checked -> "Versi ${BuildConfig.VERSION_NAME} · terbaru"
            else -> "Versi ${BuildConfig.VERSION_NAME} · sedang diperiksa"
        }
        binding.appUpdateButton.visibility = if (release == null) View.GONE else View.VISIBLE
        binding.appUpdateButton.isEnabled = release != null && !busy
    }

    private fun renderBusy(
        title: String,
        message: String,
        percent: Int,
        detail: String = getString(R.string.close_game_hint),
    ) {
        currentStatus = LauncherStatus.BUSY
        binding.progressGroup.visibility = View.VISIBLE
        binding.downloadProgress.progress = percent
        binding.progressText.text = message
        binding.statusTitle.text = title
        binding.statusDetail.text = detail
        binding.primaryButton.text = "Mohon tunggu"
        binding.primaryButton.isEnabled = false
        binding.refreshButton.isEnabled = false
        binding.uninstallButton.isEnabled = false
        binding.appUpdateButton.isEnabled = false
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(getColor(R.color.accent))
    }

    private fun hideProgress() {
        binding.progressGroup.visibility = View.GONE
        binding.downloadProgress.progress = 0
        binding.progressText.text = ""
    }

    private fun renderError(message: String) {
        busy = false
        currentStatus = LauncherStatus.ERROR
        hideProgress()
        binding.statusTitle.text = "Operasi gagal"
        binding.statusDetail.text = message
        binding.primaryButton.text = "Coba lagi"
        binding.primaryButton.isEnabled = true
        binding.refreshButton.isEnabled = true
        binding.uninstallButton.isEnabled = canUninstall
        binding.appUpdateButton.isEnabled = latestAppRelease != null
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(getColor(R.color.danger))
        binding.diagnosticsText.text = "$diagnostics\nError: $message"
    }

    private fun finishWithError(message: String) {
        busy = false
        toast(message)
        renderError(message)
    }

    private fun shizukuSummary(available: Boolean, permission: Boolean, ready: Boolean): String = when {
        !available -> "tidak berjalan"
        !permission -> "berjalan, izin belum diberikan"
        !ready -> "izin diberikan, UserService belum siap"
        else -> "siap"
    }

    private fun readableError(error: Throwable): String = error.message
        ?.takeIf(String::isNotBlank)
        ?: error.javaClass.simpleName

    private fun formatBytes(bytes: Long): String = if (bytes < 0) "?" else "%.1f MB".format(bytes / 1_048_576.0)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private data class StatusUi(val title: String, val detail: String, val action: String, val color: Int)

    companion object {
        private const val SHIZUKU_URL = "https://github.com/RikkaApps/Shizuku"
    }
}
