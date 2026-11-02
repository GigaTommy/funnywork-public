package com.funnyworker.android

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Rule
import org.junit.Test

class MobileModelUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun fullModelNamesAndThinkingLevelsStayInsideTheDialog() {
        val longName = "DeepSeek Flash 正式环境长上下文工具调用与思考模式模型 ".repeat(3)
        val rows = listOf(Row("flash", longName, JSONObject().put("provider_name", "DeepSeek")
            .put("reasoningEfforts", JSONArray(listOf("off", "low", "high", "max")))), Row("pro", "DeepSeek Pro", JSONObject()))
        var effort by mutableStateOf("off")
        var selected by mutableStateOf("flash")
        compose.setContent { FunnyWorkTheme("dark") {
            MobileModelDialog(rows, selected, effort, false, { selected = it }, { effort = it }, {})
        } }
        compose.onNodeWithTag("phone-model-current").assertTextEquals(longName)
        compose.onNodeWithTag("phone-model-effort-max").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("phone-model-option-pro").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("phone-model-current").performScrollTo().assertTextEquals("DeepSeek Pro")
        compose.onNodeWithTag("phone-model-effort-max").assertDoesNotExist()
    }
}
