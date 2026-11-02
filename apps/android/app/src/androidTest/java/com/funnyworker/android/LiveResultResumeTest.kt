package com.funnyworker.android

import android.app.Application
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Collections

/** Real server Range responses, actual process death, native Mac offline. */
class LiveResultResumeTest {
    @Test fun resumeFixedResultAfterRealProcessDeath(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments(); assumeTrue(args.getString("liveResultResume") == "true")
        val phase = requireNotNull(args.getString("phase"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = JSONObject(context.filesDir.resolve("android-result-resume-config.json").readText())
        val output = context.filesDir.resolve("android-result-resume-report.json")
        val file = SyncedFile.reference(config.getJSONObject("attachment")); val path = config.text("path")
        val cache = ClientFiles(context); val offsets = Collections.synchronizedList(mutableListOf<Long>())
        val engine = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.endsWith("/files/original")) {
                request.header("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull()?.let { offsets.add(it) }
            }
            val response = chain.proceed(request)
            if (phase == "prepare" && request.header("Range")?.startsWith("bytes=262144-") == true) {
                check(response.code == 206)
                context.filesDir.resolve("android-result-second-range-held").writeText("actual HTTP 206")
                val until = System.nanoTime() + 120_000_000_000L
                while (System.nanoTime() < until) Thread.sleep(50)
                error("Process-kill orchestrator deadline")
            }
            response
        }.build()
        val api = AccountApi(engine, automaticRouting = false, fixedOrigin = config.text("origin"))
        val owner = ViewModelStore()
        val model = withContext(Dispatchers.Main) { ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(context.applicationContext as Application, api) as T
        })[AppViewModel::class.java] }
        await { model.state.value.ready }
        if (phase == "prepare") {
            val account = JSONObject(context.filesDir.resolve("android-uat-account.json").readText())
            withContext(Dispatchers.Main) { model.login(config.text("origin"), account.text("username"), account.text("password")) }
            await { model.state.value.credential != null && !model.state.value.loading }
            withContext(Dispatchers.Main) { model.source(true); model.select(Row(config.text("sessionId"), "Fixed native result resume", JSONObject().put("id", config.text("sessionId")))) }
        }
        val c = requireNotNull(model.state.value.credential)
        val source = JSONObject(api.call(c, "sync/sessions/${config.text("sessionId")}/state"))
        assertFalse("Source Mac must really be offline", source.getBoolean("online"))
        if (phase == "prepare") {
            withContext(Dispatchers.Main) { model.draft("DOWNLOAD_DRAFT_${file.hash}"); model.download(file, path) }
            await { context.filesDir.resolve("android-result-second-range-held").exists() }
            assertEquals(262144L, cache.partial(c.key, file).length())
            output.writeText(JSONObject().put("origin", config.text("origin")).put("sessionId", config.text("sessionId"))
                .put("stage", "first-chunk-durable-second-real-response-held").put("partialBytes", 262144)
                .put("sourceMacOffline", true).put("sha256", file.hash).toString(2))
            awaitCancellation()
        } else {
            assertEquals(config.text("sessionId"), model.state.value.task)
            assertEquals("DOWNLOAD_DRAFT_${file.hash}", model.state.value.draft)
            assertEquals(262144L, cache.partial(c.key, file).length())
            withContext(Dispatchers.Main) { model.download(file, path) }
            await { cache.verified(c.key, file) != null }
            assertEquals(262144L, offsets.first()); assertEquals(file.bytes, cache.verified(c.key, file)!!.length())
            val report = JSONObject(output.readText()).put("stage", "passed").put("resumedOffsets", JSONArray(offsets))
                .put("expectedBytes", file.bytes).put("verifiedSha256", ClientFiles.digest(cache.local(c.key, file)))
                .put("resumedAfterRealProcessDeath", true).put("draftRestored", true)
            assertEquals(file.hash, report.text("verifiedSha256"))
            withContext(Dispatchers.Main) { model.logout() }; await { model.state.value.credential == null }
            report.put("sessionLogoutPassed", true); output.writeText(report.toString(2))
            context.filesDir.resolve("android-uat-account.json").delete()
            withContext(Dispatchers.Main) { owner.clear() }
        }
    }
    private suspend fun await(condition: () -> Boolean) { withTimeout(90000) { while (!condition()) delay(100) } }
}
