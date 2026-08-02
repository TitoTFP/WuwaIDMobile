package com.titotfp.wuwaid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.ZipInputStream

class DiagnosticUploadClientTest {
    @Test
    fun archiveContainsDiagnosticsReport() {
        val report = "Shizuku: izin diberikan, UserService belum siap"
        val archive = DiagnosticUploadClient().createArchive(report)

        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            assertEquals("diagnostics.txt", zip.nextEntry.name)
            assertEquals(report, zip.bufferedReader(UTF_8).use { it.readText() })
        }
    }

    @Test
    fun uploadSendsAllMultipartFieldsForSuccessful2xxAndDisconnects() {
        val connection = FakeHttpURLConnection(responseCode = 201)
        val client =
            DiagnosticUploadClient(
                endpoint = "https://logs.example.test/api/logs",
                openConnection = { connection },
            )

        client.upload(
            report = "Shizuku siap",
            appVersion = "WuwaIDMobile-0.3.1",
            os = "Android 15 (API 35)",
            timestamp = "2026-08-02T04:00:00Z",
        )

        val body = String(connection.body.toByteArray(), UTF_8)
        assertTrue(body.contains("name=\"appVersion\"\r\n\r\nWuwaIDMobile-0.3.1"))
        assertTrue(body.contains("name=\"timestamp\"\r\n\r\n2026-08-02T04:00:00Z"))
        assertTrue(body.contains("name=\"os\"\r\n\r\nAndroid 15 (API 35)"))
        assertTrue(body.contains("name=\"logs\"; filename=\"wuwaidmobile-diagnostics.zip\""))
        assertTrue(body.contains("Content-Type: application/zip\r\n\r\nPK"))
        assertTrue(connection.disconnected)
    }

    @Test
    fun uploadRejectsInsecureEndpointBeforeOpeningConnection() {
        var connectionOpened = false
        val client =
            DiagnosticUploadClient(
                endpoint = "http://logs.example.test/api/logs",
                openConnection = {
                    connectionOpened = true
                    error("insecure endpoint must not open a connection")
                },
            )

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                client.upload(
                    report = "Shizuku siap",
                    appVersion = "WuwaIDMobile-0.3.1",
                    os = "Android 15 (API 35)",
                    timestamp = "2026-08-02T04:00:00Z",
                )
            }

        assertEquals("URL diagnostik wajib HTTPS", error.message)
        assertFalse(connectionOpened)
    }

    @Test
    fun uploadUsesMultipartAndSurfacesHttpFailure() {
        val connection = FakeHttpURLConnection(responseCode = 500)
        val client =
            DiagnosticUploadClient(
                endpoint = "https://logs.example.test/api/logs",
                openConnection = { connection },
            )

        val error =
            assertThrows(IllegalStateException::class.java) {
                client.upload(
                    report = "UserService tidak merespons",
                    appVersion = "WuwaIDMobile-0.3.1",
                    os = "Android 15 (API 35)",
                    timestamp = "2026-08-02T04:00:00Z",
                )
            }

        val body = String(connection.body.toByteArray(), UTF_8)
        assertEquals("Server menolak diagnostik (HTTP 500)", error.message)
        assertTrue(connection.disconnected)
        assertTrue(body.contains("name=\"appVersion\"\r\n\r\nWuwaIDMobile-0.3.1"))
        assertTrue(body.contains("name=\"logs\"; filename=\"wuwaidmobile-diagnostics.zip\""))
    }

    private class FakeHttpURLConnection(
        private val responseCode: Int,
    ) : HttpURLConnection(URL("https://logs.example.test/api/logs")) {
        val body = ByteArrayOutputStream()
        var disconnected = false

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy(): Boolean = false

        override fun getOutputStream(): ByteArrayOutputStream = body

        override fun getResponseCode(): Int = responseCode
    }
}
