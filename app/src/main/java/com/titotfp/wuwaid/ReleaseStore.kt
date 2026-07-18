package com.titotfp.wuwaid

import android.content.Context
import org.json.JSONObject

class ReleaseStore(context: Context) {
    private val preferences = context.getSharedPreferences("patch_release", Context.MODE_PRIVATE)

    fun save(release: PatchRelease) {
        val json = JSONObject()
            .put("tag", release.tag)
            .put("title", release.title)
            .put("publishedAt", release.publishedAt)
            .put("notes", release.notes)
            .put("assetUrl", release.assetUrl)
            .put("size", release.size)
            .put("sha256", release.sha256)
        preferences.edit().putString(KEY, json.toString()).apply()
    }

    fun load(): PatchRelease? = runCatching {
        val json = JSONObject(preferences.getString(KEY, null) ?: return null)
        PatchRelease(
            tag = json.getString("tag"),
            title = json.getString("title"),
            publishedAt = json.getString("publishedAt"),
            notes = json.getString("notes"),
            assetUrl = json.getString("assetUrl"),
            size = json.getLong("size"),
            sha256 = json.getString("sha256"),
        )
    }.getOrNull()

    companion object {
        private const val KEY = "latest"
    }
}
