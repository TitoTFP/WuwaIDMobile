package com.titotfp.wuwaid

import org.json.JSONArray
import org.json.JSONObject

data class AppRelease(
    val tag: String,
    val title: String,
    val publishedAt: String,
    val notes: String,
    val assetUrl: String,
    val size: Long,
    val sha256: String,
)

data class ParsedAppRelease(
    val tag: String,
    val title: String,
    val publishedAt: String,
    val notes: String,
    val assetUrl: String,
    val size: Long,
    val digest: String?,
    val checksumsUrl: String?,
)

object AppReleaseParser {
    private const val CHECKSUM_ASSET = "SHA256sums.txt"
    private val sha256Regex = Regex("^[0-9a-fA-F]{64}$")

    fun parseLatest(json: String, currentVersion: String): ParsedAppRelease? {
        val current = Version.parse(currentVersion)
            ?: error("Versi aplikasi tidak valid: $currentVersion")
        val releases = JSONArray(json)
        var best: Pair<Version, ParsedAppRelease>? = null

        for (index in 0 until releases.length()) {
            val root = releases.getJSONObject(index)
            if (root.optBoolean("draft")) continue
            val tag = root.optString("tag_name")
            val version = Version.parse(tag) ?: continue
            if (version <= current || best?.first?.let { version <= it } == true) continue

            val assets = root.optJSONArray("assets") ?: continue
            val assetName = assetName(tag)
            val apk = findAsset(assets, assetName) ?: continue
            val sums = findAsset(assets, CHECKSUM_ASSET)
            val digest = apk.optString("digest")
                .removePrefix("sha256:")
                .takeIf(sha256Regex::matches)
                ?.lowercase()

            best = version to ParsedAppRelease(
                tag = tag,
                title = root.optString("name").ifBlank { tag },
                publishedAt = root.optString("published_at"),
                notes = root.optString("body"),
                assetUrl = apk.optString("browser_download_url"),
                size = apk.optLong("size"),
                digest = digest,
                checksumsUrl = sums?.optString("browser_download_url")?.takeIf(String::isNotBlank),
            )
        }
        return best?.second
    }

    fun finish(parsed: ParsedAppRelease, fallbackChecksum: String? = null): AppRelease {
        val hash = parsed.digest ?: fallbackChecksum?.takeIf(sha256Regex::matches)?.lowercase()
            ?: error("SHA-256 ${assetName(parsed.tag)} tidak tersedia")
        require(parsed.assetUrl.startsWith("https://")) { "URL APK update wajib HTTPS" }
        require(parsed.size > 0) { "Ukuran APK update tidak valid" }
        return AppRelease(
            tag = parsed.tag,
            title = parsed.title,
            publishedAt = parsed.publishedAt,
            notes = parsed.notes,
            assetUrl = parsed.assetUrl,
            size = parsed.size,
            sha256 = hash,
        )
    }

    fun checksumForApk(text: String, tag: String): String? {
        val expected = assetName(tag)
        return text.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { it.split(Regex("\\s+"), limit = 2) }
            .firstOrNull { parts ->
                parts.size == 2 && parts[1].removePrefix("*") == expected && sha256Regex.matches(parts[0])
            }
            ?.first()
            ?.lowercase()
    }

    fun assetName(tag: String): String = "WuwaID-Mobile-$tag.apk"

    private fun findAsset(assets: JSONArray, name: String): JSONObject? {
        for (index in 0 until assets.length()) {
            val item = assets.getJSONObject(index)
            if (item.optString("name") == name) return item
        }
        return null
    }

    private data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
        override fun compareTo(other: Version): Int = compareValuesBy(
            this,
            other,
            Version::major,
            Version::minor,
            Version::patch,
        )

        companion object {
            fun parse(value: String): Version? {
                val parts = value.removePrefix("v")
                    .substringBefore('-')
                    .substringBefore('+')
                    .split('.')
                if (parts.size != 3) return null
                val numbers = parts.map { it.toIntOrNull() ?: return null }
                return Version(numbers[0], numbers[1], numbers[2])
            }
        }
    }
}
