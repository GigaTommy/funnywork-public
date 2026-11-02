package com.funnyworker.android

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/** UI-only fixtures: real backend delivery is covered separately by LiveRemoteWorkflowTest. */
class RemoteUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun approval() = RemoteInteraction("epoch/event/one", "one", "epoch", "approval", "Read test file", "Permission needed", "", emptyList())
    @Test fun offlineApprovalCannotBeSubmitted() {
        val model = AppViewModel(context.applicationContext as Application)
        compose.setContent { FunnyWorkTheme { InteractionCard(approval(), AppState(remoteReady = true, remoteState = RemoteState(false, "epoch", true, 0, emptyList(), false)), model) } }
        compose.onNodeWithText(context.getString(R.string.remote_allow_once)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.remote_deny)).assertIsNotEnabled()
    }
    @Test fun acceptedApprovalStaysDisabledUntilSourceStateChanges() {
        val item = approval(); val model = AppViewModel(context.applicationContext as Application)
        val state = AppState(remoteReady = true, remoteState = RemoteState(true, "epoch", true, 0, listOf(item), false),
            commandRecords = listOf(JSONObject().put("interaction", item.id).put("status", "accepted")))
        compose.setContent { FunnyWorkTheme { InteractionCard(item, state, model) } }
        compose.onNodeWithText(context.getString(R.string.remote_allow_once)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.command_awaiting_receipt)).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
            context.filesDir.resolve("android-approval-ui.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
    @Test fun approvalDetailsStartCollapsedWithoutHidingDecisionActions() {
        val item = approval().copy(detail = "Exact operation details")
        val model = AppViewModel(context.applicationContext as Application)
        val state = AppState(remoteReady = true, remoteState = RemoteState(true, "epoch", true, 0, listOf(item), false))
        compose.setContent { FunnyWorkTheme { InteractionCard(item, state, model) } }
        compose.onNodeWithText(item.detail).assertDoesNotExist()
        compose.onNodeWithTag("approval-allow-one").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("approval-deny-one").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("approval-details-one").performClick()
        compose.onNodeWithText(item.detail).assertIsDisplayed()
        compose.onNodeWithTag("approval-details-one").performClick()
        compose.onNodeWithText(item.detail).assertDoesNotExist()
        compose.onNodeWithTag("approval-allow-one").assertIsEnabled()
    }
    @Test fun reviewWithoutNotesCannotAppendEmptyFeedback() {
        val model = AppViewModel(context.applicationContext as Application)
        val review = JSONObject().put("end", 3).put("messages", JSONArray()).put("notes", JSONObject()).put("comment", "")
        compose.setContent { FunnyWorkTheme { ReviewPanel(AppState(review = review), model) {} } }
        compose.onNodeWithText(context.getString(R.string.review_stage)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.review_stage_hint)).assertIsDisplayed()
    }
}
