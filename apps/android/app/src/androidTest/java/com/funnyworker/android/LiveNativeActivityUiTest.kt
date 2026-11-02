package com.funnyworker.android

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Uses MainActivity, actual login and navigation, actual services and the existing UAT task. */
class LiveNativeActivityUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun existingTaskRestoresDraftAndOpensActualResultsOrDecidesExactNewApproval() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveNativeActivityUI") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cfg = JSONObject(context.filesDir.resolve("android-live-ui-config.json").readText())
        val account = JSONObject(context.filesDir.resolve("android-uat-account.json").readText())
        val origin = cfg.getString("origin")
        require(origin in setOf(ServiceIdentity.INTERNATIONAL, "https://funnywork.funnypixelsapp.com"))
        val task = cfg.getString("sessionId")
        require(task in setOf("3c938b76-8e69-46ff-ab05-b86c67200df8", "cc7e42b0-33a9-4892-b169-68af8530d957"))
        val intent = Intent(context, MainActivity::class.java).putExtra("com.funnyworker.android.test.OFFICIAL_ENTRY", origin)
        ActivityScenario.launch<MainActivity>(intent).use { activity ->
            fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            fun await(tag: String) { compose.waitUntil(90000) { exists(tag) } }
            compose.waitUntil(90000) { exists("login-submit") || exists("open-sidebar") }
            if (cfg.optBoolean("requireRestoredSession")) check(!exists("login-submit")) { "Signed upgrade lost the authenticated session" }
            if (exists("login-submit")) {
                compose.onNodeWithTag("login-username").performTextInput(account.getString("username"))
                compose.onNodeWithTag("login-password").performTextInput(account.getString("password"))
                compose.onNodeWithTag("login-submit").performClick()
            }
            await("open-sidebar"); compose.onNodeWithTag("open-sidebar").performClick()
            compose.onNodeWithTag("account-settings").assertTextContains(cfg.getString("displayName"), substring = true)
            compose.onNodeWithTag("source-remote").performClick()
            compose.waitUntil(90000) {
                try { compose.onNodeWithTag("conversation-list").performScrollToNode(hasTestTag("sidebar-task-$task")); true }
                catch (_: AssertionError) { false }
            }
            compose.onNodeWithTag("sidebar-task-$task").performScrollTo().performClick()
            await("conversation-composer")
            val composer = compose.onNodeWithTag("conversation-composer")
            cfg.optString("expectedUpgradeDraft").takeIf { it.isNotEmpty() }?.let { composer.assertTextEquals(it) }
            // Recover only a host-identified suffix left by this test's interrupted run.
            // Preserve any text preceding it; never infer ownership from a generic prefix.
            val interruptedSuffix = cfg.optString("interruptedOwnSuffix")
            if (interruptedSuffix.isNotEmpty()) {
                require(Regex(" LIVE-UI-[0-9a-fA-F-]{36}").matches(interruptedSuffix))
                val current = composer.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
                if (current == cfg.getString("interruptedOwnDraft") && current.endsWith(interruptedSuffix))
                    composer.performTextReplacement(current.removeSuffix(interruptedSuffix))
            }
            val before = composer.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
            val marker = " LIVE-UI-" + java.util.UUID.randomUUID()
            context.filesDir.resolve("android-live-own-draft.json").writeText(JSONObject()
                .put("sessionId", task).put("before", before).put("ownSuffix", marker).toString())
            composer.performTextInput(marker)
            composer.assertTextEquals(before + marker)
            activity.recreate()
            await("conversation-composer")
            composer.assertTextEquals(before + marker)
            screenshot("live-task.png")
            try {
                if (cfg.has("eventId")) {
                    val action = "approval-${if (cfg.optBoolean("allow")) "allow" else "deny"}-${cfg.getString("eventId")}"
                    // A preserved reading anchor can keep the newly inserted approval above the viewport.
                    // Scroll the actual message list rather than requiring offscreen LazyColumn semantics.
                    compose.waitUntil(90000) {
                        try {
                            compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange) and !hasTestTag("conversation-list")).performScrollToIndex(0)
                            exists(action)
                        } catch (_: AssertionError) { false }
                    }
                    compose.onNodeWithTag(action).performScrollTo()
                    compose.waitUntil(90000) {
                        val nodes = compose.onAllNodesWithTag(action).fetchSemanticsNodes()
                        nodes.isNotEmpty() && nodes.all { androidx.compose.ui.semantics.SemanticsProperties.Disabled !in it.config }
                    }
                    fun report(stage: String) { context.filesDir.resolve("android-live-approval-report.json").writeText(JSONObject()
                        .put("stage", stage).put("eventId", cfg.getString("eventId")).toString()) }
                    if (cfg.optBoolean("approvalGate")) {
                        report("ready")
                        compose.waitUntil(360000) {
                            if (!cfg.optBoolean("observeOnly")) compose.onNodeWithTag(action).assertIsEnabled()
                            composer.assertTextEquals(before + marker)
                            context.filesDir.resolve("android-live-approval-go").exists()
                        }
                        screenshot("live-slow-control-approval.png")
                    }
                    if (!cfg.optBoolean("observeOnly")) compose.onNodeWithTag(action).performClick()
                    compose.waitUntil(90000) {
                        val nodes = compose.onAllNodesWithTag(action).fetchSemanticsNodes()
                        nodes.isEmpty() || nodes.all { androidx.compose.ui.semantics.SemanticsProperties.Disabled in it.config }
                    }
                    if (cfg.optBoolean("approvalGate")) {
                        val key = ServiceIdentity.account(origin, cfg.getString("tenant"), cfg.getString("principal"))
                        if (cfg.optBoolean("observeOnly")) {
                            activity.recreate(); await("conversation-composer"); composer.assertTextEquals(before + marker)
                            compose.waitUntil(90000) {
                                val nodes = compose.onAllNodesWithTag(action).fetchSemanticsNodes()
                                nodes.isEmpty() || nodes.all { androidx.compose.ui.semantics.SemanticsProperties.Disabled in it.config }
                            }
                            val commands = SecureStore(context).read(key)?.optJSONObject("remoteCommands")?.optJSONArray(task)?.objects().orEmpty()
                            check(commands.none { it.optJSONObject("payload")?.text("eventId") == cfg.getString("eventId") })
                            report("observed")
                        } else {
                        var recorded: JSONObject? = null
                        compose.waitUntil(90000) {
                            recorded = SecureStore(context).read(key)?.optJSONObject("remoteCommands")?.optJSONArray(task)?.objects()
                                ?.singleOrNull { it.optJSONObject("payload")?.text("eventId") == cfg.getString("eventId") }
                            recorded?.optBoolean("pending") == false
                        }
                        val local = recorded!!
                        context.filesDir.resolve("android-live-approval-report.json").writeText(JSONObject()
                            .put("stage", "posted").put("eventId", cfg.getString("eventId"))
                            .put("request", RemoteCommands.body(local)).put("status", local.text("status"))
                            .put("admissionRejected", local.optBoolean("admissionRejected"))
                            .put("admissionCode", local.text("admissionCode")).toString())
                        }
                        screenshot("live-approval-posted.png")
                        compose.waitUntil(360000) {
                            composer.assertTextEquals(before + marker)
                            context.filesDir.resolve("android-live-approval-finish").exists()
                        }
                    }
                    composer.assertTextEquals(before + marker)
                } else if (cfg.optBoolean("previewRetry")) {
                    val key = ServiceIdentity.account(origin, cfg.getString("tenant"), cfg.getString("principal"))
                    val commandsBefore = SecureStore(context).read(key)?.optJSONObject("remoteCommands")?.optJSONArray(task)?.toString()
                    compose.onNodeWithTag("conversation-files").performScrollTo().performClick()
                    val result = cfg.getString("resultId")
                    await("result-version-$result")
                    val version = cfg.optInt("targetVersion", 1)
                    compose.onNodeWithTag("result-version-$result").performScrollTo().performClick()
                    compose.onNodeWithText(context.getString(R.string.result_version, version)).performClick()
                    compose.onAllNodesWithText(context.getString(R.string.result_preview_failed)).onFirst().assertExists()
                    compose.onNodeWithTag("result-download-$result-$version-original").assertExists()
                    compose.onNodeWithTag("result-retry-preview-$result-$version").performScrollTo().performClick()
                    await("conversation-composer")
                    val retry = context.getString(R.string.result_retry_preview_request, result, version)
                    composer.assertTextEquals(before + marker + "\n\n" + retry)
                    activity.recreate(); await("conversation-composer")
                    composer.assertTextEquals(before + marker + "\n\n" + retry)
                    check(SecureStore(context).read(key)?.optJSONObject("remoteCommands")?.optJSONArray(task)?.toString() == commandsBefore)
                    screenshot("live-preview-retry-draft.png")
                    composer.performTextReplacement(before + marker)
                    context.filesDir.resolve("android-live-preview-retry-report.json").writeText(JSONObject()
                        .put("passed", true).put("resultId", result).put("version", version)
                        .put("onlyStaged", true).put("draftRestored", true).toString())
                } else if (cfg.optBoolean("latePreview")) {
                    compose.onNodeWithTag("conversation-files").performScrollTo().performClick()
                    val result = cfg.getString("resultId")
                    val selectedVersion = cfg.optInt("lateSelectedVersion", 1)
                    val lateVersion = cfg.optInt("lateVersion", 1)
                    val key = ServiceIdentity.account(origin, cfg.getString("tenant"), cfg.getString("principal"))
                    val original = SyncedFile.reference(cfg.getJSONObject("originalReference"))
                    val thumbnail = SyncedFile.reference(cfg.getJSONObject("thumbnailReference"))
                    await("result-version-$result")
                    compose.onNodeWithTag("result-version-$result").performScrollTo().performClick()
                    compose.onNodeWithText(context.getString(R.string.result_version, selectedVersion)).performClick()
                    compose.onNodeWithTag("result-download-$result-$selectedVersion-original").performScrollTo().performClick()
                    compose.waitUntil(90000) { ClientFiles(context).verified(key, original) != null }
                    compose.onNodeWithTag("result-open-${original.hash}").performScrollTo().performClick()
                    compose.waitUntil(30000) { compose.onAllNodesWithContentDescription(original.name).fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithContentDescription(original.name).assertExists()
                    context.filesDir.resolve("android-live-late-report.json").writeText(JSONObject().put("stage", "original-open").put("originalHash", original.hash).toString())
                    compose.waitUntil(360000) {
                        compose.onNodeWithContentDescription(original.name).assertExists()
                        context.filesDir.resolve("android-live-late-released").exists()
                    }
                    compose.waitUntil(30000) {
                        compose.onNodeWithContentDescription(original.name).assertExists()
                        SecureStore(context).read(key)?.optJSONObject("results")?.optJSONArray(task)?.objects()?.any { it.optJSONObject("manifest")?.text("id") == result && it.optJSONObject("manifest")?.optInt("version") == lateVersion && it.optJSONObject("transfers")?.optJSONObject("thumbnail")?.optBoolean("complete") == true } == true
                    }
                    screenshot("live-real-late-original.png")
                    compose.onAllNodesWithText(context.getString(R.string.done)).onLast().performClick()
                    compose.onNodeWithText(original.name + " · v$selectedVersion").assertExists()
                    if (lateVersion != selectedVersion) {
                        compose.onNodeWithTag("result-version-$result").performScrollTo().performClick()
                        compose.onNodeWithText(context.getString(R.string.result_version, lateVersion)).performClick()
                    }
                    compose.onNodeWithTag("result-download-$result-$lateVersion-thumbnail").performScrollTo().performClick()
                    compose.waitUntil(90000) { ClientFiles(context).verified(key, thumbnail) != null }
                    compose.onNodeWithTag("result-open-${thumbnail.hash}").performScrollTo().performClick()
                    compose.waitUntil(30000) { compose.onAllNodesWithContentDescription(thumbnail.name).fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithContentDescription(thumbnail.name).assertExists()
                    screenshot("live-real-late-thumbnail.png")
                    compose.onAllNodesWithText(context.getString(R.string.done)).onLast().performClick()
                    compose.onAllNodesWithText(context.getString(R.string.done)).onLast().performClick()
                    context.filesDir.resolve("android-live-late-report.json").writeText(JSONObject().put("stage", "passed").put("originalHash", original.hash).put("thumbnailHash", thumbnail.hash).put("automaticDirectoryRefresh", true).toString())
                } else if (cfg.has("reviewRounds")) {
                    val rounds = cfg.getJSONArray("reviewRounds").objects()
                    require(rounds.size == 2)
                    val phase = cfg.getString("reviewPhase")
                    require(phase in setOf("prepare", "resume"))
                    compose.waitUntil(90000) { val nodes = compose.onAllNodesWithTag("review-result").fetchSemanticsNodes(); nodes.isNotEmpty() && nodes.all { androidx.compose.ui.semantics.SemanticsProperties.Disabled !in it.config } }
                    if (phase == "prepare") {
                        repeat(3) {
                            val more = context.getString(R.string.load_more)
                            compose.waitUntil(90000) {
                                try { compose.onNode(hasScrollToNodeAction() and hasAnyDescendant(hasTestTag("review-result"))).performScrollToNode(hasText(more)); true }
                                catch (_: AssertionError) { false }
                            }
                            compose.onNodeWithText(more).performClick(); Thread.sleep(2000)
                            compose.waitUntil(90000) { val nodes = compose.onAllNodesWithText(more).fetchSemanticsNodes(); nodes.isNotEmpty() && nodes.all { androidx.compose.ui.semantics.SemanticsProperties.Disabled !in it.config } }
                        }
                    }
                    if (!exists("review-round-menu")) compose.onNodeWithTag("review-result").performScrollTo().performClick()
                    await("review-round-menu")
                    fun select(round: JSONObject) {
                        compose.onNodeWithTag("review-round-menu").performScrollTo().performClick()
                        val nodes = compose.onAllNodes(SemanticsMatcher("round tag") { node -> androidx.compose.ui.semantics.SemanticsProperties.TestTag in node.config && node.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].startsWith("review-round-") }).fetchSemanticsNodes()
                        context.filesDir.resolve("android-live-round-tags.json").writeText(org.json.JSONArray(nodes.map { it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] }).toString())
                        compose.onNodeWithTag("review-round-${round.getLong("end")}").performScrollTo().performClick()
                    }
                    fun comment() = compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.review_comment)))
                    for (round in rounds) {
                        select(round)
                        if (phase == "prepare") {
                            comment().performScrollTo()
                            val prior = comment().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
                            val lineText = "+${round.optString("linePrefix", "ROUND${round.getInt("version")}_")}${round.optString("marker", cfg.getString("marker"))}"
                            val ownedReference = ReviewLine(lineText, null, 2, true).reference(round.getLong("sequence"), "review.txt", context.getString(R.string.old_line), context.getString(R.string.new_line))!!
                            check(prior.isEmpty() || prior == round.getString("opinion") || prior == round.getString("opinion") + "\n\n" + ownedReference)
                            if (prior.isEmpty()) comment().performTextInput(round.getString("opinion"))
                            val change = "review-change-${round.getLong("sequence")}-0"
                            compose.onNodeWithTag(change).performScrollTo().performClick()
                            val line = compose.onNodeWithText("+${round.optString("linePrefix", "ROUND${round.getInt("version")}_")}${round.optString("marker", cfg.getString("marker"))}")
                            line.performScrollTo().performClick(); line.performClick()
                            compose.onAllNodesWithText(context.getString(R.string.done)).onLast().performClick()
                            val referenced = comment().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
                            check(referenced.split(ownedReference).size == 2)
                        }
                        val text = comment().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
                        check(text.contains(round.getString("opinion")))
                    }
                    activity.recreate(); await("conversation-composer")
                    if (!exists("review-round-menu")) compose.onNodeWithTag("review-result").performScrollTo().performClick(); await("review-round-menu")
                    for (round in rounds) { select(round); check(comment().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text.contains(round.getString("opinion"))) }
                    if (phase == "resume") {
                        val chosen = rounds[1]; select(chosen)
                        compose.onNodeWithText(context.getString(R.string.review_stage)).performScrollTo().performClick()
                        await("conversation-composer")
                        val staged = composer.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
                        check(staged.startsWith(before + marker))
                        check(staged.split(chosen.getString("opinion")).size == 2)
                        check(!staged.contains(rounds[0].getString("opinion")))
                        composer.performTextReplacement(before + marker)
                        activity.recreate(); await("conversation-composer")
                        if (!exists("review-round-menu")) compose.onNodeWithTag("review-result").performScrollTo().performClick(); await("review-round-menu"); select(rounds[0])
                        check(comment().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text.contains(rounds[0].getString("opinion")))
                        comment().performScrollTo().performTextReplacement("")
                    }
                    screenshot("live-multi-round-$phase.png")
                    compose.onAllNodesWithText(context.getString(R.string.done)).onLast().performClick()
                    context.filesDir.resolve("android-live-review-report.json").writeText(JSONObject().put("phase", phase).put("pid", android.os.Process.myPid()).put("passed", true).toString())
                    if (phase == "prepare") compose.waitUntil(360000) { context.filesDir.resolve("android-live-review-process-release").exists() }
                } else {
                    compose.onNodeWithTag("conversation-files").performScrollTo().performClick()
                    val targetVersion = cfg.optInt("targetVersion", 2)
                    val version = "result-version-${cfg.getString("resultId")}"
                    await(version); compose.onNodeWithTag(version).performScrollTo().assertExists()
                    compose.onNodeWithTag(version).performClick()
                    compose.onNodeWithText(context.getString(R.string.result_version, 1)).performClick()
                    compose.onNodeWithText(cfg.optString("v1Name", "recovery.xlsx") + " · v1").assertExists()
                    if (cfg.optBoolean("complex") && cfg.has("v1WorkbookReference")) {
                        val result = cfg.getString("resultId")
                        compose.onNodeWithTag("result-download-$result-1-${cfg.getString("workbookKey")}").performScrollTo().performClick()
                        compose.waitUntil(60000) {
                            ClientFiles(context).verified(ServiceIdentity.account(origin, cfg.getString("tenant"), cfg.getString("principal")),
                                SyncedFile.reference(cfg.getJSONObject("v1WorkbookReference"))) != null
                        }
                        compose.onNodeWithTag("result-open-${SyncedFile.reference(cfg.getJSONObject("v1WorkbookReference")).hash}").performScrollTo().performClick()
                        compose.waitUntil(60000) { compose.onAllNodesWithText("COMPLEX_R1_${cfg.getString("marker")}").fetchSemanticsNodes().isNotEmpty() }
                        screenshot("live-real-complex-v1.png")
                        compose.onAllNodesWithText(context.getString(R.string.done)).onLast().performClick()
                    }
                    compose.onNodeWithTag("result-directory-refresh").performClick()
                    compose.onNodeWithText(cfg.optString("v1Name", "recovery.xlsx") + " · v1").assertExists()
                    compose.onNodeWithTag(version).performScrollTo().performClick()
                    compose.onNodeWithText(context.getString(R.string.result_version, targetVersion)).performClick()
                    screenshot("live-results.png")
                    if (cfg.has("workbookKey")) {
                        val open = "result-open-${SyncedFile.reference(cfg.getJSONObject("workbookReference")).hash}"
                        compose.onNodeWithTag("result-download-${cfg.getString("resultId")}-${targetVersion}-${cfg.getString("workbookKey")}").performScrollTo().performClick()
                        // Verify real downloaded bytes; transfer is triggered only through UI.
                        compose.waitUntil(60000) {
                            ClientFiles(context).verified(ServiceIdentity.account(origin, cfg.getString("tenant"), cfg.getString("principal")),
                                SyncedFile.reference(cfg.getJSONObject("workbookReference"))) != null
                        }
                        compose.onNodeWithTag(open).performScrollTo().performClick()
                        compose.waitUntil(60000) { compose.onAllNodesWithText("${cfg.optString("v2Prefix", "V2_")}${cfg.getString("marker")}").fetchSemanticsNodes().isNotEmpty() }
                        screenshot("live-workbook.png")
                        if (cfg.optBoolean("complex")) {
                            compose.onNodeWithTag("workbook-next-columns").performClick()
                            compose.onNodeWithText("COLP_${cfg.getString("marker")}").assertExists()
                            compose.onNodeWithTag("workbook-next-columns").performClick()
                            compose.onNodeWithText("COLAD_${cfg.getString("marker")}").assertExists()
                            compose.onAllNodesWithText("‹").onLast().performClick(); compose.onAllNodesWithText("‹").onLast().performClick()
                            compose.onAllNodesWithText("›").onFirst().performClick()
                            compose.waitUntil(60000) { compose.onAllNodesWithText("ROW101_${cfg.getString("marker")}").fetchSemanticsNodes().isNotEmpty() }
                            compose.onAllNodesWithText("›").onFirst().performClick()
                            compose.waitUntil(60000) { compose.onAllNodesWithText("ROW201_${cfg.getString("marker")}").fetchSemanticsNodes().isNotEmpty() }
                            compose.onNodeWithText("Summary ▾").performClick(); compose.onNodeWithText("Empty").performClick()
                            compose.onNodeWithText(context.getString(R.string.sheet_empty)).assertExists()
                            compose.onNodeWithText("Empty ▾").performClick(); compose.onNodeWithText("Summary").performClick()
                        }
                        compose.onNodeWithText("Summary ▾").performClick()
                        compose.onNodeWithText("Details").performClick()
                        compose.waitUntil(60000) { compose.onAllNodesWithText("${cfg.optString("detailsPrefix", "SECOND_")}${cfg.getString("marker")}").fetchSemanticsNodes().isNotEmpty() }
                        screenshot("live-workbook-details.png")
                        compose.onAllNodesWithText(context.getString(R.string.done)).onLast().performClick()
                    }
                    compose.onAllNodesWithText(context.getString(R.string.done)).onLast().performClick()
                }
            } finally {
                if (exists("conversation-composer")) {
                    // Never replace a concurrently changed draft.
                    val current = composer.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
                    if (current == before + marker) composer.performTextReplacement(before)
                }
            }
            assertEquals(before, composer.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
            context.filesDir.resolve("android-live-ui-report.json").writeText(JSONObject()
                .put("origin", origin).put("sessionId", task).put("mainActivity", true)
                .put("draftRestored", true).put("approval", cfg.has("eventId")).toString())
        }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve(name).outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
    }
}
