package com.titotfp.wuwaid

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal interface ReleaseHttpResponse : Closeable {
    fun openStream(): InputStream
}

internal fun interface ReleaseHttpTransport {
    fun open(url: String, githubApi: Boolean): ReleaseHttpResponse
}

internal class UrlConnectionReleaseHttpTransport : ReleaseHttpTransport {
    override fun open(url: String, githubApi: Boolean): ReleaseHttpResponse {
        require(url.startsWith("https://")) { "URL wajib HTTPS" }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 30 * 60_000
        connection.setRequestProperty("User-Agent", "WuwaID-Mobile/${BuildConfig.VERSION_NAME}")
        if (githubApi) connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.connect()

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            connection.disconnect()
            error("HTTP $responseCode saat mengakses GitHub")
        }
        if (!connection.url.protocol.equals("https", ignoreCase = true)) {
            connection.disconnect()
            error("Redirect non-HTTPS ditolak")
        }

        return object : ReleaseHttpResponse {
            override fun openStream(): InputStream = connection.inputStream
            override fun close() = connection.disconnect()
        }
    }
}

class GitHubReleaseClient internal constructor(
    private val transport: ReleaseHttpTransport,
) {
    constructor() : this(UrlConnectionReleaseHttpTransport())

    fun fetchLatest(): PatchRelease {
        val parsed = ReleaseParser.parse(getText(LATEST_RELEASE_URL, githubApi = true))
        val fallback = if (parsed.digest == null) {
            val url = parsed.checksumsUrl ?: error("Rilis tidak memiliki SHA256sums.txt")
            ReleaseParser.checksumForPatch(getText(url))
        } else {
            null
        }
        return ReleaseParser.finish(parsed, fallback)
    }

    fun fetchLatestAppUpdate(currentVersion: String): AppRelease? {
        val parsed = AppReleaseParser.parseLatest(
            getText(APP_RELEASES_URL, githubApi = true),
            currentVersion,
        ) ?: return null
        val fallback = if (parsed.digest == null) {
            val url = parsed.checksumsUrl ?: error("Rilis aplikasi tidak memiliki SHA256sums.txt")
            AppReleaseParser.checksumForApk(getText(url), parsed.tag)
        } else {
            null
        }
        return AppReleaseParser.finish(parsed, fallback)
    }

    fun download(release: PatchRelease, destination: File, progress: (Long, Long) -> Unit) {
        downloadVerified(
            url = release.assetUrl,
            size = release.size,
            sha256 = release.sha256,
            label = "patch",
            destination = destination,
            progress = progress,
        )
    }

    fun download(release: AppRelease, destination: File, progress: (Long, Long) -> Unit) {
        downloadVerified(
            url = release.assetUrl,
            size = release.size,
            sha256 = release.sha256,
            label = "APK update",
            destination = destination,
            progress = progress,
        )
    }

    private fun downloadVerified(
        url: String,
        size: Long,
        sha256: String,
        label: String,
        destination: File,
        progress: (Long, Long) -> Unit,
    ) {
        require(destination.name.endsWith(".part")) { "Unduhan harus memakai file .part" }
        destination.parentFile?.mkdirs()
        destination.delete()

        try {
            transport.open(url, githubApi = false).use { response ->
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                BufferedInputStream(response.openStream(), BUFFER_SIZE).use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            downloaded += count
                            progress(downloaded, size)
                        }
                        output.fd.sync()
                    }
                }
                check(downloaded == size) {
                    "Ukuran $label tidak cocok: $downloaded dari $size byte"
                }
                val actual = digest.digest().toHex()
                check(actual.equals(sha256, ignoreCase = true)) {
                    "SHA-256 $label tidak cocok"
                }
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    private fun getText(url: String, githubApi: Boolean = false): String =
        transport.open(url, githubApi).use { response ->
            response.openStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/TitoTFP/WuwaID/releases/latest"
        const val APP_RELEASES_URL = "https://api.github.com/repos/TitoTFP/WuwaIDMobile/releases?per_page=20"
        private const val BUFFER_SIZE = 64 * 1024
    }
}
