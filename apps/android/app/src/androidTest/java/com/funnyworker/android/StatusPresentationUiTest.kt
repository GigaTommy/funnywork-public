package com.funnyworker.android

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

/** Offline presentation checks; a sample online state is not a real-device connectivity claim. */
class StatusPresentationUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun screenshot(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        context.filesDir.resolve(name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun statusColorsFollowChosenAppAppearanceRatherThanSystem() {
        val appearance = mutableStateOf("dark")
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply { uiMode = Configuration.UI_MODE_NIGHT_NO }
            CompositionLocalProvider(LocalConfiguration provides config) {
                FunnyWorkTheme(appearance.value) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SemanticStatus(stringResource(R.string.online), StatusKind.ONLINE, Modifier.testTag("online-indicator"))
                        ConnectionLabel(DeviceConnection.OFFLINE)
                        ConnectionLabel(DeviceConnection.FAILED)
                        SemanticStatus(stringResource(R.string.task_approval), StatusKind.WAITING)
                        SemanticStatus(stringResource(R.string.creation_rejected), StatusKind.FAILED)
                        SemanticStatus(stringResource(R.string.creation_ready), StatusKind.COMPLETED)
                    }
                }
            }
        }
        fun assertDot(expected: Color) {
            val pixels = compose.onNodeWithTag("online-indicator").captureToImage().toPixelMap()
            val color = pixels[(6 * context.resources.displayMetrics.density).roundToInt(), pixels.height / 2]
            assertEquals(expected.red, color.red, .025f); assertEquals(expected.green, color.green, .025f); assertEquals(expected.blue, color.blue, .025f)
        }
        assertDot(Color(0xFF70CE91)); screenshot("android-ux-status-dark.png")
        compose.runOnIdle { appearance.value = "light" }
        assertDot(Color(0xFF237A43)); screenshot("android-ux-status-light.png")
    }
    @Test fun englishHeaderAndFailureFitNarrowLargeTextLayout() {
        val config = Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.ENGLISH) }
        val localized = context.createConfigurationContext(config)
        val searching = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                LocalDensity provides Density(density.density, 2f)) {
                FunnyWorkTheme("light") {
                    Column(Modifier.width(288.dp).padding(8.dp)) {
                        SidebarHeader(searching.value, { searching.value = true }, { searching.value = false })
                        ConversationSources(AppState(devicesError = true)) {}
                    }
                }
            }
        }
        compose.onNodeWithTag("sidebar-search-toggle").performClick()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        val cancel = compose.onNodeWithTag("sidebar-search-cancel").fetchSemanticsNode().boundsInRoot
        assertTrue(cancel.height / context.resources.displayMetrics.density <= 56)
        val status = compose.onNodeWithText("Connection failed").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val chip = compose.onNodeWithTag("source-remote").fetchSemanticsNode().boundsInRoot
        assertTrue(status.right <= chip.right + 1); assertTrue(status.bottom <= chip.bottom + 1)
        screenshot("android-ux-header-en-large.png")
    }
    @Test fun headerSearchAndCancelFitNarrowAccessibilityLayout() {
        val searching = mutableStateOf(false)
        var cancelCount = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                FunnyWorkTheme("dark") {
                    Column(Modifier.width(288.dp).padding(8.dp)) {
                        SidebarHeader(searching.value, { searching.value = true }, { searching.value = false; cancelCount++ })
                        ConversationSources(AppState(devicesError = true)) {}
                        if (searching.value) OutlinedTextField("", {}, modifier = Modifier.fillMaxWidth().testTag("fixed-search"), label = { Text(stringResource(R.string.search)) })
                    }
                }
            }
        }
        compose.onNodeWithTag("sidebar-search-toggle").assertIsDisplayed().performClick()
        compose.onNodeWithTag("sidebar-search-toggle").assertDoesNotExist()
        compose.onNodeWithTag("fixed-search").assertIsDisplayed()
        val cancel = compose.onNodeWithTag("sidebar-search-cancel")
        cancel.assertIsDisplayed()
        val pixelsPerDp = context.resources.displayMetrics.density
        assertTrue(cancel.fetchSemanticsNode().boundsInRoot.height / pixelsPerDp <= 56)
        compose.onNodeWithText(context.getString(R.string.device_connection_failed)).assertIsDisplayed()
        screenshot("android-ux-header-large.png")
        cancel.performClick()
        compose.onNodeWithTag("fixed-search").assertDoesNotExist()
        assertEquals(1, cancelCount)
    }
}
