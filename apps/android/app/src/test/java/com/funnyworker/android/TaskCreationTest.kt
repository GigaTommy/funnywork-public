package com.funnyworker.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskCreationTest {
    private fun row(id: String = "a") = Row(id, "季度销售复盘", JSONObject().put("id", id).put("online", true).put("task_state", "running").put("task_state_remaining_ms", 30000).put("next_seq", 4))
    private fun record() = JSONObject().put("device", "mac").put("text", "整理销售数据").put("createdAt", 100000)
        .put("status", "missing").put("request", JSONObject().put("kind", "create").put("id", "command-one").put("epoch", "epoch-one").put("payload", JSONObject().put("workspaceId", "work")))
    private fun device() = DeviceInfo("mac", "Mac mini", true, false, "macOS", null, "epoch-one", listOf(Row("work", "Work", JSONObject())))
    private fun receipt(status: String = "accepted") = JSONObject().put("id", "command-one").put("status", status).put("result", JSONObject().put("sessionId", "new-task"))
    @Test fun taskPhasesExpireAndStaleDirectoriesNeverShowRunning() {
        for ((wire, stage) in listOf("approval" to TaskStage.APPROVAL, "question" to TaskStage.QUESTION, "waiting" to TaskStage.WAITING, "running" to TaskStage.RUNNING)) {
            val row = row().also { it.json.put("task_state", wire) }
            assertEquals(stage, TaskDirectory.stage(row, true, 100, 100))
            assertEquals(TaskStage.ONLINE, TaskDirectory.stage(row, true, 100, 30100))
            assertEquals(TaskStage.UNKNOWN, TaskDirectory.stage(row, false, 100, 100))
            assertEquals(TaskStage.OFFLINE, TaskDirectory.stage(row.also { it.json.put("online", false) }, true, 100, 100))
        }
    }
    @Test fun titleSearchIsLiteralCaseInsensitiveAndDeviceAware() {
        val r = row(); r.json.put("device_name", "Mac mini").put("device_id", "mac")
        assertEquals(listOf(r), TaskDirectory.titles(listOf(r), "销售"))
        assertEquals(listOf(r), TaskDirectory.titles(listOf(r), "MAC"))
        assertTrue(TaskDirectory.titles(listOf(r), ".*").isEmpty())
        assertTrue(TaskDirectory.unread(r, 3)); assertFalse(TaskDirectory.unread(r, 4))
    }
    @Test fun directoryPaginationRejectsLoopsDuplicatesAndMalformedIds() {
        assertEquals("b", TaskDirectory.page(JSONArray().put(row("b").json), "a").single().id)
        for (array in listOf(JSONArray().put(row().json).put(row().json), JSONArray().put(JSONObject.NULL)))
            assertThrows(Exception::class.java) { TaskDirectory.page(array, "") }
    }
    @Test fun searchFollowsScanCursorEvenOnEmptyPages() {
        val page = JSONObject().put("items", JSONArray()).put("afterSession", "b").put("afterSeq", 3).put("hasMore", true)
        assertEquals(SearchCursor("b", 3), TaskSearchPage.parse(page, SearchCursor()).cursor)
        assertThrows(Exception::class.java) { TaskSearchPage.parse(page, SearchCursor("b", 3)) }
        page.put("hasMore", false)
        assertFalse(TaskSearchPage.parse(page, SearchCursor("b", 3)).more)
    }
    @Test fun searchRejectsUnorderedOrOutOfCursorResults() {
        fun hit(id: String, seq: Long) = JSONObject().put("conversation", row(id).json).put("seq", seq).put("excerpt", "销售")
        val page = JSONObject().put("afterSession", "b").put("afterSeq", 5).put("hasMore", false)
        assertEquals(1, TaskSearchPage.parse(page.put("items", JSONArray().put(hit("b", 4))), SearchCursor()).items.size)
        for (items in listOf(JSONArray().put(hit("b", 6)), JSONArray().put(hit("b", 4)).put(hit("a", 5)), JSONArray().put(hit("b", 4)).put(hit("b", 4))))
            assertThrows(Exception::class.java) { TaskSearchPage.parse(page.put("items", items), SearchCursor()) }
    }
    @Test fun manualRetryOnlyReusesMissingRecentRequestOnSameEpochAndWorkspace() {
        val r = record()
        assertTrue(CreationRecovery.canRetry(r, device(), 124999))
        assertFalse(CreationRecovery.canRetry(r, device(), 125000))
        assertFalse(CreationRecovery.canRetry(r, device(), 99999))
        assertFalse(CreationRecovery.canRetry(r, device().copy(epoch = "epoch-two"), 110000))
        assertFalse(CreationRecovery.canRetry(r, device().copy(online = false), 110000))
        assertFalse(CreationRecovery.canRetry(r.put("status", "unknown"), device(), 110000))
        assertEquals("command-one", CreationRecovery.id(r))
    }
    @Test fun clearRequiresReviewableTerminalOrElapsedUnknownResult() {
        assertFalse(CreationRecovery.canClear(record().put("status", "unknown"), 110000))
        assertTrue(CreationRecovery.canClear(record().put("status", "unknown"), 130000))
        assertTrue(CreationRecovery.canClear(record().put("status", "rejected"), 100000))
    }
    @Test fun duplicateAcceptedReceiptSeedsDraftOnlyOnceAndDoesNotSwitchVisibleTask() {
        val r = CreationRecovery.reconcile(record(), receipt())
        val account = JSONObject().put("task", "existing").put("drafts", JSONObject().put("desktop:new-task", "原草稿"))
        val first = CreationRecovery.accepted(account, r); val second = CreationRecovery.accepted(first, r)
        assertEquals("原草稿\n\n整理销售数据", second.getJSONObject("drafts").getString("desktop:new-task"))
        assertEquals("existing", second.getString("task")); assertEquals("new-task", second.getString("createdTask"))
        assertFalse(second.has("pendingCreate")); assertFalse(account.has("createdTask"))
    }
    @Test fun rejectedOrWrongReceiptCanNeverBecomeAnAcceptedConversation() {
        assertThrows(Exception::class.java) { CreationRecovery.reconcile(record(), receipt().put("id", "other")) }
        assertThrows(Exception::class.java) { CreationRecovery.reconcile(record(), receipt("missing")) }
        assertThrows(Exception::class.java) { CreationRecovery.reconcile(record(), receipt().put("result", JSONObject().put("sessionId", "../x"))) }
        assertEquals("expired", CreationRecovery.status(CreationRecovery.reconcile(record(), receipt("expired"))))
    }
    @Test fun malformedLegacyRecordIsNeverRetriedAndRequiresExplicitReviewToClear() {
        val corrupt = JSONObject().put("createdAt", 100000).put("status", "missing")
        assertFalse(CreationRecovery.valid(corrupt))
        assertFalse(CreationRecovery.canRetry(corrupt, device(), 100001))
        assertTrue(CreationRecovery.canClear(corrupt, 100001))
    }
}
