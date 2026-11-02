package com.funnyworker.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in local integration only. Never use a public or production service here. */
class LocalDevelopmentParityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun sameDevelopmentAccountShowsDeviceDirectory() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("localDevelopment") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.filesDir.resolve("android-development-account.json")
        try {
            val account = JSONObject(file.readText())
            require(account.getString("server") == "https://localhost:8766")
            args.getString("locale")?.let { locale ->
                require(locale in setOf("zh-CN", "en"))
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    compose.runOnIdle { context.getSystemService(android.app.LocaleManager::class.java).applicationLocales = android.os.LocaleList.forLanguageTags(locale) }
                } else error("Per-app locale checks require API 33 or later")
                compose.waitForIdle()
            }
            val activity = compose.activity
            fun label(id: Int) = activity.getString(id)
            compose.waitUntil(15_000) { compose.onAllNodesWithText(label(R.string.login_help)).fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithContentDescription(label(R.string.menu)).fetchSemanticsNodes().isNotEmpty() }
            if (compose.onAllNodesWithText(label(R.string.login_help)).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithText(label(R.string.login_help)).performClick()
                compose.onNode(hasSetTextAction() and hasText(label(R.string.service))).performTextReplacement(account.getString("server"))
                compose.onNodeWithText(label(R.string.done)).performClick()
                compose.onNode(hasSetTextAction() and hasText(label(R.string.username))).performTextReplacement(account.getString("username"))
                compose.onNode(hasSetTextAction() and hasText(label(R.string.password))).performTextReplacement(account.getString("password"))
                compose.onNodeWithText(label(R.string.login), useUnmergedTree = true).performClick()
            }
            compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription(label(R.string.menu)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(label(R.string.menu)).performClick()
            compose.onNodeWithTag("source-remote").assertIsDisplayed()
            compose.onNodeWithTag("sidebar-devices").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText(label(R.string.device_checking)).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText(label(R.string.device_refresh_failed)).assertDoesNotExist()
            val model = ViewModelProvider(activity)[AppViewModel::class.java]
            assertEquals(account.getString("server"), model.state.value.credential?.server)
            args.getString("expectedAvatarDigest")?.let { expected ->
                val actual = java.security.MessageDigest.getInstance("SHA-256").digest(model.state.value.avatar.toByteArray()).joinToString("") { "%02x".format(it) }
                assertEquals(expected, actual)
            }
            // Cancel an in-flight directory refresh, then ensure a late result cannot restore online state.
            compose.runOnIdle { model.loadDevices(); model.foreground(false) }
            Thread.sleep(500)
            assertFalse(model.state.value.devicesFresh)
            assertFalse(model.state.value.remoteReady)
            assertFalse(model.state.value.devicesLoading)
            compose.runOnIdle { model.foreground(true) }
            compose.waitUntil(15_000) { model.state.value.devicesFresh && !model.state.value.devicesLoading }
            val expectedDevice = args.getString("expectedDeviceName")
            if (expectedDevice != null) {
                compose.onNode(hasText(expectedDevice) and hasAnyAncestor(isDialog())).assertIsDisplayed()
                compose.onNode(hasText("macOS") and hasAnyAncestor(isDialog())).assertIsDisplayed()
                val online = args.getString("expectedOnline") != "false"
                assertEquals(if (online) DeviceConnection.ONLINE else DeviceConnection.OFFLINE, model.state.value.deviceConnection())
                if (!online) {
                    val device = model.state.value.devices.first { it.title == expectedDevice }
                    compose.onNodeWithTag("device-create-${device.id}").assertIsNotEnabled()
                }
            }
            else compose.onNodeWithText(label(R.string.device_empty)).assertIsDisplayed()
            when (args.getString("screen")) {
                "creation" -> {
                    val device = requireNotNull(model.state.value.devices.firstOrNull { it.title == expectedDevice })
                    val workspace = DeviceInfo.parse(device.json).workspaces.first()
                    compose.onNodeWithTag("device-create-${device.id}").performClick()
                    if (DeviceInfo.parse(device.json).workspaces.size == 1) compose.onNodeWithTag("creation-submit").assertIsEnabled()
                    compose.runOnIdle { model.creation(workspace = "") }
                    compose.onNodeWithTag("creation-submit").assertIsNotEnabled()
                    compose.onNode(hasText(workspace.title) and hasAnyAncestor(isDialog())).performClick()
                    compose.onNodeWithTag("creation-submit").assertIsEnabled()
                    compose.onNodeWithText(label(R.string.location) + ": " + device.title + " · " + workspace.title).assertIsDisplayed()
                }
                "sidebar" -> {
                    compose.onNodeWithTag("device-directory-close").performClick()
                    compose.onNodeWithContentDescription(label(R.string.menu)).performClick()
                    compose.onNodeWithTag("source-remote").assertTextContains(label(R.string.online))
                }
                "task" -> {
                    compose.onNodeWithTag("device-directory-close").performClick()
                    compose.runOnIdle { model.source(true) }
                    compose.waitUntil(15_000) { model.state.value.tasks.isNotEmpty() }
                    val task = model.state.value.tasks.first()
                    compose.runOnIdle { model.select(task) }
                    compose.waitUntil(15_000) { model.state.value.remoteReady }
                    assertTrue(model.state.value.remoteState?.online == true)
                    compose.onNode(hasText(label(R.string.online)) and hasAnyAncestor(hasTestTag("remote-task-identity"))).assertIsDisplayed()
                    compose.onNodeWithText(label(R.string.send)).assertIsNotEnabled()
                }
            }
            context.filesDir.resolve("android-development-parity-report.json").writeText(JSONObject()
                .put("environment", "local development").put("server", account.getString("server"))
                .put("account", account.getString("username")).put("directoryPassed", true)
                .put("backgroundCancellationPassed", true).put("expectedDevice", expectedDevice)
                .put("expectedOnline", args.getString("expectedOnline") != "false")
                .put("screen", args.getString("screen") ?: "devices").toString(2))
            // Optional bounded pause for CUA to inspect the actual rendered screen.
            if (args.getString("inspectUI") == "true") Thread.sleep(30_000)
        } finally { file.delete() }
    }
}
