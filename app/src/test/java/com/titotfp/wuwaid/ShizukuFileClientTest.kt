package com.titotfp.wuwaid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuFileClientTest {
    @Test
    fun successfulBindBecomesReadyAndCancelsTimeout() {
        val fixture = Fixture()

        fixture.client.start()
        assertTrue(fixture.client.isBinding())
        assertEquals(emptyList<Int>(), fixture.gateway.bindAttempts)

        fixture.scheduler.advanceBy(ShizukuFileClient.INITIAL_BIND_DELAY_MS)
        assertEquals(listOf(1), fixture.gateway.bindAttempts)

        fixture.gateway.connect(1, FakeUserServiceFiles())

        assertTrue(fixture.client.isReady())
        assertFalse(fixture.client.isBinding())
        assertEquals("", fixture.client.lastError())

        fixture.scheduler.advanceBy(
            ShizukuFileClient.BIND_TIMEOUT_MS + ShizukuFileClient.RETRY_DELAYS_MS.sum(),
        )
        assertEquals(listOf(1), fixture.gateway.bindAttempts)
        assertTrue(fixture.gateway.unbindCalls.isEmpty())
    }

    @Test
    fun stuckBindTimesOutUnbindsAndRetries() {
        val fixture = Fixture()
        fixture.client.start()
        fixture.scheduler.advanceBy(ShizukuFileClient.INITIAL_BIND_DELAY_MS)

        fixture.scheduler.advanceBy(ShizukuFileClient.BIND_TIMEOUT_MS)

        assertEquals(listOf(UnbindCall(1, true)), fixture.gateway.unbindCalls)
        assertTrue(fixture.client.isBinding())
        assertFalse(fixture.client.isReady())
        assertEquals("UserService tidak merespons dalam 10 detik", fixture.client.lastError())

        fixture.scheduler.advanceBy(ShizukuFileClient.RETRY_DELAYS_MS[0] - 1)
        assertEquals(listOf(1), fixture.gateway.bindAttempts)
        fixture.scheduler.advanceBy(1)
        assertEquals(listOf(1, 2), fixture.gateway.bindAttempts)
    }

    @Test
    fun retriesUseCappedBackoff() {
        val fixture = Fixture()
        fixture.client.start()
        fixture.scheduler.advanceBy(ShizukuFileClient.INITIAL_BIND_DELAY_MS)

        var activeAttempt = 1
        val expectedDelays = listOf(500L, 1_500L, 4_000L, 8_000L, 8_000L)
        expectedDelays.forEach { delay ->
            fixture.gateway.nullBinding(activeAttempt)
            val attemptsBeforeDelay = fixture.gateway.bindAttempts.size

            fixture.scheduler.advanceBy(delay - 1)
            assertEquals(attemptsBeforeDelay, fixture.gateway.bindAttempts.size)

            fixture.scheduler.advanceBy(1)
            activeAttempt += 1
            assertEquals(activeAttempt, fixture.gateway.bindAttempts.last())
        }

        assertEquals(listOf(1, 2, 3, 4, 5, 6), fixture.gateway.bindAttempts)
    }

    @Test
    fun staleCallbackCannotReplaceNewerAttempt() {
        val fixture = Fixture()
        fixture.client.start()
        fixture.scheduler.advanceBy(ShizukuFileClient.INITIAL_BIND_DELAY_MS)
        fixture.scheduler.advanceBy(ShizukuFileClient.BIND_TIMEOUT_MS)
        fixture.scheduler.advanceBy(ShizukuFileClient.RETRY_DELAYS_MS[0])

        assertEquals(listOf(1, 2), fixture.gateway.bindAttempts)

        fixture.gateway.connect(1, FakeUserServiceFiles())

        assertFalse(fixture.client.isReady())
        assertTrue(fixture.client.isBinding())
        assertEquals(
            listOf(UnbindCall(1, true), UnbindCall(1, true)),
            fixture.gateway.unbindCalls,
        )

        fixture.gateway.connect(2, FakeUserServiceFiles())
        assertTrue(fixture.client.isReady())
        assertFalse(fixture.client.isBinding())
    }

    @Test
    fun invalidServiceIsRejectedAndRetried() {
        val fixture = Fixture()
        fixture.client.start()
        fixture.scheduler.advanceBy(ShizukuFileClient.INITIAL_BIND_DELAY_MS)

        fixture.gateway.connect(1, FakeUserServiceFiles(alive = false))

        assertFalse(fixture.client.isReady())
        assertTrue(fixture.client.isBinding())
        assertEquals("UserService tidak mengembalikan binder yang aktif", fixture.client.lastError())
        assertEquals(listOf(UnbindCall(1, true)), fixture.gateway.unbindCalls)

        fixture.scheduler.advanceBy(ShizukuFileClient.RETRY_DELAYS_MS[0])
        assertEquals(listOf(1, 2), fixture.gateway.bindAttempts)
    }

    @Test
    fun permissionResultControlsBinding() {
        val fixture = Fixture(permission = false)
        fixture.client.start()

        assertFalse(fixture.client.isBinding())
        fixture.client.requestPermission()
        assertEquals(listOf(1001), fixture.gateway.permissionRequests)

        fixture.gateway.permissionResult(granted = false)
        assertFalse(fixture.client.isBinding())
        assertEquals("Izin Shizuku ditolak", fixture.client.lastError())

        fixture.gateway.permissionResult(granted = true)
        assertTrue(fixture.client.isBinding())
        fixture.scheduler.advanceBy(ShizukuFileClient.INITIAL_BIND_DELAY_MS)
        assertEquals(listOf(1), fixture.gateway.bindAttempts)
    }

    @Test
    fun binderDeathClearsReadyStateAndBinderReturnReconnects() {
        val fixture = Fixture()
        fixture.client.start()
        fixture.scheduler.advanceBy(ShizukuFileClient.INITIAL_BIND_DELAY_MS)
        fixture.gateway.connect(1, FakeUserServiceFiles())
        assertTrue(fixture.client.isReady())

        fixture.gateway.binderDead()

        assertFalse(fixture.client.isReady())
        assertFalse(fixture.client.isBinding())
        assertEquals("Layanan Shizuku terputus", fixture.client.lastError())

        fixture.gateway.binderReceived()
        assertTrue(fixture.client.isBinding())
        fixture.scheduler.advanceBy(ShizukuFileClient.INITIAL_BIND_DELAY_MS)
        assertEquals(listOf(1, 2), fixture.gateway.bindAttempts)

        fixture.gateway.connect(2, FakeUserServiceFiles())
        assertTrue(fixture.client.isReady())
    }

    @Test
    fun serviceFailureCallbacksScheduleRecovery() {
        val failureCallbacks = listOf<Pair<String, (FakeShizukuGateway, Int) -> Unit>>(
            "disconnect" to { gateway, attempt -> gateway.disconnect(attempt) },
            "binding died" to { gateway, attempt -> gateway.bindingDied(attempt) },
            "null binding" to { gateway, attempt -> gateway.nullBinding(attempt) },
        )

        failureCallbacks.forEach { (name, callback) ->
            val fixture = Fixture()
            fixture.client.start()
            fixture.scheduler.advanceBy(ShizukuFileClient.INITIAL_BIND_DELAY_MS)
            fixture.gateway.connect(1, FakeUserServiceFiles())

            callback(fixture.gateway, 1)

            assertFalse("$name must clear ready state", fixture.client.isReady())
            assertTrue("$name must schedule a retry", fixture.client.isBinding())
            fixture.scheduler.advanceBy(ShizukuFileClient.RETRY_DELAYS_MS[0])
            assertEquals("$name must create a new attempt", listOf(1, 2), fixture.gateway.bindAttempts)
        }
    }

    @Test
    fun stopCancelsPendingWorkAndUnbindsActiveAttempt() {
        val fixture = Fixture()
        fixture.client.start()
        fixture.scheduler.advanceBy(ShizukuFileClient.INITIAL_BIND_DELAY_MS)
        assertEquals(listOf(1), fixture.gateway.bindAttempts)

        fixture.client.stop()

        assertFalse(fixture.client.isReady())
        assertFalse(fixture.client.isBinding())
        assertEquals(listOf(UnbindCall(1, true)), fixture.gateway.unbindCalls)
        assertEquals(1, fixture.gateway.stopCalls)

        fixture.scheduler.advanceBy(
            ShizukuFileClient.BIND_TIMEOUT_MS + ShizukuFileClient.RETRY_DELAYS_MS.sum(),
        )
        assertEquals(listOf(1), fixture.gateway.bindAttempts)
    }

    @Test
    fun bindWaitsForStartAvailabilityAndPermission() {
        val fixture = Fixture(available = false, permission = false)

        fixture.client.bind()
        fixture.scheduler.advanceBy(ShizukuFileClient.INITIAL_BIND_DELAY_MS)
        assertTrue(fixture.gateway.bindAttempts.isEmpty())

        fixture.client.start()
        assertFalse(fixture.client.isBinding())

        fixture.gateway.available = true
        fixture.gateway.binderReceived()
        assertFalse(fixture.client.isBinding())

        fixture.gateway.permissionResult(granted = true)
        assertTrue(fixture.client.isBinding())
        fixture.scheduler.advanceBy(ShizukuFileClient.INITIAL_BIND_DELAY_MS)
        assertEquals(listOf(1), fixture.gateway.bindAttempts)
    }

    private class Fixture(
        available: Boolean = true,
        permission: Boolean = true,
    ) {
        val gateway = FakeShizukuGateway(available, permission)
        val scheduler = FakeTaskScheduler()
        var stateChanges = 0
        val client = ShizukuFileClient(gateway, scheduler) { stateChanges += 1 }
    }

    private data class UnbindCall(
        val attemptId: Int,
        val remove: Boolean,
    )

    private class FakeShizukuGateway(
        var available: Boolean,
        var permission: Boolean,
    ) : ShizukuGateway {
        private var listener: ShizukuGateway.Listener? = null
        val bindAttempts = mutableListOf<Int>()
        val unbindCalls = mutableListOf<UnbindCall>()
        val permissionRequests = mutableListOf<Int>()
        var stopCalls = 0
        var bindError: Throwable? = null

        override fun start(listener: ShizukuGateway.Listener) {
            check(this.listener == null)
            this.listener = listener
        }

        override fun stop() {
            stopCalls += 1
            listener = null
        }

        override fun isAvailable(): Boolean = available

        override fun hasPermission(): Boolean = permission

        override fun requestPermission(requestCode: Int) {
            permissionRequests += requestCode
        }

        override fun bindUserService(attemptId: Int) {
            bindAttempts += attemptId
            bindError?.let { throw it }
        }

        override fun unbindUserService(attemptId: Int, remove: Boolean) {
            unbindCalls += UnbindCall(attemptId, remove)
        }

        fun binderReceived() {
            listener?.onBinderReceived()
        }

        fun binderDead() {
            listener?.onBinderDead()
        }

        fun permissionResult(granted: Boolean, requestCode: Int = 1001) {
            permission = granted
            listener?.onPermissionResult(requestCode, granted)
        }

        fun connect(attemptId: Int, service: UserServiceFiles?) {
            listener?.onServiceConnected(attemptId, service)
        }

        fun disconnect(attemptId: Int) {
            listener?.onServiceDisconnected(attemptId)
        }

        fun bindingDied(attemptId: Int) {
            listener?.onBindingDied(attemptId)
        }

        fun nullBinding(attemptId: Int) {
            listener?.onNullBinding(attemptId)
        }
    }

    private class FakeTaskScheduler : TaskScheduler {
        private data class Entry(
            val dueAt: Long,
            val sequence: Long,
            val action: () -> Unit,
            var cancelled: Boolean = false,
        )

        private val entries = mutableListOf<Entry>()
        private var now = 0L
        private var nextSequence = 0L

        override fun schedule(delayMillis: Long, action: () -> Unit): ScheduledTask {
            val entry = Entry(
                dueAt = now + delayMillis.coerceAtLeast(0L),
                sequence = nextSequence++,
                action = action,
            )
            entries += entry
            return ScheduledTask { entry.cancelled = true }
        }

        fun advanceBy(durationMillis: Long) {
            require(durationMillis >= 0L)
            val target = now + durationMillis

            while (true) {
                val next = entries
                    .asSequence()
                    .filter { !it.cancelled && it.dueAt <= target }
                    .minWithOrNull(compareBy<Entry> { it.dueAt }.thenBy { it.sequence })
                    ?: break

                entries.remove(next)
                now = next.dueAt
                next.action()
            }

            now = target
            entries.removeAll { it.cancelled }
        }
    }

    private class FakeUserServiceFiles(
        private val alive: Boolean = true,
    ) : UserServiceFiles {
        override fun isAlive(): Boolean = alive
        override fun copyFile(source: String, destination: String): Boolean = true
        override fun replaceFile(source: String, destination: String): Boolean = true
        override fun deleteFile(path: String): Boolean = true
        override fun exists(path: String): Boolean = true
        override fun mkdirs(path: String): Boolean = true
        override fun listFiles(path: String): Array<String> = emptyArray()
        override fun readText(path: String): String = ""
        override fun writeTextAtomic(path: String, content: String): Boolean = true
        override fun sha1(path: String): String = ""
        override fun sha256(path: String): String = ""
        override fun lastError(): String = ""
    }
}
