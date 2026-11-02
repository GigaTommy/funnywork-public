package com.funnyworker.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class LoginSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun signInStartsWithDisabledSubmitAndHidesOfficialOrigin() {
        val activity = compose.activity
        compose.waitUntil(10_000) { compose.onAllNodesWithText(activity.getString(R.string.start)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(activity.getString(R.string.login)).assertIsNotEnabled()
        compose.onNodeWithText(ServiceIdentity.INTERNATIONAL).assertDoesNotExist()
        compose.onNodeWithText(ServiceIdentity.MAINLAND).assertDoesNotExist()
        compose.onNodeWithText(activity.getString(R.string.login_help)).performClick()
        compose.onNodeWithText(activity.getString(R.string.custom_service)).assertIsDisplayed()
        compose.onNodeWithText(activity.getString(R.string.cancel)).performClick()
        compose.onNodeWithText(activity.getString(R.string.start)).assertIsDisplayed()
    }
    @Test fun invalidCustomOriginDoesNotCloseHelpOrSubmitCredentials() {
        val activity = compose.activity
        compose.waitUntil(10_000) { compose.onAllNodesWithText(activity.getString(R.string.login_help)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(activity.getString(R.string.login_help)).performClick()
        compose.onNode(hasSetTextAction() and hasText(activity.getString(R.string.service))).performTextReplacement("http://example.com/api")
        compose.onNodeWithText(activity.getString(R.string.done)).performClick()
        compose.onNodeWithText(activity.getString(R.string.invalid_service)).assertIsDisplayed()
    }
}
