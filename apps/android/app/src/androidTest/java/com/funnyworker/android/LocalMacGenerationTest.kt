package com.funnyworker.android

import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** Explicitly metered, local-only real Mac run. Never fabricates events, ACKs or result files. */
class LocalMacGenerationTest {
    val compose = createAndroidComposeRule<MainActivity>()
    // Check the opt-in and stored destination before Activity startup can restore an account.
    @get:Rule val localOnly: RuleChain = RuleChain.outerRule(TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("localGeneration") == "true")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = context.filesDir.resolve("android-development-account.json")
            try {
                val input = JSONObject(file.readText())
                require(input.getString("server") == "https://localhost:8766" && input.getString("username") == "Gigi")
                SecureStore(context).read("session")?.let { saved ->
                    val credential = Credential.from(saved)
                    require(credential.server == "https://localhost:8766" && credential.name == "Gigi")
                }
                base.evaluate()
            } finally { file.delete() }
        }
    } }).around(compose)
    @Test fun localMacGeneratesAndDeliversPreviewableSalesReport() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("localGeneration") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = context.filesDir.resolve("android-development-account.json")
        val pdfPreview = args.getString("preview") == "pdf"
        val minimumVersion = args.getString("minimumResultVersion")?.toInt() ?: 1
        require(minimumVersion >= 1)
        val output = context.filesDir.resolve(if (pdfPreview) "local-generation-pdf-report.json" else "local-generation-report.json")
        val report = JSONObject().put("environment", "local development").put("account", "Gigi")
            .put("startedAt", System.currentTimeMillis()).put("phase", "starting").put("minimumResultVersion", minimumVersion)
        fun checkpoint() = output.writeText(report.toString(2))
        checkpoint()
        var observedModel: AppViewModel? = null
        try {
            val login = JSONObject(input.readText())
            require(login.getString("server") == "https://localhost:8766" && login.getString("username") == "Gigi")
            val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            observedModel = model
            compose.waitUntil(15000) { model.state.value.ready }
            model.state.value.credential?.let { require(it.server == login.getString("server") && it.name == "Gigi") }
            if (model.state.value.credential == null) compose.runOnIdle { model.login(login.getString("server"), "Gigi", login.getString("password")) }
            compose.waitUntil(20000) { model.state.value.credential != null && model.state.value.devicesFresh && !model.state.value.loading }
            val credential = requireNotNull(model.state.value.credential)
            fun delivered(state: AppState): List<Triple<JSONObject, String, SyncedFile>> = state.resultFiles.flatMap { row ->
                val manifest = row.optJSONObject("manifest") ?: return@flatMap emptyList()
                if (manifest.optInt("version") < minimumVersion) return@flatMap emptyList()
                val references = listOf("original" to manifest.optJSONObject("original")) + manifest.optJSONArray("variants")?.objects().orEmpty().map { it.text("key") to it.optJSONObject("content") }
                references.mapNotNull { (key, content) -> content?.let { runCatching { Triple(manifest, key, SyncedFile.reference(it)) }.getOrNull() } }
            }
            fun api(path: String, body: JSONObject? = null) = runBlocking { AccountApi(automaticRouting = false).call(credential, path, body) }
            val budget = JSONObject(api("inference/budget"))
            assertTrue(budget.getBoolean("enabled") && budget.getLong("remaining_micros") > 0)
            val device = model.state.value.devices.single { it.id == args.getString("realMacDevice") }
            assertEquals("darwin", device.json.text("platform")); assertNotNull(model.state.value.availableDevice(device.id))
            args.getString("clearTerminalCreationId")?.let { operation ->
                val pending = requireNotNull(model.state.value.pendingCreate)
                require(CreationRecovery.valid(pending) && CreationRecovery.id(pending) == operation)
                val receipt = JSONObject(api("sync/devices/${device.id}/commands/$operation"))
                require(receipt.text("status") in setOf("uncertain", "expired", "rejected"))
                require(CreationRecovery.status(pending) == receipt.text("status"))
                compose.runOnIdle { model.clearCreation() }
                assertNull(model.state.value.pendingCreate)
                report.put("clearedTerminalCreationId", operation).put("previousCreationStatus", receipt.text("status")); checkpoint()
            }
            val resumed = args.getString("resumeTask")
            if (resumed == null) {
                val wanted = args.getString("taskPrompt") ?: "请制作华南三城月度销售复盘。数据（万元）：广州1月120、2月150、3月180；深圳1月160、2月190、3月240；佛山1月80、2月95、3月115。计算各月合计、各城市一季度合计和3月环比，简要说明增长趋势。请实际调用工作区工具生成并交付两份文件：sales-trend.png（彩色柱状或折线图，图中英文标签便于跨端显示）与sales-report.docx（一页中文或英文经营摘要，含核对后的数字，并生成PDF预览）。文件只写入当前工作区的deliverables/south-china-sales目录，优先使用已有Python及绘图库，不安装依赖、不访问网页。不要只给代码或口头描述；使用publish_result发布可在手机预览的固定版本结果。完成后用中文简洁说明。"
                compose.runOnIdle { model.source(true); model.chooseDevice(device.id); model.creation(text = wanted); model.creationVisible(true); model.create() }
                report.put("phase", "awaiting_creation"); checkpoint()
                compose.waitUntil(40000) { model.state.value.task != null && model.state.value.pendingCreate == null && model.state.value.createdTask == null && model.state.value.remoteReady }
                assertEquals(wanted, model.state.value.draft)
                val session = requireNotNull(model.state.value.task)
                report.put("sessionId", session).put("deviceId", device.id).put("createdFromAndroid", true); checkpoint()
                api("sync/sessions/$session/title", JSONObject().put("title", args.getString("taskTitle") ?: "华南销售复盘").put("revision", 0))
                if (args.getString("creationOnly") == "true") {
                    val history = JSONObject(api("sync/sessions/$session/events?offset=0&limit=100"))
                    assertTrue(history.getJSONArray("events").objects().none { it.text("type") in setOf("user/message", "turn/start") })
                    compose.runOnIdle { model.checkCreation() }
                    assertEquals(wanted, model.state.value.draft)
                    report.put("creationAccepted", true).put("draftMovedExactlyOnce", true).put("notAutomaticallySent", true).put("phase", "creation_verified"); checkpoint()
                    return
                }
                compose.onNodeWithText(compose.activity.getString(R.string.send)).assertIsEnabled().performClick()
                report.put("sendClicked", true); checkpoint()
            } else {
                require(ServiceIdentity.validId(resumed))
                compose.runOnIdle { model.source(true) }
                compose.waitUntil(20000) { model.state.value.tasksFresh }
                val task = model.state.value.tasks.single { it.id == resumed && it.json.text("device_id") == device.id }
                compose.runOnIdle { model.select(task) }
                report.put("sessionId", resumed).put("deviceId", device.id).put("resumedWithoutSending", true); checkpoint()
                if (args.getString("sendExistingDraft") == "true") {
                    compose.waitUntil(20000) { model.state.value.remoteReady }
                    assertTrue(model.state.value.draft.startsWith("请制作华南三城月度销售复盘。"))
                    // Explicit recovery only for a proven unsent draft. Never replay a prompt.
                    assertTrue(JSONArray(api("sync/sessions/$resumed/commands")).objects().none { it.text("kind") == "prompt" })
                    assertTrue(model.state.value.desktopEvents.none { it.user })
                    api("sync/sessions/$resumed/title", JSONObject().put("title", "华南销售复盘").put("revision", task.json.optLong("title_revision", 0)))
                    compose.onNodeWithText(compose.activity.getString(R.string.send)).assertIsEnabled().performClick()
                    report.put("resumedWithoutSending", false).put("sendClicked", true).put("originalUnsentDraftRecovered", true); checkpoint()
                }
            }
            val stages = linkedSetOf<String>(); var running = false; var resultSeen = false
            report.put("phase", "awaiting_results"); checkpoint()
            val deadline = System.currentTimeMillis() + 600000
            while (System.currentTimeMillis() < deadline) {
                compose.runOnIdle { model.refresh(); model.loadResults() }
                Thread.sleep(2500)
                val state = model.state.value
                running = running || state.remoteState?.running == true
                state.tasks.firstOrNull { it.id == state.task }?.json?.text("task_state")?.takeIf { it.isNotBlank() }?.let(stages::add)
                report.put("runningObserved", running).put("taskStages", JSONArray(stages.toList()))
                    .put("eventCount", state.desktopEvents.size).put("resultCount", state.resultFiles.size)
                    .put("interactionKinds", JSONArray(state.remoteState?.interactions.orEmpty().map { it.kind })); checkpoint()
                val files = delivered(state)
                if (files.any { it.second == "original" && it.third.name.endsWith(".png", true) } && files.any { it.third.name.endsWith(".pdf", true) } && state.remoteState?.running == false && state.desktopEvents.any { it.type == "assistant/message" && it.text.isNotBlank() }) { resultSeen = true; break }
            }
            assertTrue("Real Mac did not deliver PNG and PDF before deadline; inspect local report", resultSeen)
            val state = model.state.value; val session = requireNotNull(state.task)
            val originals = delivered(state)
            val cache = ClientFiles(context); val verified = JSONArray()
            for ((manifest, key, file) in originals.filter { it.second == "original" && it.third.name.endsWith(".png", true) || it.third.name.endsWith(".pdf", true) }) {
                val path = "sync/sessions/$session/results/${manifest.getString("id")}/versions/${manifest.getInt("version")}/files/$key"
                compose.runOnIdle { model.download(file, path) }
                compose.waitUntil(30000) { cache.local(credential.key, file).exists() && file.hash !in model.state.value.transferProgress }
                val local = requireNotNull(cache.verified(credential.key, file))
                if (file.name.endsWith(".png", true)) assertNotNull(BitmapFactory.decodeFile(local.path))
                else ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { assertEquals("Expected the requested one-page report", 1, it.pageCount) } }
                verified.put(JSONObject().put("name", file.name).put("sha256", file.hash).put("bytes", file.bytes).put("resultId", manifest.getString("id")).put("version", manifest.getInt("version")).put("fileKey", key))
            }
            assertTrue(state.desktopEvents.any { it.type == "assistant/message" && it.text.isNotBlank() })
            report.put("modelGeneratedReply", true).put("verifiedFiles", verified).put("budgetAfter", JSONObject(api("inference/budget"))); checkpoint()
            compose.onNodeWithText(compose.activity.getString(R.string.result_files)).performClick()
            val image = originals.first { if (pdfPreview) it.third.name.endsWith(".pdf", true) else it.second == "original" && it.third.name.endsWith(".png", true) }.third
            compose.onNodeWithTag("result-open-${image.hash}").performScrollTo().performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription(image.name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(image.name).assertIsDisplayed()
            report.put(if (pdfPreview) "androidPdfPreviewPassed" else "androidImagePreviewPassed", true).put("phase", "preview_verified"); checkpoint()
            if (args.getString("inspectUI") == "true") Thread.sleep(30000)
        } finally {
            observedModel?.state?.value?.pendingCreate?.let { pending ->
                report.put("pendingCreationStatus", CreationRecovery.status(pending))
                if (CreationRecovery.valid(pending)) report.put("pendingCreationId", CreationRecovery.id(pending))
            }
            checkpoint()
            input.delete()
        }
    }
}
