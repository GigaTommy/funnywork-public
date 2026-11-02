package com.funnyworker.android
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class ResultVersionsTest {
    private fun row(id: String, version: Number) = JSONObject().put("manifest", JSONObject().put("id", id).put("version", version))
    @Test fun versionsAreBoundToOneResultAndLatestDoesNotDuplicateFiles() {
        val rows = listOf(row("one", 1), row("two", 8), row("one", 2), row("one", 1), row("one", 2.5), row("one", 0))
        assertEquals(listOf(2, 1), ResultVersions.versions(rows, "one").map { it.getJSONObject("manifest").getInt("version") })
        assertEquals(listOf("one", "two"), ResultVersions.latest(rows).map { it.getJSONObject("manifest").text("id") })
        assertTrue(ResultVersions.versions(rows, "missing").isEmpty())
    }
}
