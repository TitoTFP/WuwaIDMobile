package com.titotfp.wuwaid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppReleaseParserTest {
    @Test
    fun selectsNewestVersionIncludingPrerelease() {
        val parsed = AppReleaseParser.parseLatest(RELEASES_JSON, "0.1.0-debug")!!
        val release = AppReleaseParser.finish(parsed)

        assertEquals("v0.2.0", release.tag)
        assertEquals(900_000L, release.size)
        assertEquals(HASH_B, release.sha256)
        assertEquals("https://github.com/TitoTFP/WuwaIDMobile/releases/download/v0.2.0/WuwaID-Mobile-v0.2.0.apk", release.assetUrl)
    }

    @Test
    fun returnsNullWhenCurrentVersionIsNewest() {
        assertNull(AppReleaseParser.parseLatest(RELEASES_JSON, "0.2.0"))
    }

    @Test
    fun usesChecksumFallbackForExactApk() {
        val parsed = AppReleaseParser.parseLatest(RELEASES_JSON.replace("sha256:$HASH_B", ""), "0.1.0")!!
        val sums = "$HASH_A  other.apk\n$HASH_B  WuwaID-Mobile-v0.2.0.apk"

        val release = AppReleaseParser.finish(parsed, AppReleaseParser.checksumForApk(sums, parsed.tag))

        assertEquals(HASH_B, release.sha256)
    }

    @Test
    fun ignoresDraftRelease() {
        val json = RELEASES_JSON.replaceFirst("\"draft\":false", "\"draft\":true")

        assertNull(AppReleaseParser.parseLatest(json, "0.1.1"))
    }

    @Test
    fun requiresExactVersionedApkAsset() {
        val json = RELEASES_JSON.replace("WuwaID-Mobile-v0.2.0.apk", "wrong.apk")

        assertNull(AppReleaseParser.parseLatest(json, "0.1.1"))
    }

    companion object {
        private const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private val RELEASES_JSON = """
            [
              {
                "tag_name":"v0.2.0",
                "name":"WuwaID Mobile v0.2.0",
                "published_at":"2026-08-01T00:00:00Z",
                "body":"Update baru",
                "draft":false,
                "prerelease":true,
                "assets":[
                  {
                    "name":"WuwaID-Mobile-v0.2.0.apk",
                    "size":900000,
                    "digest":"sha256:$HASH_B",
                    "browser_download_url":"https://github.com/TitoTFP/WuwaIDMobile/releases/download/v0.2.0/WuwaID-Mobile-v0.2.0.apk"
                  },
                  {
                    "name":"SHA256sums.txt",
                    "browser_download_url":"https://github.com/TitoTFP/WuwaIDMobile/releases/download/v0.2.0/SHA256sums.txt"
                  }
                ]
              },
              {
                "tag_name":"v0.1.1",
                "name":"WuwaID Mobile v0.1.1",
                "draft":false,
                "assets":[
                  {
                    "name":"WuwaID-Mobile-v0.1.1.apk",
                    "size":800000,
                    "digest":"sha256:$HASH_A",
                    "browser_download_url":"https://github.com/TitoTFP/WuwaIDMobile/releases/download/v0.1.1/WuwaID-Mobile-v0.1.1.apk"
                  }
                ]
              }
            ]
        """.trimIndent()
    }
}
