package com.titotfp.wuwaid

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

class RootFileClientTest {
    private val gameRoot = "/storage/emulated/0/Android/data/${GamePaths.GAME_PACKAGE}"

    private class FakeHelper : RootFileClient.HelperExecutor {
        val requests = mutableListOf<ByteArray>()
        var handler: (ByteArray) -> RootFileClient.ProcessResult = { success() }

        override fun run(
            request: ByteArray,
            timeoutSeconds: Long,
        ): RootFileClient.ProcessResult {
            requests += request
            return handler(request)
        }
    }

    private fun client(helper: FakeHelper) = RootFileClient(ContextWrapper(null), helper)

    @Test fun rejectsPathOutsideAllowlist() {
        val helper = FakeHelper()
        assertFalse(client(helper).exists("/etc/passwd"))
        assertTrue(helper.requests.isEmpty())
    }

    @Test fun rejectsTraversalAndPrefixLookalike() {
        val helper = FakeHelper()
        assertEquals("", client(helper).readText("$gameRoot/files/../../system"))
        assertEquals("", client(helper).readText("${gameRoot}evil/files/x"))
        assertTrue(helper.requests.isEmpty())
    }

    @Test fun rejectsNul() {
        assertFalse(client(FakeHelper()).exists("$gameRoot/a\u0000b"))
    }

    @Test fun parsesBooleanAndList() {
        val helper = FakeHelper()
        helper.handler = { success(byteArrayOf(1)) }
        assertTrue(client(helper).exists("$gameRoot/files/a"))
        helper.handler = { success(stringList("b", "a")) }
        assertEquals(listOf("b", "a"), client(helper).listFiles("$gameRoot/files").toList())
    }

    @Test fun malformedResponseFailsClosed() {
        val helper = FakeHelper()
        helper.handler = { RootFileClient.ProcessResult(0, byteArrayOf(1, 2), "") }
        val client = client(helper)
        assertFalse(client.exists("$gameRoot/files/a"))
        assertTrue(client.lastError().isNotBlank())
    }

    @Test fun nonzeroExitCannotLookSuccessful() {
        val helper = FakeHelper()
        helper.handler = { RootFileClient.ProcessResult(1, success().stdout, "denied\n/path/private") }
        val client = client(helper)
        assertFalse(client.mkdirs("$gameRoot/files/a"))
        assertFalse(client.lastError().contains("/path/private"))
    }

    @Test fun helperFailureIsRememberedAndSuccessClearsIt() {
        val helper = FakeHelper()
        helper.handler = { throw IOException("boom") }
        val client = client(helper)
        assertFalse(client.deleteFile("$gameRoot/files/a"))
        assertTrue(client.lastError().contains("boom"))
        helper.handler = { success() }
        assertTrue(client.deleteFile("$gameRoot/files/a"))
        assertEquals("", client.lastError())
    }

    @Test fun availabilityProbeIsCachedUntilInvalidated() {
        val helper = FakeHelper()
        val client = client(helper)
        assertTrue(client.isAvailable())
        assertTrue(client.isAvailable())
        assertEquals(1, helper.requests.size)
        client.invalidateProbe()
        assertTrue(client.isAvailable())
        assertEquals(2, helper.requests.size)
    }

    @Test fun protocolRejectsTruncatedAndOversizedFrames() {
        assertThrows(IOException::class.java) { RootFileClient.Protocol.decodeResponse(byteArrayOf()) }
        val malformed = responseHeader(0, 0, RootFileClient.MAX_PAYLOAD + 1)
        assertThrows(IOException::class.java) { RootFileClient.Protocol.decodeResponse(malformed) }
    }

    companion object {
        private fun success(payload: ByteArray = byteArrayOf()) =
            RootFileClient.ProcessResult(
                0,
                responseHeader(0, 0, payload.size) + payload,
                "",
            )

        private fun responseHeader(
            status: Int,
            errno: Int,
            length: Int,
        ) = ByteArrayOutputStream()
            .also { b ->
                DataOutputStream(b).use { o ->
                    o.writeInt(0x57554944)
                    o.writeShort(1)
                    o.writeShort(status)
                    o.writeInt(errno)
                    o.writeInt(length)
                }
            }.toByteArray()

        private fun stringList(vararg values: String) =
            ByteArrayOutputStream()
                .also { b ->
                    DataOutputStream(b).use { o ->
                        o.writeInt(values.size)
                        values.forEach {
                            val bytes = it.toByteArray()
                            o.writeInt(bytes.size)
                            o.write(bytes)
                        }
                    }
                }.toByteArray()
    }
}
