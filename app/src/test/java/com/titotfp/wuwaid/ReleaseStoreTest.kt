package com.titotfp.wuwaid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReleaseStoreTest {
    @Test
    fun saveAndLoadRoundTripNormalizesHash() {
        val preferences = FakeReleasePreferences()
        val store = ReleaseStore(preferences)
        val release = validRelease().copy(sha256 = HASH.uppercase())

        store.save(release)

        assertEquals(validRelease(), store.load())
        assertEquals(1, preferences.putCalls)
    }

    @Test
    fun missingCacheReturnsNull() {
        assertNull(ReleaseStore(FakeReleasePreferences()).load())
    }

    @Test
    fun malformedOrIncompleteJsonReturnsNull() {
        val malformed = FakeReleasePreferences("{broken")
        val incomplete = FakeReleasePreferences("{\"tag\":\"v1\"}")

        assertNull(ReleaseStore(malformed).load())
        assertNull(ReleaseStore(incomplete).load())
    }

    @Test
    fun invalidCachedSecurityFieldsAreRejected() {
        assertNull(ReleaseStore(FakeReleasePreferences(json(assetUrl = "http://example.invalid/file"))).load())
        assertNull(ReleaseStore(FakeReleasePreferences(json(size = 0))).load())
        assertNull(ReleaseStore(FakeReleasePreferences(json(sha256 = "not-a-hash"))).load())
        assertNull(ReleaseStore(FakeReleasePreferences(json(tag = ""))).load())
    }

    @Test
    fun invalidReleaseIsNotSaved() {
        val preferences = FakeReleasePreferences()
        val store = ReleaseStore(preferences)

        assertThrows(IllegalArgumentException::class.java) {
            store.save(validRelease().copy(assetUrl = "http://example.invalid/file"))
        }
        assertNull(preferences.value)
        assertEquals(0, preferences.putCalls)
    }

    private fun validRelease(): PatchRelease = PatchRelease(
        tag = "v3.5.1-id.3",
        title = "Patch",
        publishedAt = "2026-07-18T00:00:00Z",
        notes = "Catatan",
        assetUrl = "https://example.invalid/patch.pak",
        size = 1234,
        sha256 = HASH,
    )

    private fun json(
        tag: String = "v3.5.1-id.3",
        assetUrl: String = "https://example.invalid/patch.pak",
        size: Long = 1234,
        sha256: String = HASH,
    ): String = """
        {
          "tag":"$tag",
          "title":"Patch",
          "publishedAt":"2026-07-18T00:00:00Z",
          "notes":"Catatan",
          "assetUrl":"$assetUrl",
          "size":$size,
          "sha256":"$sha256"
        }
    """.trimIndent()

    private class FakeReleasePreferences(
        var value: String? = null,
    ) : ReleasePreferences {
        var putCalls = 0

        override fun putString(key: String, value: String) {
            assertEquals("latest", key)
            putCalls += 1
            this.value = value
        }

        override fun getString(key: String): String? {
            assertEquals("latest", key)
            return value
        }
    }

    companion object {
        private const val HASH = "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd"
    }
}
