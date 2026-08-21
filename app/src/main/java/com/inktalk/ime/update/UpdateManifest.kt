package com.inktalk.ime.update

import org.json.JSONObject

data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val releaseNotes: String,
)

object UpdateManifestParser {
    fun parse(raw: String): UpdateManifest? {
        return try {
            val root = JSONObject(raw)
            if (root.optInt("schema") != 1) return null
            val versionCode = root.getInt("versionCode")
            val versionName = root.getString("versionName").trim()
            val apkUrl = root.getString("apkUrl").trim()
            val sha256 = root.getString("sha256").trim().lowercase()
            val notes = root.optString("releaseNotes").trim()
            if (versionCode <= 0 || versionName.isEmpty() || !apkUrl.startsWith("https://") ||
                !sha256.matches(Regex("[0-9a-f]{64}"))
            ) null else UpdateManifest(versionCode, versionName, apkUrl, sha256, notes)
        } catch (_: Exception) {
            null
        }
    }
}
