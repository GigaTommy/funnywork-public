package com.funnyworker.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReviewDraftsTest {
    private fun round(end: Long) = JSONObject().put("end", end).put("messages", JSONArray())
        .put("sequences", JSONArray().put(end - 1)).put("comment", "").put("notes", JSONObject())
    @Test fun legacyRoundAndNewerRoundKeepSeparateNotesAcrossSerializedRestart() {
        val legacy = round(9).put("comment", "original line reference").put("notes", JSONObject().put("file", "first opinion"))
        val original = JSONObject().put("reviews", JSONObject().put("task", legacy))
        val selected = ReviewDrafts.select(original, "task", round(19))
        selected.getJSONObject("reviews").getJSONObject("task").put("comment", "second opinion")
        val restored = ReviewDrafts.select(JSONObject(selected.toString()), "task", round(9))
        assertEquals("original line reference", restored.getJSONObject("reviews").getJSONObject("task").text("comment"))
        assertEquals("first opinion", restored.getJSONObject("reviews").getJSONObject("task").getJSONObject("notes").text("file"))
        assertEquals("second opinion", ReviewDrafts.select(restored, "task", round(19)).getJSONObject("reviews").getJSONObject("task").text("comment"))
        assertFalse(original.has("reviewArchive"))
    }
    @Test fun stagingConsumesOnlyChosenRoundAndCannotAppendTwiceAfterReload() {
        var account = ReviewDrafts.select(JSONObject().put("drafts", JSONObject().put("desktop:task", "existing typing")), "task", round(9))
        account.getJSONObject("reviews").getJSONObject("task").put("comment", "first opinion")
        account = ReviewDrafts.select(account, "task", round(19))
        account.getJSONObject("reviews").getJSONObject("task").put("comment", "second opinion")
        val staged = ReviewDrafts.stage(JSONObject(account.toString()), "task", "Adjust")
        assertEquals(listOf(9L), ReviewDrafts.rounds(staged, "task").map { it.getLong("end") })
        assertTrue(staged.getJSONObject("drafts").text("desktop:task").startsWith("existing typing\n\n"))
        assertFalse(staged.getJSONObject("drafts").text("desktop:task").contains("first opinion"))
        assertThrows(IllegalArgumentException::class.java) { ReviewDrafts.stage(JSONObject(staged.toString()), "task", "Adjust") }
        assertEquals("first opinion", ReviewDrafts.rounds(staged, "task").single().text("comment"))
        assertTrue(ReviewDrafts.rounds(staged, "another-task").isEmpty())
    }
    @Test fun roundCatalogNeverCarriesMessagesAcrossATurnOrHistoryGap() {
        fun event(seq: Long, type: String) = DesktopEvent(seq, type, "reply-$seq", false, JSONObject(), emptyList())
        val rounds = ReviewSnapshot.rounds(listOf(event(0, "turn/start"), event(1, "assistant/message"), event(2, "turn/end"),
            event(3, "turn/start"), event(4, "assistant/message"), event(6, "assistant/message"), event(7, "turn/end")))
        assertEquals(listOf(7L, 2L), rounds.map { it.end })
        assertEquals(listOf(6L), rounds[0].sequences)
        assertEquals(listOf(1L), rounds[1].sequences)
    }
}
