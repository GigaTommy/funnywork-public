package com.funnyworker.android

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Opt-in full release UI against production. Creates only an explicitly labelled new phone chat. */
class LiveMobileHistoryUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun newPhoneChatSearchRenameArchiveRestoreAndAllowance() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveMobileHistory") == "true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val cfg=JSONObject(context.filesDir.resolve("android-mobile-history-config.json").readText())
        val account=JSONObject(context.filesDir.resolve("android-uat-account.json").readText())
        require(cfg.text("origin") in ServiceIdentity.official)
        val marker=cfg.getString("marker");require(marker.matches(Regex("RC3-HISTORY-[a-f0-9-]{36}")))
        val ownership=context.filesDir.resolve("android-mobile-history-owned.json")
        val interrupted=ownership.takeIf { it.exists() }?.let { JSONObject(it.readText()) }
        if (interrupted != null) require(interrupted.text("marker") == marker && interrupted.text("chat").isNotEmpty()) { "Reconcile the interrupted original turn; never create a replacement chat" }
        lateinit var model: AppViewModel
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { activity ->
            fun exists(tag:String)=compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            fun await(test:()->Boolean){compose.waitUntil(120000,test)}
            await{exists("login-submit")||exists("open-sidebar")}
            if(exists("login-submit")){
                compose.onNodeWithTag("login-username").performTextInput(account.getString("username"))
                compose.onNodeWithTag("login-password").performTextInput(account.getString("password"))
                compose.onNodeWithTag("login-submit").performClick()
            }
            await{exists("open-sidebar")}
            activity.onActivity{model=ViewModelProvider(it)[AppViewModel::class.java]}
            compose.onNodeWithTag("open-sidebar").performClick()
            compose.onNodeWithTag("account-settings").assertTextContains(cfg.getString("displayName"),substring=true)
            compose.onNodeWithTag("scope-mobile").performClick()
            await{model.state.value.historyFresh && !model.state.value.loading}
            // Opening a known mobile row closes the drawer; New conversation then uses the normal toolbar action.
            val first=if (cfg.optBoolean("verifyOnly")) model.state.value.chats.firstOrNull { it.id==cfg.text("chat") } ?: error("Cross-client chat must be in the active directory") else interrupted?.let { own -> model.state.value.chats.firstOrNull { it.id==own.text("chat") } ?: error("Interrupted chat must be restored from its current directory before resuming") } ?: model.state.value.chats.firstOrNull()
            if(first!=null)compose.onNodeWithTag("conversation-list").performScrollToNode(hasTestTag("conversation-${first.id}"))
            if(first!=null)compose.onNodeWithTag("conversation-${first.id}").performClick()
            else InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4").close()
            if (cfg.optBoolean("verifyOnly")) {
                await { model.state.value.chat==cfg.text("chat") && !model.state.value.loading && model.state.value.chatMetadata?.text("title")==cfg.text("expectedTitle") }
                assertEquals("Review draft $marker",model.state.value.draft)
                assertTrue(model.state.value.phoneUsage.calls>0)
                assertFalse(model.state.value.chatMetadata!!.getBoolean("archived"))
                context.filesDir.resolve("android-mobile-history-readback.json").writeText(JSONObject().put("passed",true).put("chat",cfg.text("chat")).put("title",cfg.text("expectedTitle")).put("origin",cfg.text("origin")).put("draftPreserved",true).put("newModelCalls",0).put("confirmedCalls",model.state.value.phoneUsage.calls).put("inputTokens",model.state.value.phoneUsage.input).put("outputTokens",model.state.value.phoneUsage.output).toString())
                return@use
            }
            val before: String
            if (interrupted == null) {
                compose.onNodeWithContentDescription(context.getString(R.string.new_chat)).performClick()
                await{model.state.value.chat==null && exists("phone-composer")}
                before=model.state.value.draft
                ownership.writeText(JSONObject().put("before",before).put("marker",marker).toString())
                compose.onNodeWithTag("phone-composer").performTextReplacement("请仅回复以下标记，不调用工具：$marker")
                compose.onNodeWithText(context.getString(R.string.send)).performClick()
                await{model.state.value.chat!=null && model.state.value.phase==null && model.state.value.messages.any{!it.user && marker in it.text}}
            } else {
                before=interrupted.getString("before")
                await{model.state.value.chat==interrupted.text("chat") && model.state.value.phase==null && model.state.value.messages.any{!it.user && marker in it.text}}
            }
            val composer=compose.onNodeWithTag("phone-composer")
            val chat=requireNotNull(model.state.value.chat)
            ownership.writeText(JSONObject().put("before",before).put("marker",marker).put("chat",chat).toString())
            assertTrue(model.state.value.phoneUsage.calls>0)
            val draft="Review draft $marker";composer.performTextReplacement(draft)
            activity.recreate();await{exists("phone-composer")};compose.onNodeWithTag("phone-composer").assertTextEquals(draft)
            activity.onActivity{model=ViewModelProvider(it)[AppViewModel::class.java]}
            compose.onNodeWithTag("phone-usage").performClick();compose.onNodeWithText(context.getString(R.string.usage_calls,1)).assertExists();compose.onNodeWithText(context.getString(R.string.done)).performClick()
            fun menu(){compose.onNodeWithTag("open-sidebar").performClick()}
            fun row(){compose.onNodeWithTag("conversation-list").performScrollToNode(hasTestTag("conversation-$chat"))}
            fun archiveAction() {
                await { !model.state.value.loading && model.state.value.historyFresh && !model.state.value.historyBusy }
                compose.waitUntil(15000) { compose.onAllNodesWithTag("history-archive-menu-$chat").fetchSemanticsNodes().any { androidx.compose.ui.semantics.SemanticsProperties.Disabled !in it.config } }
                compose.onNodeWithTag("history-archive-menu-$chat").assertIsEnabled().performClick()
            }
            menu();await{!model.state.value.loading};row();compose.onNodeWithTag("conversation-$chat").performTouchInput{longClick()}
            compose.onNodeWithTag("history-rename-menu-$chat").performClick()
            val title="Android history $marker";compose.onNodeWithTag("history-rename-title").performTextReplacement(title)
            compose.onNodeWithText(context.getString(R.string.done)).performClick()
            await{!model.state.value.historyBusy && model.state.value.historyPending==null && model.state.value.chats.any{it.id==chat && it.title==title} && !model.state.value.loading}
            val search=compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.search)))
            search.performTextReplacement(marker)
            await{model.state.value.historyFresh && !model.state.value.loading && model.state.value.chats.any{it.id==chat && it.json.optJSONObject("search_match")!=null}}
            row();compose.onNodeWithTag("conversation-$chat").performClick();await{model.state.value.searchLocation?.chat==chat && exists("history-return-search")}
            compose.onNodeWithTag("history-return-search").performClick();row()
            compose.onNodeWithTag("conversation-$chat").performTouchInput{longClick()};archiveAction()
            await{!model.state.value.historyBusy && model.state.value.chatMetadata?.optBoolean("archived")==true && !model.state.value.loading}
            compose.onNodeWithText(context.getString(R.string.archived)).performClick()
            await{!model.state.value.loading && model.state.value.chats.any{it.id==chat}}
            row();compose.onNodeWithTag("conversation-$chat").performTouchInput{longClick()};archiveAction()
            await{!model.state.value.historyBusy && model.state.value.chatMetadata?.optBoolean("archived")==false && !model.state.value.loading}
            assertEquals(draft,model.state.value.draft)
            compose.onNodeWithTag("account-settings").performClick();await{!model.state.value.allowanceBusy && model.state.value.allowance!=null}
            compose.onNodeWithTag("allowance-remaining").performScrollTo().assertExists()
            compose.onNodeWithText(context.getString(R.string.done)).performClick()
            // Return active scope and this new chat, restore the previous HOME draft without sending it.
            menu();compose.onNodeWithText(context.getString(R.string.archived)).performClick()
            await{!model.state.value.loading && model.state.value.chats.any{it.id==chat}};row();compose.onNodeWithTag("conversation-$chat").performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.new_chat)).performClick();await{model.state.value.chat==null}
            compose.onNodeWithTag("phone-composer").performTextReplacement(before)
            assertEquals(before,model.state.value.draft)
            context.filesDir.resolve("android-mobile-history-report.json").writeText(JSONObject().put("passed",true).put("origin",cfg.text("origin")).put("chat",chat).put("marker",marker).put("title",title).put("draftPreserved",true).put("homeDraftRestored",true).put("confirmedUsage",true).put("allowanceRead",true).toString())
            ownership.delete()
        }
    }
}
