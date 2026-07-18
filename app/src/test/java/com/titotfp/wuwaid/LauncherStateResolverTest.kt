package com.titotfp.wuwaid

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherStateResolverTest {
    @Test
    fun resolvesStatePriorityAndUpdateCases() {
        assertEquals(LauncherStatus.NEEDS_SHIZUKU, resolve(shizukuAvailable = false))
        assertEquals(LauncherStatus.NEEDS_SHIZUKU, resolve(shizukuPermission = false))
        assertEquals(LauncherStatus.NEEDS_SHIZUKU, resolve(serviceReady = false))
        assertEquals(LauncherStatus.GAME_NOT_READY, resolve(resourceVersion = null))
        assertEquals(LauncherStatus.CONFLICT, resolve(conflicts = listOf("Mount/other.txt")))
        assertEquals(LauncherStatus.NOT_INSTALLED, resolve(currentHealthy = false))
        assertEquals(LauncherStatus.UPDATE_AVAILABLE, resolve(currentHealthy = false, anyOwnedPatch = true))
        assertEquals(LauncherStatus.UPDATE_AVAILABLE, resolve(matchesLatest = false))
        assertEquals(LauncherStatus.READY, resolve())
        assertEquals(LauncherStatus.READY, resolve(releaseAvailable = false, matchesLatest = false))
    }

    private fun resolve(
        shizukuAvailable: Boolean = true,
        shizukuPermission: Boolean = true,
        serviceReady: Boolean = true,
        resourceVersion: String? = "3.5.1",
        conflicts: List<String> = emptyList(),
        anyOwnedPatch: Boolean = false,
        currentHealthy: Boolean = true,
        releaseAvailable: Boolean = true,
        matchesLatest: Boolean = true,
    ): LauncherStatus = LauncherStateResolver.resolve(
        StateInputs(
            shizukuAvailable,
            shizukuPermission,
            serviceReady,
            resourceVersion,
            conflicts,
            anyOwnedPatch,
            currentHealthy,
            releaseAvailable,
            matchesLatest,
        ),
    )
}
