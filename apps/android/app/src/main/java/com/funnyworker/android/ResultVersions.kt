package com.funnyworker.android

import org.json.JSONObject

/** A version is scoped to a result ID; list updates never choose another version implicitly. */
object ResultVersions {
    fun versions(rows: List<JSONObject>, id: String): List<JSONObject> = rows.filter {
        val m = it.optJSONObject("manifest")
        m != null && m.text("id") == id && m.sequence("version")?.let { v -> v in 1..1000 } == true
    }.distinctBy { it.getJSONObject("manifest").getInt("version") }.sortedByDescending { it.getJSONObject("manifest").getInt("version") }
    fun latest(rows: List<JSONObject>): List<JSONObject> = rows.mapNotNull { it.optJSONObject("manifest")?.text("id") }
        .filter(ServiceIdentity::validId).distinct().mapNotNull { versions(rows, it).firstOrNull() }
}
