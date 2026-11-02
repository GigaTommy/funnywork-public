package com.funnyworker.android

import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit

/** Uses the emulator/device network stack, never account credentials or model inference. */
class PublicEntriesTest {
    @Test fun protectedRoutesReturnAccount401WithoutRedirects() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("publicEntries") == "true")
        val engine = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
        val entries = JSONArray()
        var passed = true
        for (origin in ServiceIdentity.official) {
            val checks = JSONArray()
            for (path in listOf("session", "sync/sessions?limit=1", "chats/models", "chats?history=true", "inference/budget")) {
                val row = JSONObject().put("path", path)
                try {
                    engine.newCall(Request.Builder().url("$origin/api/account/$path")
                        .header("User-Agent", "FunnyWork-Android-service-check/1.0").build()).execute().use { response ->
                        val payload = runCatching { JSONObject(response.body!!.string()) }.getOrDefault(JSONObject())
                        val good = response.code == 401 && payload.optString("code") == "UNAUTHENTICATED" && response.header("Cache-Control").orEmpty().contains("no-store", true)
                        row.put("status", response.code).put("passed", good)
                        passed = passed && good
                    }
                } catch (error: Exception) { row.put("passed", false).put("error", error.javaClass.simpleName); passed = false }
                checks.put(row)
            }
            entries.put(JSONObject().put("origin", origin).put("checks", checks))
        }
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "entry-check.json")
            .writeText(JSONObject().put("device", true).put("authenticated", false).put("entries", entries).toString(2))
        assertTrue("Inspect private entry-check.json: a route failed TLS/account-boundary checks", passed)
    }
}
