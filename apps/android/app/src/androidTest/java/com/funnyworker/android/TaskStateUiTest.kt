package com.funnyworker.android

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Component fixtures, independent from real Mac task execution. */
class TaskStateUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun device() = Row("mac", "Mac mini", JSONObject().put("online", true).put("id", "mac"))
    private fun task() = Row("task", "销售复盘", JSONObject().put("device_id", "mac").put("device_name", "Mac mini").put("online", true)
        .put("next_seq", 4).put("task_state", "waiting").put("task_state_remaining_ms", 30000))
    private fun record(status: String = "unknown", created: Long = System.currentTimeMillis()) = JSONObject().put("status", status).put("createdAt", created)
        .put("device", "mac").put("request", JSONObject().put("kind", "create").put("id", "command").put("epoch", "epoch-one").put("payload", JSONObject().put("workspaceId", "work")))
    @Test fun phaseAndUnreadFollowFreshnessAndAccountReadPosition() {
        val state = mutableStateOf(AppState(tasksFresh = true, devicesFresh = true, devices = listOf(device()), tasksReceivedAt = SystemClock.elapsedRealtime()))
        compose.setContent { FunnyWorkTheme { TaskRowSummary(task(), state.value) } }
        compose.onNodeWithText(context.getString(R.string.task_capacity)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.task_unread)).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(taskReads = mapOf("task" to 4), tasksReceivedAt = SystemClock.elapsedRealtime() - 31000) }
        compose.onNodeWithText(context.getString(R.string.task_unread)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.task_capacity)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.online)).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(devicesFresh = false) }
        compose.onNodeWithText(context.getString(R.string.device_unknown)).assertIsDisplayed()
    }
    @Test fun disconnectedComputerOverridesOldRunningOrWaitingState() {
        val row = device().also { it.json.put("online", false) }
        compose.setContent { FunnyWorkTheme { TaskRowSummary(task(), AppState(tasksFresh = true, devicesFresh = true, devices = listOf(row), tasksReceivedAt = SystemClock.elapsedRealtime())) } }
        compose.onNodeWithText(context.getString(R.string.offline)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.task_capacity)).assertDoesNotExist()
    }
    @Test fun unknownCreationCanCheckButCannotBlindlyResubmit() {
        var checks = 0; var retries = 0
        compose.setContent { FunnyWorkTheme { Column { CreationProgress(AppState(pendingCreate = record()), { checks++ }, { retries++ }, {}, {}) } } }
        compose.onNodeWithText(context.getString(R.string.creation_unknown)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.creation_retry_original)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.check)).performClick()
        assertEquals(1, checks); assertEquals(0, retries)
    }
    @Test fun endingUnknownRecoveryRequiresConfirmationAndKeepsActionsSeparate() {
        var cleared = 0
        compose.setContent { FunnyWorkTheme { Column { CreationProgress(AppState(pendingCreate = record(created = System.currentTimeMillis() - 31000)), {}, {}, { cleared++ }, {}) } } }
        compose.onNodeWithText(context.getString(R.string.creation_end_check)).performClick()
        assertEquals(0, cleared)
        compose.onNodeWithText(context.getString(R.string.creation_end_detail)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.confirm)).performClick()
        assertEquals(1, cleared)
    }
    @Test fun completedOffscreenCreationOffersOpenInsteadOfCreatingAnotherTask() {
        var opened = 0
        compose.setContent { FunnyWorkTheme { Column { CreationProgress(AppState(createdTask = "task"), {}, {}, {}, { opened++ }) } } }
        compose.onNodeWithText(context.getString(R.string.creation_ready)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.creation_open)).performClick(); assertEquals(1, opened)
    }
}
