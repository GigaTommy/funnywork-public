package com.funnyworker.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QueueReviewWorkbookTest {
    private fun state(items: List<RemoteQueueItem>) = RemoteState(true, "epoch", true, 0, emptyList(), false, items)
    @Test fun staleQueueCopyAndInvalidActionsCannotMutateCurrentItem() {
        val item = RemoteQueueItem("one", "old", true)
        assertThrows(IllegalArgumentException::class.java) { RemoteQueue.payload(state(listOf(item.copy(text = "new"))), item, "remove") }
        assertThrows(IllegalArgumentException::class.java) { RemoteQueue.payload(state(listOf(item.copy(editable = false))), item, "edit", "updated") }
        assertThrows(IllegalArgumentException::class.java) { RemoteQueue.payload(state(listOf(item)), item, "edit", " ") }
        assertEquals("updated", RemoteQueue.payload(state(listOf(item)), item, "edit", "updated").text("text"))
    }
    @Test fun withdrawalWaitsForFinalAcceptanceAndMergesNewTypingOnlyOnce() {
        val record = RemoteCommands.prepare(state(emptyList()), "queueEdit", JSONObject().put("itemId", "one").put("action", "remove")).put("withdrawalText", "queued copy")
        fun receipt(status: String) = JSONObject().put("id", record.getString("id")).put("status", status)
        val waiting = RemoteQueue.reconcile(record, receipt("queued"), "new typing")
        assertEquals("new typing", waiting.second); assertTrue(RemoteQueue.withdrawing(listOf(waiting.first)))
        val accepted = RemoteQueue.reconcile(waiting.first, receipt("accepted"), "new typing")
        assertEquals("new typing\n\nqueued copy", accepted.second)
        val restored = JSONObject(accepted.first.toString())
        assertEquals(accepted.second, RemoteQueue.reconcile(restored, receipt("accepted"), accepted.second).second)
        assertFalse(RemoteQueue.withdrawing(listOf(restored)))
        assertEquals("new typing", RemoteQueue.reconcile(record, receipt("rejected"), "new typing").second)
        assertEquals("new typing", RemoteQueue.reconcile(record, receipt("uncertain"), "new typing").second)
    }
    @Test fun manualWithdrawalRecoveryNeedsAReviewWindowAndNeverDuplicatesLateAcceptance() {
        val original = RemoteCommands.prepare(state(emptyList()), "queueEdit", JSONObject().put("itemId", "one").put("action", "remove")).put("withdrawalText", "copy").put("createdAt", 100L)
        assertFalse(RemoteQueue.canRecover(listOf(original), 30099))
        val (records, draft) = RemoteQueue.recover(listOf(original), "new", 30100)
        assertEquals("new\n\ncopy", draft); assertFalse(RemoteQueue.withdrawing(records)); assertFalse(RemoteQueue.waiting(records, "one"))
        val late = JSONObject().put("id", original.getString("id")).put("status", "accepted")
        assertEquals(draft, RemoteQueue.reconcile(JSONObject(records.single().toString()), late, draft).second)
    }
    @Test fun diffBodyFileLikeLinesDoNotSplitAFileOrInventLocations() {
        val changes = ReviewChanges.parse(8, "```diff\n--- a/a\n+++ b/a\n@@ -1,2 +1,2 @@\n--- content\n+++ content\n rest\n```")
        assertEquals(1, changes.size)
        val lines = ReviewDiff.parse(changes.single().patch)
        assertEquals(1, lines[3].old); assertEquals(1, lines[4].new)
    }
    @Test fun queueInputIsBoundedAndMalformedOrDuplicateRowsNeverCreateExtraActions() {
        val a = JSONObject().put("id", "one").put("text", "hello").put("editable", true)
        assertEquals(1, RemoteQueue.parse(JSONArray().put(a).put(a).put(JSONObject().put("id", "bad"))).size)
        assertTrue(RemoteQueue.parse(JSONArray((0..32).map { a })).isEmpty())
    }
    @Test fun diffReferencesTrackOldAndNewSidesWithoutNumberingHeadersOrPastHunks() {
        val lines = ReviewDiff.parse("--- a/a\n+++ b/a\n@@ -10,2 +20,2 @@\n-old\n+new\n same\n+outside\n@@ -1000000001 +2 @@\n+invalid")
        assertNull(lines[0].old); assertNull(lines[1].new)
        assertEquals(10, lines[3].old); assertEquals(20, lines[4].new)
        assertEquals(11, lines[5].old); assertEquals(21, lines[5].new)
        assertNull(lines[6].new); assertNull(lines[8].new)
        assertEquals("#8 · a · New 20\n> +new\n", lines[4].reference(8, "a", "Old", "New"))
    }
    @Test fun feedbackPreviewUsesTheSameFrozenReferencesAndNotesAsStaging() {
        val review = JSONObject().put("end", 9).put("sequences", JSONArray().put(8)).put("comment", "#8 · a · New 20\n> +new\n").put("notes", JSONObject().put("a", "Fix"))
        val text = ReviewFeedback.text(review, "Please adjust")
        assertTrue(text.contains("Please adjust\n#8\n")); assertTrue(text.contains("a\nFix"))
        assertThrows(IllegalArgumentException::class.java) { ReviewFeedback.text(JSONObject(review.toString()).put("comment", "").put("notes", JSONObject()), "title") }
    }
    private fun sheet() = WorkbookSheet("s1", "Sheet", 200, 30, listOf("sheet-0", "sheet-1"), listOf(12.0))
    @Test fun workbookRejectsWrongSheetOutOfWindowAndUnsafeDimensions() {
        val tile = JSONObject().put("schema", 1).put("sheetId", "s1").put("firstRow", 100).put("cells", JSONArray().put(JSONObject().put("row", 100).put("column", 2).put("text", "cached").put("formula", "DANGEROUS()").put("mergeRows", 2).put("mergeColumns", 3)))
        assertEquals("cached", WorkbookData.tile(tile, sheet()).cells.single().text)
        assertThrows(IllegalArgumentException::class.java) { WorkbookData.tile(JSONObject(tile.toString()).put("sheetId", "other"), sheet()) }
        assertThrows(IllegalArgumentException::class.java) { WorkbookData.tile(JSONObject(tile.toString()).put("firstRow", 101), sheet()) }
        assertThrows(IllegalArgumentException::class.java) { WorkbookData.tile(JSONObject(tile.toString()).put("firstRow", 0.5), sheet()) }
        assertEquals("A", WorkbookData.columnName(0)); assertEquals("AA", WorkbookData.columnName(26)); assertEquals("XFD", WorkbookData.columnName(16383))
        assertEquals(96f, sheet().width(0)); assertEquals(82f, sheet().width(1))
    }
}
