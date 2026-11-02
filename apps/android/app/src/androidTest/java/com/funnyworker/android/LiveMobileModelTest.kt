package com.funnyworker.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Only an explicit opt-in and private account file enable actual provider calls. */
class LiveMobileModelTest {
    @Test fun publishedThinkingLevelsReachBothProtocols(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveMobileModels") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.filesDir.resolve("android-model-account.json")
        val reportFile = context.filesDir.resolve("android-model-report.json")
        val records = JSONArray()
        fun save() { reportFile.writeText(JSONObject().put("platform", "android").put("records", records).toString(2)) }
        try {
            val account = JSONObject(file.readText())
            for (origin in ServiceIdentity.official) {
                val api = AccountApi(automaticRouting = false, fixedOrigin = origin)
                val credential = api.login(origin, account.getString("username"), account.getString("password"))
                try {
                    val catalog = JSONObject(api.call(credential, "chats/models")).getJSONArray("items").objects()
                        .filter { it.optBoolean("available") && it.optJSONArray("reasoningEfforts")?.length() == 4 }
                    assertTrue(catalog.size >= 4)
                    for (model in catalog) for (effort in listOf("off", "low", "high", "max")) {
                        val chat = UUID.randomUUID().toString(); val turn = UUID.randomUUID().toString()
                        val record = JSONObject().put("origin", origin).put("chat", chat).put("turn", turn).put("route", model.getString("id"))
                            .put("model", model.getString("model")).put("protocol", model.getString("protocol")).put("effort", effort).put("executeAttempted", false)
                        records.put(record); save()
                        api.call(credential, "chats/$chat/turns", JSONObject().put("id", turn).put("modelId", model.getString("id"))
                            .put("text", "Reply only FW_OK.").put("expectedRevision", 0).put("reasoningEffort", effort).put("maxOutputTokens", 128))
                        var snapshot = JSONObject(api.call(credential, "chats/$chat"))
                        var waited = 0
                        while (snapshot.getJSONObject("ticket").text("state") == "WAITING" && waited++ < 60) {
                            delay(1000); snapshot = JSONObject(api.call(credential, "chats/$chat"))
                        }
                        if (snapshot.getJSONObject("ticket").text("state") != "GRANTED") {
                            api.call(credential, "chats/$chat/turns/$turn/cancel", JSONObject()); error("Model permit not granted")
                        }
                        record.put("executeAttempted", true); save()
                        val text = StringBuilder()
                        api.execute(credential, PendingTurn(chat, turn, model.getString("id"), "", 0)) { text.append(it) }
                        var checks = 0
                        do {
                            snapshot = JSONObject(api.call(credential, "chats/$chat"))
                            if (snapshot.getJSONObject("ticket").text("state") == "SUCCEEDED") break
                            delay(200)
                        } while (++checks < 30)
                        val result = snapshot.getJSONArray("turns").getJSONObject(0)
                        assertEquals("SUCCEEDED", result.text("ticket_state")); assertEquals("SUCCEEDED", result.text("state"))
                        assertTrue(text.isNotEmpty()); assertEquals(text.toString(), result.text("reply_text"))
                        record.put("ticketState", result.text("ticket_state")).put("streamCharacters", text.length); save()
                    }
                } finally { api.call(credential, "logout", JSONObject()) }
            }
        } finally { file.delete(); save() }
    }
}
