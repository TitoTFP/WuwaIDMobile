package com.titotfp.wuwaid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RootHelperPackagingTest {
    @Test
    fun helperIsPackagedExecutableAndAnswersPing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeDir = File(context.applicationInfo.nativeLibraryDir).canonicalFile
        val helper = File(nativeDir, RootFileClient.HELPER_NAME).canonicalFile

        assertEquals(nativeDir, helper.parentFile)
        assertTrue("Root helper tidak ditemukan: $helper", helper.isFile)
        assertTrue("Root helper tidak executable: $helper", helper.canExecute())

        val process = ProcessBuilder(helper.path, "--stdio").start()
        DataOutputStream(process.outputStream).use { output ->
            output.writeInt(0x57554944)
            output.writeShort(1)
            output.writeShort(0)
            output.writeInt(2)
            output.writeShort(0)
        }

        assertTrue("Root helper timeout", process.waitFor(5, TimeUnit.SECONDS))
        val response =
            DataInputStream(process.inputStream).use { input ->
                listOf(input.readInt(), input.readUnsignedShort(), input.readUnsignedShort(), input.readInt(), input.readInt())
            }
        assertEquals(listOf(0x57554944, 1, 0, 0, 0), response)
        assertEquals(process.errorStream.bufferedReader().readText(), 0, process.exitValue())
    }
}
