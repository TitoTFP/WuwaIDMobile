package com.titotfp.wuwaid

import android.content.Context
import org.json.JSONObject

internal interface ReleasePreferences {
    fun putString(key: String, value: String)
    fun getString(key: String): String?
}

private class AndroidReleasePreferences(context: Context) : ReleasePreferences {
    private val preferences = context.getSharedPreferences("patch_release", Context.MODE_PRIVATE)

    override fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun getString(key: String): String? = preferences.getString(key, null)
}

class ReleaseStore internal constructor(
    private val preferences: ReleasePreferences,
) {
    constructor(context: Context) : this(AndroidReleasePreferences(context))

    fun save(release: PatchRelease) {
        validate(release)
        val json = JSONObject()
            .put("tag", release.tag)
            .put("title", release.title)
            .put("publishedAt", release.publishedAt)
            .put("notes", release.notes)
            .put("assetUrl", release.assetUrl)
            .put("size", release.size)
            .put("sha256", release.sha256.lowercase())
        preferences.putString(KEY, json.toString())
    }

    fun load(): PatchRelease? = runCatching {
        val json = JSONObject(preferences.getString(KEY) ?: return null)
        PatchRelease(
            tag = json.getString("tag"),
            title = json.getString("title"),
            publishedAt = json.getString("publishedAt"),
            notes = json.getString("notes"),
            assetUrl = json.getString("assetUrl"),
            size = json.getLong("size"),
            sha256 = json.getString("sha256").lowercase(),
        ).also(::validate)
    }.getOrNull()

    private fun validate(release: PatchRelease) {
        require(release.tag.isNotBlank()) { "Tag rilis cache kosong" }
        require(release.assetUrl.startsWith("https://")) { "URL rilis cache wajib HTTPS" }
        require(release.size > 0L) { "Ukuran rilis cache tidak valid" }
        require(SHA256.matches(release.sha256)) { "SHA-256 rilis cache tidak valid" }
    }

    companion object {
        private const val KEY = "latest"
        private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    }
}
