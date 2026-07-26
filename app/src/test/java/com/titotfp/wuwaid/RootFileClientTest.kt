package com.titotfp.wuwaid

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootFileClientTest {
    private val gameRoot = "/storage/emulated/0/Android/data/${GamePaths.GAME_PACKAGE}/files"

    private class FakeSuExecutor : RootFileClient.SuExecutor {
        val commands = mutableListOf<String>()
        var handler: (String) -> RootFileClient.SuResult = { RootFileClient.SuResult(0, "", "") }

        override fun run(command: String, stdin: ByteArray?, timeoutSeconds: Long): RootFileClient.SuResult {
            commands += command
            return handler(command)
        }
    }

    private fun client(su: FakeSuExecutor) = RootFileClient(ContextWrapper(null), su)

    @Test
    fun rejectsPathOutsideAllowlist() {
        val su = FakeSuExecutor()
        assertThrows(IllegalArgumentException::class.java) {
            client(su).exists("/etc/passwd")
        }
        assertTrue(su.commands.isEmpty())
    }

    @Test
    fun rejectsTraversalEscapingAllowlist() {
        val su = FakeSuExecutor()
        assertThrows(IllegalArgumentException::class.java) {
            client(su).readText("$gameRoot/../../../../system/build.prop")
        }
        assertTrue(su.commands.isEmpty())
    }

    @Test
    fun quotesSingleQuotesInPaths() {
        val su = FakeSuExecutor()
        client(su).deleteFile("$gameRoot/it's.pak")
        assertEquals(listOf("rm -f '$gameRoot/it'\\''s.pak'"), su.commands)
    }

    @Test
    fun existsParsesProbeOutput() {
        val su = FakeSuExecutor()
        su.handler = { RootFileClient.SuResult(0, "1\n", "") }
        assertTrue(client(su).exists("$gameRoot/patch.pak"))

        su.handler = { RootFileClient.SuResult(0, "0\n", "") }
        assertFalse(client(su).exists("$gameRoot/patch.pak"))
    }

    @Test
    fun sha256ParsesFirstToken() {
        val su = FakeSuExecutor()
        su.handler = { RootFileClient.SuResult(0, "abc123def  $gameRoot/patch.pak\n", "") }
        assertEquals("abc123def", client(su).sha256("$gameRoot/patch.pak"))
    }

    @Test
    fun listFilesSplitsAndTrimsLines() {
        val su = FakeSuExecutor()
        su.handler = { RootFileClient.SuResult(0, "a.pak\n\nb.sig\n", "") }
        assertEquals(listOf("a.pak", "b.sig"), client(su).listFiles(gameRoot).toList())
    }

    @Test
    fun failureSetsLastErrorAndSuccessClearsIt() {
        val su = FakeSuExecutor()
        val client = client(su)

        su.handler = { command ->
            if (command.startsWith("rm ")) {
                RootFileClient.SuResult(1, "", "permission denied")
            } else {
                RootFileClient.SuResult(0, "", "")
            }
        }
        assertFalse(client.deleteFile("$gameRoot/patch.pak"))
        assertEquals("su failed (rm -f '$gameRoot/patch.pak'): permission denied", client.lastError())
        // lastError is non-destructive: reading twice returns the same message
        assertEquals("su failed (rm -f '$gameRoot/patch.pak'): permission denied", client.lastError())

        assertTrue(client.mkdirs("$gameRoot/folder"))
        assertEquals("", client.lastError())
    }

    @Test
    fun availabilityProbeIsCachedUntilInvalidated() {
        val su = FakeSuExecutor()
        val client = client(su)
        su.handler = { RootFileClient.SuResult(0, "uid=0(root)", "") }

        assertTrue(client.isAvailable())
        assertTrue(client.isAvailable())
        assertEquals(1, su.commands.count { it == "id" })

        su.handler = { RootFileClient.SuResult(1, "", "denied") }
        assertTrue(client.isAvailable())

        client.invalidateProbe()
        assertFalse(client.isAvailable())
        assertEquals(2, su.commands.count { it == "id" })
    }

    @Test
    fun probeFailureIsAvailableFalse() {
        val su = FakeSuExecutor()
        su.handler = { throw java.io.IOException("no su binary") }
        assertFalse(client(su).isAvailable())
    }
}
