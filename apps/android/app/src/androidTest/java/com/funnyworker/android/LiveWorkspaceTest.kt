package com.funnyworker.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Exercises the real ViewModel/session plus source navigation without exposing credentials in semantics dumps. */
class LiveWorkspaceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun realLoginSwitchesSourcesAndRetainsMobileDraft() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveAccount") == "true")
        val file = compose.activity.filesDir.resolve("android-uat-account.json")
        var model: AppViewModel? = null
        try {
            check(file.length() in 1..16384) { "Provision a private authorized test account" }
            val account = JSONObject(file.readText())
            compose.waitUntil(10_000) { compose.onAllNodesWithText(compose.activity.getString(R.string.start)).fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle {
                model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
                model!!.login(ServiceIdentity.INTERNATIONAL, account.getString("username"), account.getString("password"))
            }
            compose.waitUntil(60_000) { model!!.state.value.credential != null && model!!.state.value.models.isNotEmpty() && !model!!.state.value.loading }
            check(model!!.state.value.error == null) { "Authenticated workspace failed to load" }
            compose.onNodeWithText(compose.activity.getString(R.string.home_title)).assertIsDisplayed()
            capture("android-workspace-home.png")
            val marker = "Android source-switch draft check"
            compose.onNode(hasSetTextAction() and hasText(compose.activity.getString(R.string.placeholder))).performTextReplacement(marker)
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.menu)).performClick()
            compose.onNode(hasText(compose.activity.getString(R.string.desktop)) and hasClickAction()).performClick()
            compose.waitUntil(30_000) { model!!.state.value.desktop && !model!!.state.value.loading }
            check(model!!.state.value.desktop)
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.menu)).performClick()
            compose.onNode(hasText(compose.activity.getString(R.string.mobile)) and hasClickAction()).performClick()
            compose.waitUntil(30_000) { !model!!.state.value.desktop && model!!.state.value.models.isNotEmpty() && !model!!.state.value.loading }
            check(model!!.state.value.draft == marker) { "Mobile draft was not retained across sources" }
            capture("android-workspace-drawer.png")
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            compose.onNode(hasSetTextAction() and hasText(marker)).assertIsDisplayed()
            capture("android-workspace-draft.png")
            compose.activity.filesDir.resolve("android-workspace-report.json").writeText(JSONObject()
                .put("device", true).put("loginAndWorkspace", true).put("sourceSwitch", true).put("mobileDraftRetained", true).toString(2))
        } finally {
            check(file.delete() || !file.exists()) { "Private test credential cleanup failed" }
            model?.let { active ->
                compose.runOnIdle { active.logout() }
                compose.waitUntil(30_000) { active.state.value.credential == null }
            }
        }
    }

    private fun capture(name: String) {
        val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            compose.activity.filesDir.resolve(name).outputStream().use { output ->
                check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally { screenshot.recycle() }
    }
}
