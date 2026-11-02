package com.funnyworker.android

import android.app.Application
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/** Controlled transport/UI checks; no production account or model calls. */
class SettingsParityUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val secure get() = SecureStore(context)
    private val models = ViewModelStore()
    private lateinit var model: AppViewModel
    private lateinit var credential: Credential
    private var previousSession: JSONObject? = null
    private var previousAppearance: String? = null
    @Before fun setup() {
        previousSession = secure.read("session")
        val preferences = context.getSharedPreferences("appearance", 0)
        previousAppearance = preferences.getString("mode", null)
        preferences.edit().putString("mode", "system").commit()
        credential = Credential(ServiceIdentity.INTERNATIONAL, "fixture-token", "fixture", UUID.randomUUID().toString(), "UX Test")
        secure.write("session", credential.json())
        secure.write(credential.key, JSONObject().put("drafts", JSONObject().put("mobile:home", "Keep this draft")))
        val engine = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val json = when (request.url.encodedPath.substringAfter("/api/account/")) {
                "chats/models" -> """{"items":[{"id":"a","name":"Alpha","provider_name":"Provider A","available":true},{"id":"b","name":"Beta","provider_name":"Provider B","available":true}]}"""
                "chats" -> """{"items":[],"next":""}"""
                else -> "{}"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        instrumentation.runOnMainSync {
            model = ViewModelProvider(models, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppViewModel(context.applicationContext as Application, AccountApi(engine, automaticRouting = false, fixedOrigin = ServiceIdentity.INTERNATIONAL)) as T
            })[AppViewModel::class.java]
        }
        compose.waitUntil(10000) { model.state.value.ready && !model.state.value.loading && model.state.value.models.size == 2 }
        compose.setContent {
            val activity = requireNotNull(LocalActivity.current)
            SideEffect {
                activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            }
            val state by model.state.collectAsState(); FunnyWorkTheme(state.appearance) { FunnyWork(state, model) }
        }
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync { models.clear() }
        if (previousSession == null) secure.remove("session") else secure.write("session", previousSession!!)
        if (::credential.isInitialized) { secure.remove(credential.key); ClientFiles(context).clear(credential.key) }
        context.getSharedPreferences("appearance", 0).edit().let {
            if (previousAppearance == null) it.remove("mode") else it.putString("mode", previousAppearance)
        }.commit()
    }
    private fun settings() {
        compose.onNodeWithTag("open-sidebar").performClick()
        compose.onNodeWithTag("account-settings").performClick()
    }
    @Test fun modelsGroupedByProviderRetainAccessibleSelection() {
        compose.onNodeWithContentDescription(context.getString(R.string.model)).performClick()
        compose.onNodeWithText("Provider A").assertIsDisplayed()
        compose.onNodeWithText("Provider B").assertIsDisplayed()
        compose.onNodeWithText("Beta").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.model)).performClick()
        compose.onNodeWithText("✓ Beta").assertIsSelected()
        compose.onNodeWithText("Alpha").assertIsNotSelected()
    }
    @Test fun confirmedCleanupRetainsDraftAndCancelRetainsDownloadedFile() {
        val files = ClientFiles(context)
        val tmp = files.directory(credential.key).resolve("fixture").apply { writeText("Downloaded fixture") }
        val file = SyncedFile(ClientFiles.digest(tmp), "test.txt", tmp.length(), "text/plain")
        check(tmp.renameTo(files.local(credential.key, file)))
        settings()
        compose.waitUntil(5000) { model.state.value.downloadUsage?.count == 1 }
        compose.onNodeWithTag("clear-downloads").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        assertNotNull(files.verified(credential.key, file))
        compose.onNodeWithTag("clear-downloads").performClick()
        compose.onNodeWithText(context.getString(R.string.clear_downloads_detail)).assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.clear_downloads)).onLast().performClick()
        compose.waitUntil(5000) { model.state.value.downloadUsage?.count == 0 && !model.state.value.cacheBusy }
        compose.onNodeWithTag("clear-downloads").assertIsNotEnabled()
        assertEquals("Keep this draft", model.state.value.draft)
        assertEquals("Keep this draft", secure.read(credential.key)!!.getJSONObject("drafts").getString("mobile:home"))
    }
    @Test fun appearanceSelectionPersistsWithoutChangingDraft() {
        settings()
        compose.onNodeWithTag("appearance-dark").performScrollTo().performClick().assertIsSelected()
        // Semantics can update before the rendered frame reaches the screenshot service.
        compose.waitUntil(5000) {
            val image = instrumentation.uiAutomation.takeScreenshot()
            if (image == null) false else {
                val darkSamples = (0..4).sumOf { y -> (0..4).count { x ->
                    android.graphics.Color.red(image.getPixel((image.width * (0.2 + x * 0.15)).toInt(), (image.height * (0.15 + y * 0.15)).toInt())) < 100
                } }
                val ready = darkSamples >= 18
                if (ready) context.filesDir.resolve("ux-settings-dark.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle(); ready
            }
        }
        assertEquals("dark", context.getSharedPreferences("appearance", 0).getString("mode", null))
        assertEquals("Keep this draft", model.state.value.draft)
        compose.onNodeWithTag("appearance-light").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("appearance-system").performScrollTo().performClick().assertIsSelected()
    }
    @Test fun keyboardHidesSuggestionsWithoutSendingDraft() {
        compose.onNodeWithText(context.getString(R.string.explain)).assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Keep this draft")).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText(context.getString(R.string.explain)).fetchSemanticsNodes().isEmpty() }
        assertEquals("Keep this draft", model.state.value.draft)
        assertNull(model.state.value.phase)
        instrumentation.uiAutomation.executeShellCommand("input keyevent 4").close()
        compose.waitUntil(5000) { compose.onAllNodesWithText(context.getString(R.string.explain)).fetchSemanticsNodes().isNotEmpty() }
    }
}
