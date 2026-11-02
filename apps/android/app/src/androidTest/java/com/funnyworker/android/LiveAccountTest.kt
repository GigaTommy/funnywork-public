package com.funnyworker.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt in on a provisioned debug device. No passwords/tokens in instrumentation arguments or reports. */
@RunWith(AndroidJUnit4::class)
class LiveAccountTest {
    @Test fun fixedOfficialEntriesUseIndependentAndroidIdentity(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveAccount") == "true")
        val file = instrumentation.targetContext.filesDir.resolve("android-uat-account.json")
        val reportFile = instrumentation.targetContext.filesDir.resolve("android-uat-report.json")
        val report = JSONObject().put("device", true).put("inferenceRequested",
            InstrumentationRegistry.getArguments().getString("liveInference") == "true")
        val checks = JSONArray()
        report.put("entries", checks)
        try {
            check(file.length() in 1..16384) { "Provision the private authorized account file" }
            val account = JSONObject(file.readText())
            val api = AccountApi(automaticRouting = false)
            var stable: String? = null
            for (origin in ServiceIdentity.official) {
                val row = JSONObject().put("origin", origin)
                checks.put(row)
                val login = api.login(origin, account.getString("username"), account.getString("password"))
                val credential = login.copy(server = origin)
                try {
                    val raw = login.token.removePrefix("ruoyi.").split('.')
                    check(raw.size == 3) { "Expected identity-service session" }
                    val payload = JSONObject(String(android.util.Base64.decode(raw[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP), Charsets.UTF_8))
                    assertEquals("b12c16d37fb30716a9315e197290c56e", payload.getString("clientid"))
                    val session = JSONObject(api.call(credential, "session"))
                    val key = ServiceIdentity.account(origin, session.getString("tenant"), session.getString("principalId"))
                    assertTrue(stable == null || stable == key)
                    stable = key
                    assertEquals(ServiceIdentity.INTERNATIONAL, login.server)
                    JSONArray(api.call(credential, "sync/sessions?limit=1"))
                    JSONObject(api.call(credential, "chats/models"))
                    JSONObject(api.call(credential, "chats?history=true"))
                    JSONObject(api.call(credential, "inference/budget"))
                    val peer = ServiceIdentity.official.first { it != origin }
                    val peerSession = JSONObject(api.call(credential.copy(server = peer), "session"))
                    assertTrue(key == ServiceIdentity.account(peer, peerSession.getString("tenant"), peerSession.getString("principalId")))
                    row.put("accountPassed", true)
                    if (report.getBoolean("inferenceRequested")) mobileTurn(api, credential, row) { reportFile.writeText(report.toString(2)) }
                } finally {
                    api.call(credential, "logout", JSONObject())
                    row.put("logoutPassed", true)
                }
                try { api.call(credential, "session"); fail("Revoked login remains active") }
                catch (error: ApiFailure) { assertEquals(401, error.status) }
                row.put("revokedSessionRejected", true)
            }
            report.put("passed", true)
        } finally {
            check(file.delete() || !file.exists()) { "Private test credential cleanup failed" }
            reportFile.writeText(report.toString(2))
        }
    }

    private suspend fun mobileTurn(api: AccountApi, credential: Credential, row: JSONObject, save: () -> Unit) {
        val budget = JSONObject(api.call(credential, "inference/budget"))
        check(budget.optLong("held", -1) == 0L) { "Unsettled account holds; no inference started" }
        val models = JSONObject(api.call(credential, "chats/models")).getJSONArray("items")
        val available = (0 until models.length()).map { models.getJSONObject(it) }.filter { it.optBoolean("available") }
        val model = available.firstOrNull { it.optString("model").startsWith("deepseek") }
            ?: available.firstOrNull() ?: error("No authorized mobile model")
        val chat = "android-uat-" + java.util.UUID.randomUUID().toString()
        val turn = java.util.UUID.randomUUID().toString()
        row.put("chatId", chat).put("turnId", turn).put("executeAttempted", false)
        save()
        var prepared = false
        var attempted = false
        try {
            api.call(credential, "chats/$chat/turns", JSONObject().put("id", turn).put("modelId", model.getString("id"))
                .put("text", "Reply with exactly FW_ANDROID_OK.").put("expectedRevision", 0))
            prepared = true
            var snapshot = JSONObject(api.call(credential, "chats/$chat"))
            var waited = 0
            while (snapshot.optJSONObject("ticket")?.optString("state") == "WAITING" && waited++ < 30) {
                delay(1000)
                snapshot = JSONObject(api.call(credential, "chats/$chat"))
            }
            check(snapshot.optJSONObject("ticket")?.optString("state") == "GRANTED") { "Mobile permit not granted" }
            row.put("ticketId", snapshot.getJSONObject("ticket").getString("id"))
            attempted = true
            row.put("executeAttempted", true)
            save() // Unknown outcomes are retained for read-only server reconciliation, never replayed.
            val text = StringBuilder()
            api.execute(credential, PendingTurn(chat, turn, model.getString("id"), "", 0)) { text.append(it) }
            var final = JSONObject(api.call(credential, "chats/$chat"))
            var retries = 0
            while (final.getJSONArray("turns").getJSONObject(0).optString("ticket_state") == "RUNNING" && retries++ < 20) {
                delay(500)
                final = JSONObject(api.call(credential, "chats/$chat"))
            }
            val result = final.getJSONArray("turns").getJSONObject(0)
            row.put("ticketState", result.optString("ticket_state")).put("turnState", result.optString("state"))
                .put("usageQuality", result.optString("usage_quality")).put("inputTokens", result.optLong("input_tokens"))
                .put("outputTokens", result.optLong("output_tokens")).put("streamMatchesSnapshot", text.toString() == result.optString("reply_text"))
                .put("streamCharacters", text.length).put("snapshotCharacters", result.optString("reply_text").length)
            assertEquals("SUCCEEDED", result.optString("ticket_state"))
            assertEquals("SUCCEEDED", result.optString("state"))
            assertTrue(text.contains("FW_ANDROID_OK"))
            assertTrue(row.getBoolean("streamMatchesSnapshot"))
            assertTrue(result.optLong("input_tokens") > 0 && result.optLong("output_tokens") > 0)
            val peer = ServiceIdentity.official.first { it != credential.server }
            val peerResult = JSONObject(api.call(credential.copy(server = peer), "chats/$chat")).getJSONArray("turns").getJSONObject(0)
            assertTrue(result.getString("reply_text") == peerResult.getString("reply_text"))
            row.put("peerSnapshotMatches", true).put("mobilePassed", true)
        } finally {
            if (prepared && !attempted) api.call(credential, "chats/$chat/turns/$turn/cancel", JSONObject())
            save()
        }
    }
}
