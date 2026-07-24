package com.titotfp.wuwaid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AppReleaseParserTest {
    @Test
    fun selectsNewestVersionIncludingPrereleaseCurrentSuffix() {
        val parsed = AppReleaseParser.parseLatest(RELEASES_JSON, "0.1.0-debug")!!
        val release = AppReleaseParser.finish(parsed)

        assertEquals("v0.2.0", release.tag)
        assertEquals(900_000L, release.size)
        assertEquals(HASH_B, release.sha256)
        assertEquals("https://github.com/TitoTFP/WuwaIDMobile/releases/download/v0.2.0/WuwaID-Mobile-v0.2.0.apk", release.assetUrl)
    }

    @Test
    fun comparesVersionsNumericallyRatherThanLexically() {
        val json = releasesJson(
            releaseJson("v0.9.0", HASH_A),
            releaseJson("v0.10.0", HASH_B),
        )

        assertEquals("v0.10.0", AppReleaseParser.parseLatest(json, "0.1.0")!!.tag)
    }

    @Test
    fun returnsNullWhenCurrentVersionIsNewest() {
        assertNull(AppReleaseParser.parseLatest(RELEASES_JSON, "0.2.0"))
    }

    @Test
    fun usesChecksumFallbackForExactApkIncludingWildcardAndUppercase() {
        val parsed = AppReleaseParser.parseLatest(RELEASES_JSON.replace("sha256:$HASH_B", ""), "0.1.0")!!
        val sums = "$HASH_A  other.apk\n${HASH_B.uppercase()}  *WuwaID-Mobile-v0.2.0.apk"

        val release = AppReleaseParser.finish(parsed, AppReleaseParser.checksumForApk(sums, parsed.tag))

        assertEquals(HASH_B, release.sha256)
    }

    @Test
    fun ignoresDraftMalformedAndAssetlessReleases() {
        val draft = releaseJson("v0.4.0", HASH_B, draft = true)
        val malformed = releaseJson("not-a-version", HASH_B)
        val assetless = """{"tag_name":"v0.3.0","draft":false,"assets":[]}"""
        val valid = releaseJson("v0.2.0", HASH_A)

        val parsed = AppReleaseParser.parseLatest(releasesJson(draft, malformed, assetless, valid), "0.1.0")

        assertEquals("v0.2.0", parsed!!.tag)
    }

    @Test
    fun blankTitleFallsBackToTag() {
        val json = releasesJson(releaseJson("v0.2.0", HASH_B, title = ""))

        assertEquals("v0.2.0", AppReleaseParser.parseLatest(json, "0.1.0")!!.title)
    }

    @Test
    fun requiresExactVersionedApkAsset() {
        val json = RELEASES_JSON.replace("WuwaID-Mobile-v0.2.0.apk", "wrong.apk")

        assertNull(AppReleaseParser.parseLatest(json, "0.1.1"))
    }

    @Test
    fun malformedCurrentVersionIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            AppReleaseParser.parseLatest(RELEASES_JSON, "version-terbaru")
        }
    }

    @Test
    fun finishRejectsMissingHashInsecureUrlAndInvalidSize() {
        val parsed = AppReleaseParser.parseLatest(RELEASES_JSON.replace("sha256:$HASH_B", ""), "0.1.0")!!

        assertThrows(IllegalStateException::class.java) { AppReleaseParser.finish(parsed) }
        assertThrows(IllegalArgumentException::class.java) {
            AppReleaseParser.finish(parsed.copy(assetUrl = "http://example.invalid/app.apk"), HASH_B)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppReleaseParser.finish(parsed.copy(size = 0), HASH_B)
        }
    }

    @Test
    fun checksumParserRejectsWrongFilenameAndMalformedHash() {
        assertNull(AppReleaseParser.checksumForApk("$HASH_A  other.apk", "v0.2.0"))
        assertNull(AppReleaseParser.checksumForApk("bad  WuwaID-Mobile-v0.2.0.apk", "v0.2.0"))
    }

    private fun releasesJson(vararg releases: String): String = releases.joinToString(",", "[", "]")

    private fun releaseJson(
        tag: String,
        hash: String,
        draft: Boolean = false,
        title: String = "Release $tag",
    ): String = """
        {
          "tag_name":"$tag",
          "name":"$title",
          "draft":$draft,
          "assets":[
            {
              "name":"${AppReleaseParser.assetName(tag)}",
              "size":900000,
              "digest":"sha256:$hash",
              "browser_download_url":"https://example.invalid/${AppReleaseParser.assetName(tag)}"
            }
          ]
        }
    """.trimIndent()

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
