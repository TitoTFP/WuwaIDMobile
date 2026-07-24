package com.titotfp.wuwaid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class GitHubReleaseClientTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun fetchLatestUsesEmbeddedDigestWithoutChecksumRequest() {
        val transport = FakeTransport().apply {
            enqueueText(GitHubReleaseClient.LATEST_RELEASE_URL, patchReleaseJson(HASH_A))
        }

        val release = GitHubReleaseClient(transport).fetchLatest()

        assertEquals(HASH_A, release.sha256)
        assertEquals(
            listOf(Request(GitHubReleaseClient.LATEST_RELEASE_URL, githubApi = true)),
            transport.requests,
        )
        assertEquals(1, transport.closedResponses)
    }

    @Test
    fun fetchLatestUsesExactChecksumFallback() {
        val transport = FakeTransport().apply {
            enqueueText(GitHubReleaseClient.LATEST_RELEASE_URL, patchReleaseJson(digest = null))
            enqueueText(CHECKSUM_URL, "$HASH_B  ${ReleaseParser.PATCH_ASSET}\n$HASH_A  other.pak")
        }

        val release = GitHubReleaseClient(transport).fetchLatest()

        assertEquals(HASH_B, release.sha256)
        assertEquals(
            listOf(
                Request(GitHubReleaseClient.LATEST_RELEASE_URL, githubApi = true),
                Request(CHECKSUM_URL, githubApi = false),
            ),
            transport.requests,
        )
        assertEquals(2, transport.closedResponses)
    }

    @Test
    fun fetchLatestRejectsReleaseWithoutDigestOrChecksumAsset() {
        val transport = FakeTransport().apply {
            enqueueText(
                GitHubReleaseClient.LATEST_RELEASE_URL,
                patchReleaseJson(digest = null, includeChecksums = false),
            )
        }

        val error = assertThrows(IllegalStateException::class.java) {
            GitHubReleaseClient(transport).fetchLatest()
        }

        assertTrue(error.message.orEmpty().contains("SHA256sums.txt"))
        assertEquals(1, transport.requests.size)
        assertEquals(1, transport.closedResponses)
    }

    @Test
    fun appUpdateReturnsNullWithoutFetchingChecksumWhenCurrentIsNewest() {
        val transport = FakeTransport().apply {
            enqueueText(GitHubReleaseClient.APP_RELEASES_URL, appReleaseJson())
        }

        val update = GitHubReleaseClient(transport).fetchLatestAppUpdate("0.2.0")

        assertNull(update)
        assertEquals(
            listOf(Request(GitHubReleaseClient.APP_RELEASES_URL, githubApi = true)),
            transport.requests,
        )
    }

    @Test
    fun verifiedDownloadWritesPartAndReportsProgress() {
        val bytes = ByteArray(70_000) { (it % 251).toByte() }
        val transport = FakeTransport().apply { enqueueBytes(DOWNLOAD_URL, bytes) }
        val destination = File(temporary.root, "downloads/patch.part")
        val progress = mutableListOf<Pair<Long, Long>>()

        GitHubReleaseClient(transport).download(
            patchRelease(bytes),
            destination,
        ) { downloaded, total -> progress += downloaded to total }

        assertTrue(destination.isFile)
        assertArrayEquals(bytes, destination.readBytes())
        assertTrue(progress.isNotEmpty())
        assertEquals(bytes.size.toLong() to bytes.size.toLong(), progress.last())
        assertEquals(1, transport.closedResponses)
    }

    @Test
    fun sizeMismatchDeletesPartialDownload() {
        val bytes = "short".toByteArray()
        val transport = FakeTransport().apply { enqueueBytes(DOWNLOAD_URL, bytes) }
        val destination = File(temporary.root, "patch.part")
        val release = patchRelease(bytes).copy(size = bytes.size + 1L)

        val error = assertThrows(IllegalStateException::class.java) {
            GitHubReleaseClient(transport).download(release, destination) { _, _ -> }
        }

        assertTrue(error.message.orEmpty().contains("Ukuran patch tidak cocok"))
        assertFalse(destination.exists())
        assertEquals(1, transport.closedResponses)
    }

    @Test
    fun hashMismatchDeletesPartialDownload() {
        val bytes = "downloaded-patch".toByteArray()
        val transport = FakeTransport().apply { enqueueBytes(DOWNLOAD_URL, bytes) }
        val destination = File(temporary.root, "patch.part")
        val release = patchRelease(bytes).copy(sha256 = HASH_A)

        val error = assertThrows(IllegalStateException::class.java) {
            GitHubReleaseClient(transport).download(release, destination) { _, _ -> }
        }

        assertTrue(error.message.orEmpty().contains("SHA-256 patch tidak cocok"))
        assertFalse(destination.exists())
        assertEquals(1, transport.closedResponses)
    }

    @Test
    fun interruptedDownloadDeletesPartAndClosesResponse() {
        val transport = FakeTransport().apply {
            enqueueStream(DOWNLOAD_URL) { FailingInputStream(ByteArray(70_000) { 1 }) }
        }
        val destination = File(temporary.root, "patch.part")
        val release = PatchRelease(
            tag = "v-test",
            title = "Test",
            publishedAt = "",
            notes = "",
            assetUrl = DOWNLOAD_URL,
            size = 70_000,
            sha256 = HASH_A,
        )

        assertThrows(IOException::class.java) {
            GitHubReleaseClient(transport).download(release, destination) { _, _ -> }
        }

        assertFalse(destination.exists())
        assertEquals(1, transport.closedResponses)
    }

    @Test
    fun downloadRequiresPartDestinationBeforeOpeningNetwork() {
        val transport = FakeTransport()
        val bytes = "patch".toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            GitHubReleaseClient(transport).download(
                patchRelease(bytes),
                File(temporary.root, "patch.pak"),
            ) { _, _ -> }
        }

        assertTrue(transport.requests.isEmpty())
    }

    private fun patchRelease(bytes: ByteArray): PatchRelease = PatchRelease(
        tag = "v-test",
        title = "Test",
        publishedAt = "",
        notes = "",
        assetUrl = DOWNLOAD_URL,
        size = bytes.size.toLong(),
        sha256 = bytes.sha256(),
    )

    private fun patchReleaseJson(
        digest: String?,
        includeChecksums: Boolean = true,
    ): String {
        val digestField = digest?.let { "\"digest\":\"sha256:$it\"," }.orEmpty()
        val checksumAsset = if (includeChecksums) {
            ", {\"name\":\"SHA256sums.txt\",\"browser_download_url\":\"$CHECKSUM_URL\"}"
        } else {
            ""
        }
        return """
            {
              "tag_name":"v3.5.1-id.3",
              "name":"Patch Test",
              "assets":[
                {
                  "name":"${ReleaseParser.PATCH_ASSET}",
                  "size":123,
                  $digestField
                  "browser_download_url":"$DOWNLOAD_URL"
                }
                $checksumAsset
              ]
            }
        """.trimIndent()
    }

    private fun appReleaseJson(): String = """
        [
          {
            "tag_name":"v0.2.0",
            "draft":false,
            "assets":[
              {
                "name":"WuwaID-Mobile-v0.2.0.apk",
                "size":123,
                "digest":"sha256:$HASH_A",
                "browser_download_url":"$DOWNLOAD_URL"
              }
            ]
          }
        ]
    """.trimIndent()

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class Request(
        val url: String,
        val githubApi: Boolean,
    )

    private class FakeTransport : ReleaseHttpTransport {
        private data class ResponseSpec(
            val streamFactory: () -> InputStream,
        )

        private val responses = mutableMapOf<String, MutableList<ResponseSpec>>()
        val requests = mutableListOf<Request>()
        var closedResponses = 0

        fun enqueueText(url: String, text: String) = enqueueBytes(url, text.toByteArray())

        fun enqueueBytes(url: String, bytes: ByteArray) {
            enqueueStream(url) { ByteArrayInputStream(bytes) }
        }

        fun enqueueStream(url: String, streamFactory: () -> InputStream) {
            responses.getOrPut(url) { mutableListOf() } += ResponseSpec(streamFactory)
        }

        override fun open(url: String, githubApi: Boolean): ReleaseHttpResponse {
            requests += Request(url, githubApi)
            val queue = responses[url] ?: error("Tidak ada response fake untuk $url")
            val spec = queue.removeAt(0)
            return object : ReleaseHttpResponse {
                override fun openStream(): InputStream = spec.streamFactory()
                override fun close() {
                    closedResponses += 1
                }
            }
        }
    }

    private class FailingInputStream(
        private val bytes: ByteArray,
    ) : InputStream() {
        private var position = 0
        private var bulkReads = 0

        override fun read(): Int {
            if (position >= bytes.size) return -1
            return bytes[position++].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (bulkReads++ > 0) throw IOException("connection reset")
            if (position >= bytes.size) return -1
            val count = minOf(length, 32_000, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
    }

    companion object {
        private const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val CHECKSUM_URL = "https://example.invalid/SHA256sums.txt"
        private const val DOWNLOAD_URL = "https://example.invalid/download"
    }
}
