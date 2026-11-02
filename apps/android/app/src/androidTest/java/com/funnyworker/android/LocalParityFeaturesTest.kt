package com.funnyworker.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.hamcrest.Matchers.anyOf
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/** Real Gigi account and real Mac on local development services; no production endpoints. */
class LocalParityFeaturesTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private lateinit var model: AppViewModel
    private fun label(id: Int) = compose.activity.getString(id)
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun start() {
        assumeTrue(args.getString("localDevelopment") == "true")
        val account = JSONObject(File(context.filesDir, "android-development-account.json").readText())
        require(account.getString("server") == "https://localhost:8766" && account.getString("username") == "Gigi")
        compose.waitUntil(15000) { ViewModelProvider(compose.activity)[AppViewModel::class.java].state.value.ready }
        model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        model.state.value.credential?.let { require(it.server == "https://localhost:8766") }
        if (model.state.value.credential == null) compose.runOnIdle { model.login(account.getString("server"), account.getString("username"), account.getString("password")) }
        compose.waitUntil(20000) { model.state.value.credential != null && !model.state.value.loading && model.state.value.devicesFresh }
        assertEquals("Gigi", model.state.value.credential?.name)
    }
    private fun api(path: String, body: JSONObject? = null) = runBlocking { AccountApi(automaticRouting = false).call(requireNotNull(model.state.value.credential), path, body) }
    private fun menu() { compose.onNodeWithContentDescription(label(R.string.menu)).performClick() }
    private fun report(name: String, data: JSONObject) {
        File(context.filesDir, "android-p1-$name.json").writeText(data.put("environment", "local development").put("account", "Gigi").toString(2))
    }
    private fun photoResult(code: Int, uri: android.net.Uri?, action: () -> Unit) {
        Intents.init()
        try {
            intending(anyOf(hasAction("android.provider.action.PICK_IMAGES"), hasAction("androidx.activity.result.contract.action.PICK_IMAGES"), hasAction(Intent.ACTION_OPEN_DOCUMENT)))
                .respondWith(Instrumentation.ActivityResult(code, Intent().apply { if (uri != null) data = uri }))
            action(); compose.waitForIdle()
        } finally { Intents.release() }
    }
    @Test fun avatarCancelPreviewAndSaveUseAuthenticatedDevelopmentProfile() {
        start(); val original = JSONObject(api("session")).getString("avatarUrl")
        menu(); compose.onNodeWithTag("account-avatar").performClick()
        photoResult(Activity.RESULT_CANCELED, null) { compose.onNodeWithTag("avatar-select").performClick() }
        compose.onNodeWithTag("avatar-save").assertIsNotEnabled()
        assertEquals(original, JSONObject(api("session")).getString("avatarUrl"))
        val input = File(context.filesDir, "transfers/gigi-avatar.jpg"); require(input.isFile)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", input)
        photoResult(Activity.RESULT_OK, uri) { compose.onNodeWithTag("avatar-select").performClick() }
        compose.waitUntil(10000) { model.state.value.avatarCandidate != null && !model.state.value.avatarBusy }
        val candidate = requireNotNull(model.state.value.avatarCandidate)
        compose.onNodeWithTag("avatar-save").assertIsEnabled().performClick()
        compose.waitUntil(15000) { !model.state.value.avatarBusy && !model.state.value.avatarEditor }
        assertEquals(candidate, JSONObject(api("session")).getString("avatarUrl"))
        report("avatar", JSONObject().put("cancelPreservedAvatar", true).put("saved", true).put("avatarDigest", digest(candidate))
            .put("photoResult", "Espresso intent result supplies the real local picture URI; profile save/read use the real backend"))
        menu(); compose.onNodeWithTag("account-avatar").assertIsDisplayed()
        if (args.getString("inspectUI") == "true") Thread.sleep(30000)
    }
    @Test fun externalAvatarChangeAppearsWithoutSigningInAgain() {
        start(); val initial = model.state.value.avatar
        menu(); report("sync-ready", JSONObject().put("initialDigest", digest(initial)))
        compose.waitUntil(60000) { model.state.value.avatar.isNotBlank() && model.state.value.avatar != initial }
        assertEquals(model.state.value.avatar, JSONObject(api("session")).getString("avatarUrl"))
        report("sync", JSONObject().put("automaticProfileSync", true).put("avatarDigest", digest(model.state.value.avatar)))
        if (args.getString("inspectUI") == "true") Thread.sleep(15000)
    }
    @Test fun realCreationReceiptRecoversAndSearchOpensTheMatchingMessage() {
        start(); menu(); compose.onNodeWithTag("source-remote").performClick()
        compose.onNodeWithTag("sidebar-devices").performClick()
        compose.waitUntil(15000) { model.state.value.devicesFresh && !model.state.value.devicesLoading }
        val device = model.state.value.devices.first { model.state.value.availableDevice(it.id) != null }
        compose.onNodeWithTag("device-create-${device.id}").performClick()
        val wanted = "请整理月度销售数据，先列出需要补充的信息。"
        compose.onNode(hasSetTextAction() and hasText(label(R.string.task_text)) and hasAnyAncestor(isDialog())).performTextReplacement(wanted)
        val observed = CopyOnWriteArrayList<String>(); val observer = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val job = observer.launch { model.state.collect { state -> state.pendingCreate?.let { observed.add(CreationRecovery.status(it)) } } }
        val previous = model.state.value.task
        try {
            compose.onNodeWithTag("creation-submit").performClick()
            compose.waitUntil(40000) { model.state.value.pendingCreate == null && model.state.value.task != previous && model.state.value.task != null }
            val session = requireNotNull(model.state.value.task)
            assertEquals(wanted, model.state.value.draft)
            if (args.getString("expectLostResponse") == "true") assertTrue("The local proxy must drop the one creation response", "unknown" in observed)
            val row = JSONObject(api("sync/sessions/$session")); assertEquals(device.id, row.getString("device_id"))
            api("sync/sessions/$session/title", JSONObject().put("title", "月度销售复盘").put("revision", row.getLong("title_revision")))
            compose.runOnIdle { model.refresh() }
            compose.waitUntil(15000) { model.state.value.remoteReady && model.state.value.tasks.any { it.id == session && it.title == "月度销售复盘" } }
            // Submit a real user instruction to the actual Mac. This does not claim a model reply.
            compose.onNodeWithText(label(R.string.send)).assertIsEnabled().performClick()
            compose.waitUntil(40000) { model.state.value.desktopMessages.any { it.user && it.text.contains("月度销售") } }
            menu(); compose.onNodeWithTag("conversation-search").performTextReplacement("月度销售复盘")
            compose.onNodeWithTag("sidebar-task-$session").assertIsDisplayed()
            compose.onNodeWithText(label(R.string.search_messages)).performClick()
            compose.onNodeWithTag("conversation-search").performTextReplacement("月度销售")
            compose.waitUntil(15000) { model.state.value.searchHits.any { it.conversation.id == session && it.excerpt.contains("月度销售") } && !model.state.value.searchBusy }
            val hit = model.state.value.searchHits.first { it.conversation.id == session }
            compose.onNodeWithTag("search-hit-${hit.id}").performClick()
            compose.waitUntil(15000) { model.state.value.historyTarget == hit.sequence && model.state.value.desktopMessages.any { it.id == hit.sequence.toString() } }
            compose.onNodeWithTag("message-${hit.sequence}").assertIsDisplayed()
            assertEquals(wanted, model.state.value.desktopMessages.first { it.id == hit.sequence.toString() }.text.trim())
            assertTrue(model.state.value.draft.isBlank())
            report("creation-search", JSONObject().put("deviceId", device.id).put("sessionId", session).put("observedCreationStates", org.json.JSONArray(observed.toList()))
                .put("draftSeededOnce", true).put("realUserMessageSynced", true).put("titleSearchPassed", true).put("messageSearchPassed", true)
                .put("modelGeneratedReplyVerified", false))
            if (args.getString("inspectUI") == "true") Thread.sleep(30000)
        } finally { job.cancel(); observer.cancel() }
    }
    companion object {
        @JvmStatic @AfterClass fun clearPrivateInput() {
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "android-development-account.json").delete()
        }
    }
}
