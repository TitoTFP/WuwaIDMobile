package com.titotfp.wuwaid

internal object VendorGuidance {
    enum class IssueType {
        SHIZUKU_PERMISSION_DENIED,
        SHIZUKU_SERVICE_TIMEOUT,
    }

    fun isXiaomiFamily(
        manufacturer: String,
        brand: String = manufacturer,
    ): Boolean {
        val m = manufacturer.lowercase()
        val b = brand.lowercase()
        return m.contains("xiaomi") || m.contains("poco") || m.contains("redmi") ||
            b.contains("xiaomi") || b.contains("poco") || b.contains("redmi")
    }

    fun isTranssionFamily(
        manufacturer: String,
        brand: String = manufacturer,
    ): Boolean {
        val m = manufacturer.lowercase()
        val b = brand.lowercase()
        return m.contains("infinix") || m.contains("tecno") || m.contains("itel") ||
            b.contains("infinix") || b.contains("tecno") || b.contains("itel")
    }

    fun getGuidance(
        issue: IssueType,
        manufacturer: String,
        brand: String = manufacturer,
    ): String? =
        when (issue) {
            IssueType.SHIZUKU_PERMISSION_DENIED -> {
                if (isXiaomiFamily(manufacturer, brand)) {
                    "Perangkat Xiaomi/POCO/Redmi mewajibkan opsi 'USB Debugging (Security settings)' diaktifkan di Opsi Pengembang (Developer Options) agar Shizuku dapat memberikan izin."
                } else {
                    null
                }
            }

            IssueType.SHIZUKU_SERVICE_TIMEOUT -> {
                if (isTranssionFamily(manufacturer, brand)) {
                    "Perangkat Infinix/TECNO membatasi proses latar belakang Shizuku. Buka Pengaturan > Manajemen Baterai, matikan optimasi baterai untuk Shizuku & WuwaID Mobile, serta nonaktifkan pembatasan child process di Opsi Pengembang jika tersedia."
                } else if (isXiaomiFamily(manufacturer, brand)) {
                    "Pastikan Shizuku tidak dihentikan oleh penghemat baterai MIUI/HyperOS dan tetap berjalan di latar belakang."
                } else {
                    null
                }
            }
        }
}
