package com.titotfp.wuwaid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParserTest {
    @Test
    fun parsesCurrentReleaseAssetAndDigest() {
        val parsed = ReleaseParser.parse(RELEASE_JSON)
        val release = ReleaseParser.finish(parsed)

        assertEquals("v3.5.1-id.3", release.tag)
        assertEquals(48_341_712L, release.size)
        assertEquals(HASH, release.sha256)
        assertEquals(
            "https://github.com/TitoTFP/WuwaID/releases/download/v3.5.1-id.3/${ReleaseParser.PATCH_ASSET}",
            release.assetUrl,
        )
    }

    @Test
    fun blankTitleFallsBackToTag() {
        val parsed = ReleaseParser.parse(RELEASE_JSON.replace("Wuthering Waves Lokalisasi Bahasa Indonesia v.3.5.1-id.3", ""))

        assertEquals("v3.5.1-id.3", parsed.title)
    }

    @Test
    fun usesExactChecksumFallbackIncludingWildcardAndUppercaseHash() {
        val parsed = ReleaseParser.parse(RELEASE_JSON.replace(DIGEST_FIELD, ""))
        assertNull(parsed.digest)
        val sums = """
            ${HASH.uppercase()}  *${ReleaseParser.PATCH_ASSET}
            0fd4697de717ff701568307903ecd630498abb3c101eb9ad77eca34507b47765  winhttp.dll
        """.trimIndent()

        val release = ReleaseParser.finish(parsed, ReleaseParser.checksumForPatch(sums))

        assertEquals(HASH, release.sha256)
    }

    @Test
    fun malformedEmbeddedDigestFallsBackInsteadOfBeingTrusted() {
        val parsed = ReleaseParser.parse(RELEASE_JSON.replace(HASH, "not-a-valid-hash"))

        assertNull(parsed.digest)
        assertEquals(HASH, ReleaseParser.finish(parsed, HASH).sha256)
    }

    @Test
    fun rejectsReleaseWithoutExpectedAsset() {
        val wrong = RELEASE_JSON.replace(ReleaseParser.PATCH_ASSET, "WuWa_ID_99_P.pak")

        assertThrows(IllegalStateException::class.java) { ReleaseParser.parse(wrong) }
    }

    @Test
    fun checksumParserDoesNotAcceptAnotherFilenameOrMalformedHash() {
        assertNull(ReleaseParser.checksumForPatch("$HASH  other.pak"))
        assertNull(ReleaseParser.checksumForPatch("not-a-hash  ${ReleaseParser.PATCH_ASSET}"))
    }

    @Test
    fun finishRejectsMissingHashInsecureUrlAndInvalidSize() {
        val parsed = ReleaseParser.parse(RELEASE_JSON.replace(DIGEST_FIELD, ""))

        assertThrows(IllegalStateException::class.java) { ReleaseParser.finish(parsed) }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseParser.finish(parsed.copy(assetUrl = "http://example.invalid/patch"), HASH)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseParser.finish(parsed.copy(size = 0), HASH)
        }
    }

    @Test
    fun normalizationKeepsChecksumMatchingCaseInsensitive() {
        val parsed = ReleaseParser.parse(RELEASE_JSON)

        assertTrue(ReleaseParser.finish(parsed).sha256.all { it.isDigit() || it in 'a'..'f' })
    }

    companion object {
        private const val HASH = "88eb1837d99208d4d3658e893a2e38559f05f957fcd6931df70dbf4cb8e2dad7"
        private const val DIGEST_FIELD = "\"digest\":\"sha256:$HASH\","
        private val RELEASE_JSON = """
            {
              "tag_name":"v3.5.1-id.3",
              "name":"Wuthering Waves Lokalisasi Bahasa Indonesia v.3.5.1-id.3",
              "published_at":"2026-07-18T12:54:15Z",
              "body":"Menambah translasi yang kurang",
              "assets":[
                {
                  "name":"${ReleaseParser.PATCH_ASSET}",
                  "size":48341712,
                  $DIGEST_FIELD
                  "browser_download_url":"https://github.com/TitoTFP/WuwaID/releases/download/v3.5.1-id.3/${ReleaseParser.PATCH_ASSET}"
                },
                {
                  "name":"SHA256sums.txt",
                  "size":184,
                  "browser_download_url":"https://github.com/TitoTFP/WuwaID/releases/download/v3.5.1-id.3/SHA256sums.txt"
                }
              ]
            }
        """.trimIndent()
    }
}
