package com.funnyworker.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Presentation fixtures only; these do not establish that a real computer is online. */
class DeviceDirectoryUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun device(online: Boolean = true) = Row("mac-one", "Mac mini", JSONObject()
        .put("id", "mac-one").put("name", "Mac mini").put("online", online).put("epoch", if (online) "epoch-one" else "")
        .put("platform", "darwin").put("lastSeen", 1792800000000L)
        .put("workspaces", JSONArray().put(JSONObject().put("id", "work-one").put("title", "Operations"))))
    @Test fun showsDeviceIdentityAndWorkspaceAndCreatesOnlyFromFreshState() {
        var selected = ""
        compose.setContent { FunnyWorkTheme { DeviceDirectory(AppState(devices = listOf(device()), devicesFresh = true), {}, { selected = it }, {}) } }
        compose.onNodeWithText("Mac mini").assertIsDisplayed()
        compose.onNodeWithText("macOS").assertIsDisplayed()
        compose.onNodeWithText("Operations").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.new_chat)).performClick()
        assertEquals("mac-one", selected)
    }
    @Test fun failedRefreshReplacesOnlineLabelAndDisablesStaleDevice() {
        val state = mutableStateOf(AppState(devices = listOf(device()), devicesFresh = true))
        var retries = 0
        compose.setContent { FunnyWorkTheme { DeviceDirectory(state.value, { retries++ }, {}, {}) } }
        compose.onNodeWithText(context.getString(R.string.new_chat)).assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(devicesFresh = false, devicesError = true) }
        compose.onAllNodesWithText(context.getString(R.string.online)).assertCountEquals(0)
        compose.onNodeWithText(context.getString(R.string.device_connection_failed)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.new_chat)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.retry)).performClick()
        assertEquals(1, retries)
        compose.runOnIdle { state.value = state.value.copy(devicesFresh = true, devicesError = false) }
        compose.onNodeWithText(context.getString(R.string.new_chat)).assertIsEnabled()
    }
    @Test fun offlineDeviceIsVisibleButCannotCreate() {
        compose.setContent { FunnyWorkTheme { DeviceDirectory(AppState(devices = listOf(device(false)), devicesFresh = true), {}, {}, {}) } }
        compose.onNodeWithText("Mac mini").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.new_chat)).assertIsNotEnabled()
    }
    @Test fun loadingAndEmptyStatesDoNotReportOnline() {
        val state = mutableStateOf(AppState(devicesLoading = true))
        compose.setContent { FunnyWorkTheme { DeviceDirectory(state.value, {}, {}, {}) } }
        compose.onNodeWithText(context.getString(R.string.device_checking)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.refresh)).assertIsNotEnabled()
        compose.runOnIdle { state.value = AppState(devicesFresh = true) }
        compose.onNodeWithText(context.getString(R.string.device_empty)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.device_unlinked)).assertIsDisplayed()
    }
    @Test fun remoteStatusRemainsVisibleAtLargeFontInNarrowDarkLayout() {
        val state = mutableStateOf(AppState())
        compose.setContent {
            val density = LocalDensity.current
            val configuration = android.content.res.Configuration(LocalConfiguration.current).apply {
                uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f), LocalConfiguration provides configuration) {
                FunnyWorkTheme { Box(Modifier.width(288.dp)) { ConversationSources(state.value) { state.value = state.value.copy(desktop = it) } } }
            }
        }
        compose.onNodeWithText(context.getString(R.string.chat_source)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.remote)).assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.device_unknown)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(true, state.value.desktop) }
    }
}
