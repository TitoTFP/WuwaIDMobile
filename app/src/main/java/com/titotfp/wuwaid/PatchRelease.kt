package com.titotfp.wuwaid

import org.json.JSONArray
import org.json.JSONObject

data class PatchRelease(
    val tag: String,
    val title: String,
    val publishedAt: String,
    val notes: String,
    val assetUrl: String,
    val size: Long,
    val sha256: String,
)

data class ParsedRelease(
    val tag: String,
    val title: String,
    val publishedAt: String,
    val notes: String,
    val assetUrl: String,
    val size: Long,
    val digest: String?,
    val checksumsUrl: String?,
)

object ReleaseParser {
    const val PATCH_ASSET = "pakchunk0-ID-WindowsNoEditor_1000_P.pak"
    private const val CHECKSUM_ASSET = "SHA256sums.txt"
    private val sha256Regex = Regex("^[0-9a-fA-F]{64}$")

    fun parse(json: String): ParsedRelease {
        val root = JSONObject(json)
        val assets = root.getJSONArray("assets")
        val patch = findAsset(assets, PATCH_ASSET)
            ?: error("Rilis terbaru tidak memiliki $PATCH_ASSET")
        val sums = findAsset(assets, CHECKSUM_ASSET)
        val digest = patch.optString("digest")
            .removePrefix("sha256:")
            .takeIf(sha256Regex::matches)
            ?.lowercase()

        return ParsedRelease(
            tag = root.getString("tag_name"),
            title = root.optString("name", root.getString("tag_name")),
            publishedAt = root.optString("published_at"),
            notes = root.optString("body"),
            assetUrl = patch.getString("browser_download_url"),
            size = patch.getLong("size"),
            digest = digest,
            checksumsUrl = sums?.optString("browser_download_url")?.takeIf(String::isNotBlank),
        )
    }

    fun checksumForPatch(text: String): String? = text.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { it.split(Regex("\\s+"), limit = 2) }
        .firstOrNull { parts ->
            parts.size == 2 && parts[1].removePrefix("*") == PATCH_ASSET && sha256Regex.matches(parts[0])
        }
        ?.first()
        ?.lowercase()

    fun finish(parsed: ParsedRelease, fallbackChecksum: String? = null): PatchRelease {
        val hash = parsed.digest ?: fallbackChecksum?.takeIf(sha256Regex::matches)?.lowercase()
            ?: error("SHA-256 $PATCH_ASSET tidak tersedia")
        require(parsed.assetUrl.startsWith("https://")) { "URL patch wajib HTTPS" }
        require(parsed.size > 0) { "Ukuran patch tidak valid" }
        return PatchRelease(
            tag = parsed.tag,
            title = parsed.title,
            publishedAt = parsed.publishedAt,
            notes = parsed.notes,
            assetUrl = parsed.assetUrl,
            size = parsed.size,
            sha256 = hash,
        )
    }

    private fun findAsset(assets: JSONArray, name: String): JSONObject? {
        for (index in 0 until assets.length()) {
            val item = assets.getJSONObject(index)
            if (item.optString("name") == name) return item
        }
        return null
    }
}
