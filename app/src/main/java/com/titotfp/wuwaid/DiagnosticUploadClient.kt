package com.titotfp.wuwaid

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class DiagnosticUploadClient internal constructor(
    private val endpoint: String = LOG_UPLOAD_ENDPOINT,
    private val openConnection: (String) -> HttpURLConnection = { url ->
        URL(url).openConnection() as HttpURLConnection
    },
) {
    fun upload(
        report: String,
        appVersion: String,
        os: String,
        timestamp: String = Instant.now().toString(),
    ) {
        require(endpoint.startsWith("https://")) { "URL diagnostik wajib HTTPS" }

        val boundary = "----WuwaIDMobile${UUID.randomUUID().toString().replace("-", "")}"
        val connection = openConnection(endpoint)
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", "WuwaID-Mobile/$appVersion")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            connection.outputStream.buffered().use { output ->
                writeField(output, boundary, "appVersion", appVersion)
                writeField(output, boundary, "timestamp", timestamp)
                writeField(output, boundary, "os", os)
                writeArchive(output, boundary, createArchive(report))
                output.write("--$boundary--\r\n".toByteArray(UTF_8))
            }

            val responseCode = connection.responseCode
            check(responseCode in 200..299) {
                "Server menolak diagnostik (HTTP $responseCode)"
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun createArchive(report: String): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("diagnostics.txt"))
                zip.write(report.toByteArray(UTF_8))
                zip.closeEntry()
            }
            bytes.toByteArray()
        }

    private fun writeField(
        output: OutputStream,
        boundary: String,
        name: String,
        value: String,
    ) {
        output.write("--$boundary\r\n".toByteArray(UTF_8))
        output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(UTF_8))
        output.write(value.toByteArray(UTF_8))
        output.write("\r\n".toByteArray(UTF_8))
    }

    private fun writeArchive(
        output: OutputStream,
        boundary: String,
        archive: ByteArray,
    ) {
        output.write("--$boundary\r\n".toByteArray(UTF_8))
        output.write(
            "Content-Disposition: form-data; name=\"logs\"; filename=\"wuwaidmobile-diagnostics.zip\"\r\n".toByteArray(
                UTF_8,
            ),
        )
        output.write("Content-Type: application/zip\r\n\r\n".toByteArray(UTF_8))
        output.write(archive)
        output.write("\r\n".toByteArray(UTF_8))
    }

    private companion object {
        const val LOG_UPLOAD_ENDPOINT = "https://logs.titotfp.my.id/api/logs"
        const val TIMEOUT_MS = 30_000
    }
}
