package com.titotfp.wuwaid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorGuidanceTest {
    @Test
    fun detectsXiaomiFamily() {
        assertTrue(VendorGuidance.isXiaomiFamily("Xiaomi"))
        assertTrue(VendorGuidance.isXiaomiFamily("POCO"))
        assertTrue(VendorGuidance.isXiaomiFamily("Redmi"))
        assertTrue(VendorGuidance.isXiaomiFamily("unknown", "Xiaomi"))
        assertTrue(VendorGuidance.isXiaomiFamily("unknown", "POCO"))
        assertFalse(VendorGuidance.isXiaomiFamily("Samsung"))
        assertFalse(VendorGuidance.isXiaomiFamily("Google"))
    }

    @Test
    fun detectsTranssionFamily() {
        assertTrue(VendorGuidance.isTranssionFamily("INFINIX"))
        assertTrue(VendorGuidance.isTranssionFamily("TECNO"))
        assertTrue(VendorGuidance.isTranssionFamily("itel"))
        assertTrue(VendorGuidance.isTranssionFamily("unknown", "Infinix"))
        assertTrue(VendorGuidance.isTranssionFamily("unknown", "TECNO"))
        assertFalse(VendorGuidance.isTranssionFamily("Samsung"))
        assertFalse(VendorGuidance.isTranssionFamily("Xiaomi"))
    }

    @Test
    fun returnsPermissionGuidanceForXiaomiOnly() {
        val xiaomiGuidance =
            VendorGuidance.getGuidance(
                VendorGuidance.IssueType.SHIZUKU_PERMISSION_DENIED,
                "Xiaomi",
            )
        assertNotNull(xiaomiGuidance)
        assertTrue(xiaomiGuidance!!.contains("USB Debugging (Security settings)"))

        val pocoGuidance =
            VendorGuidance.getGuidance(
                VendorGuidance.IssueType.SHIZUKU_PERMISSION_DENIED,
                "POCO",
            )
        assertNotNull(pocoGuidance)

        val samsungGuidance =
            VendorGuidance.getGuidance(
                VendorGuidance.IssueType.SHIZUKU_PERMISSION_DENIED,
                "Samsung",
            )
        assertNull(samsungGuidance)
    }

    @Test
    fun returnsTimeoutGuidanceForTranssionAndXiaomi() {
        val infinixGuidance =
            VendorGuidance.getGuidance(
                VendorGuidance.IssueType.SHIZUKU_SERVICE_TIMEOUT,
                "Infinix",
            )
        assertNotNull(infinixGuidance)
        assertTrue(infinixGuidance!!.contains("Infinix/TECNO"))

        val tecnoGuidance =
            VendorGuidance.getGuidance(
                VendorGuidance.IssueType.SHIZUKU_SERVICE_TIMEOUT,
                "TECNO",
            )
        assertNotNull(tecnoGuidance)

        val xiaomiGuidance =
            VendorGuidance.getGuidance(
                VendorGuidance.IssueType.SHIZUKU_SERVICE_TIMEOUT,
                "Xiaomi",
            )
        assertNotNull(xiaomiGuidance)
        assertTrue(xiaomiGuidance!!.contains("MIUI/HyperOS"))

        val googleGuidance =
            VendorGuidance.getGuidance(
                VendorGuidance.IssueType.SHIZUKU_SERVICE_TIMEOUT,
                "Google",
            )
        assertNull(googleGuidance)
    }
}
