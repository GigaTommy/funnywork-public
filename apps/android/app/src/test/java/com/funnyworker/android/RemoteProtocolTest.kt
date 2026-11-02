package com.funnyworker.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class RemoteProtocolTest {
    @Test fun definitiveRejectionKeepsTheSameStaleInteractionBlockedAfterReload() {
        val old = RemoteCommands.prepare(state(), "decision", JSONObject().put("eventId", "approval"), "interaction")
        for (code in listOf("SYNC_DECISION_EXPIRED", "SYNC_DEVICE_OFFLINE")) {
            val rejected = JSONObject(RemoteCommands.admissionRejected(old, ApiFailure(409, code))!!.toString())
            assertTrue(RemoteCommands.blocksInteraction(rejected, "interaction"))
            assertFalse(RemoteCommands.blocksInteraction(rejected, "different-epoch-interaction"))
        }
        for (status in listOf("unknown", "queued", "claimed", "accepted", "uncertain"))
            assertTrue(RemoteCommands.blocksInteraction(JSONObject(old.toString()).put("status", status), "interaction"))
        for (status in listOf("rejected", "expired"))
            assertFalse(RemoteCommands.blocksInteraction(JSONObject(old.toString()).put("status", status), "interaction"))
    }
    @Test fun onlyDefinitiveAdmissionConflictsReleasePendingWithoutChangingTheCommand() {
        val old = RemoteCommands.prepare(state(), "decision", JSONObject().put("eventId", "approval"))
        for (code in listOf("SYNC_DECISION_EXPIRED", "SYNC_DEVICE_OFFLINE")) {
            val rejected = RemoteCommands.admissionRejected(old, ApiFailure(409, code))!!
            assertFalse(rejected.getBoolean("pending")); assertTrue(rejected.getBoolean("admissionRejected"))
            assertEquals("rejected", rejected.text("status"))
            assertEquals(RemoteCommands.body(old).toString(), RemoteCommands.body(rejected).toString())
        }
        for (failure in listOf(ApiFailure(404), ApiFailure(409), ApiFailure(409, "SYNC_COMMAND_CONFLICT"), ApiFailure(502)))
            assertNull(RemoteCommands.admissionRejected(old, failure))
        assertTrue(old.getBoolean("pending")); assertEquals("unknown", old.text("status"))
    }
    private fun state() = RemoteState(true, "epoch-one", true, 12, emptyList(), true)
    @Test fun stopAndSteerBindExactTurnAndQueueDoesNot() {
        val stop = RemoteCommands.cancel(state()); assertEquals(12L, stop.second.getLong("expectedTurn"))
        val steer = RemoteCommands.prompt("guide", true, state()); assertEquals("steer", steer.second.getString("mode")); assertEquals(12L, steer.second.getLong("expectedTurn"))
        assertFalse(RemoteCommands.prompt("queued", false, state()).second.has("expectedTurn"))
        assertThrows(IllegalArgumentException::class.java) { RemoteCommands.cancel(state().copy(turn = null)) }
    }
    @Test fun approvalOnlyAllowsOnceAndContinuedAnswersUseCallId() {
        val approval = RemoteInteraction("a", "event-one", "epoch-one", "approval", "", "", "", emptyList())
        assertEquals("allowed-once", RemoteCommands.decision(approval, true).second.getString("outcome"))
        assertEquals("rejected", RemoteCommands.decision(approval, false).second.getString("outcome"))
        val q = RemoteQuestion("q", "Question", "", false, listOf("A" to ""))
        val answers = JSONObject().put("q", JSONObject().put("selected", JSONArray(listOf("A"))).put("custom", ""))
        for (kind in listOf("questions", "continued")) {
            val item = approval.copy(kind = kind, questions = listOf(q))
            val (command, payload) = RemoteCommands.answer(item, answers)
            assertEquals(if (kind == "continued") "answer" else "decision", command)
            assertTrue(payload.has(if (kind == "continued") "callId" else "eventId"))
        }
    }
    @Test fun answersRejectInvalidLabelsDuplicatesAndEmptyContent() {
        val q = RemoteQuestion("q", "Question", "", false, listOf("A" to "", "B" to ""))
        for (selected in listOf(listOf("missing"), listOf("A", "B"), listOf("A", "A"), emptyList()))
            assertFalse(RemoteCommands.validAnswer(q, JSONObject().put("selected", JSONArray(selected)).put("custom", "")))
        assertTrue(RemoteCommands.validAnswer(q.copy(multiple = true), JSONObject().put("selected", JSONArray(listOf("A", "B")))))
        assertTrue(RemoteCommands.validAnswer(q, JSONObject().put("custom", "Custom reply")))
    }
    @Test fun durableBodyIsStableAndUnknownReceiptIsNeverAccepted() {
        val record = RemoteCommands.prepare(state(), "prompt", JSONObject().put("text", "hello").put("mode", "queue"))
        val serialized = JSONObject(record.toString())
        assertEquals(RemoteCommands.body(record).toString(), RemoteCommands.body(serialized).toString())
        val uncertain = RemoteCommands.reconcile(record, JSONObject().put("id", record.getString("id")).put("status", "uncertain"))
        assertTrue(uncertain.getBoolean("pending")); assertEquals("unknown", record.getString("status"))
        val queued = RemoteCommands.reconcile(record, JSONObject().put("id", record.getString("id")).put("status", "queued"))
        assertFalse(queued.getBoolean("pending")); assertEquals("queued", queued.getString("status"))
        assertThrows(IllegalArgumentException::class.java) { RemoteCommands.reconcile(record, JSONObject().put("id", "other").put("status", "accepted")) }
        assertThrows(IllegalArgumentException::class.java) { RemoteCommands.reconcile(record, JSONObject().put("id", record.getString("id")).put("status", "missing")) }
    }
    @Test fun snapshotSeparatesApprovalsPendingQuestionsAndContinuedQuestions() {
        val root = JSONObject("""{"online":true,"epoch":"epoch-one","state":{"running":true,"turnStart":12,"pending":[{"eventId":"a","event":"approval/request","request":{"toolName":"shell"}},{"eventId":"q","event":"user-questions/request","request":{"questions":[{"id":"one","question":"Choose","options":[{"label":"A"},{"label":"A"}]}]}}],"projections":{"userQuestions":{"active":[{"state":"continued","callId":"c","questions":[]}]}}}}""")
        val remote = RemoteState.parse(root)
        assertEquals(listOf("approval", "questions", "continued"), remote.interactions.map { it.kind })
        assertEquals(1, remote.interactions[1].questions[0].options.size)
        assertFalse(RemoteState.parse(JSONObject(root.toString()).put("state", JSONObject().put("controllable", false))).online)
    }
    @Test fun eventPagesRejectGapsAndSupportSequencesBeyondInt() {
        val offset = 3000000000L
        val row = JSONObject().put("seq", offset).put("type", "turn/start").put("data", JSONObject())
        val page = JSONObject().put("events", JSONArray().put(row)).put("nextSeq", offset + 1).put("durableNextSeq", offset + 1)
        assertEquals(offset, RemoteHistory.page(page, offset).events.single().seq)
        assertThrows(IllegalArgumentException::class.java) { RemoteHistory.page(page, offset - 1) }
    }
    @Test fun byteCappedPagesReachTheFinalReplyWithoutSkippingSequences() = runBlocking<Unit> {
        val requests = mutableListOf<Pair<Long, Int>>()
        val page = RemoteHistory.range(0, 4) { offset, limit ->
            requests += offset to limit
            val count = minOf(2, limit)
            val rows = JSONArray()
            repeat(count) { index ->
                val seq = offset + index
                rows.put(JSONObject().put("seq", seq).put("type", "assistant/message").put("data", JSONObject().put("content", JSONArray().put(JSONObject().put("text", if (seq == 3L) "Final report ready" else "")))))
            }
            JSONObject().put("events", rows).put("nextSeq", offset + count).put("durableNextSeq", 4)
        }
        assertEquals(listOf(0L to 4, 2L to 2), requests)
        assertEquals(listOf(0L, 1L, 2L, 3L), page.events.map { it.seq })
        assertEquals("Final report ready", RemoteHistory.messages(page.events).single().text)
        assertEquals(4L, page.next)
    }
    @Test fun historyWindowDoesNotChaseAnAdvancingHeadOrLoopOnEmptyPages() = runBlocking<Unit> {
        var calls = 0
        val page = RemoteHistory.range(0, 1) { offset, _ ->
            calls++
            JSONObject().put("events", JSONArray().put(JSONObject().put("seq", offset).put("type", "turn/end")))
                .put("nextSeq", 1).put("durableNextSeq", 1000)
        }
        assertEquals(1, calls); assertEquals(1L, page.next); assertEquals(1000L, page.durable)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { RemoteHistory.range(0, 1) { offset, _ -> JSONObject().put("events", JSONArray()).put("nextSeq", offset).put("durableNextSeq", 1) } }
        }
    }
    @Test fun runtimeContextNeverAppearsAsAChatReply() {
        val page = JSONObject("""{"events":[
            {"seq":0,"type":"user/message","data":{"content":[{"text":"Monthly sales"}]}},
            {"seq":1,"type":"user/message","data":{"source":{"kind":"runtime-context"},"content":[{"text":"Internal workspace context"}]}},
            {"seq":2,"event":{"type":"user/message","data":{"message":{"source":{"kind":"tool"},"content":[{"text":"Internal tool context"}]}}}},
            {"seq":3,"type":"user/message","data":{"source":{"kind":"user"},"content":[{"text":"Next step"}]}},
            {"seq":4,"type":"assistant/message","data":{"content":[{"text":"Sales report"}]}}
        ],"nextSeq":5,"durableNextSeq":5}""")
        val events = RemoteHistory.page(page, 0).events
        assertEquals(listOf(0L, 3L, 4L), RemoteHistory.messages(events).map { it.seq })
        assertEquals(5, events.size) // Preserve durable sequencing and activity evidence.
    }
    @Test fun reasoningAndToolArgumentsAreNotChatText() {
        val page = JSONObject("""{"events":[{"seq":0,"type":"assistant/message","data":{"message":{"content":[
            {"type":"reasoning","text":"Internal reasoning"},
            {"type":"tool-call","text":"Tool arguments"},
            {"type":"text","text":"Public result"},{"text":"Legacy text"}
        ]}}}],"nextSeq":1,"durableNextSeq":1}""")
        val event = RemoteHistory.page(page, 0).events.single()
        assertEquals("Public result\nLegacy text", event.text)
        assertEquals(4, event.content.size)
    }
    @Test fun reviewsDoNotCrossHistoryGapOrTurnBoundary() {
        fun event(seq: Long, type: String, text: String = "") = DesktopEvent(seq, type, text, false, JSONObject(), emptyList())
        val events = listOf(event(0, "turn/start"), event(1, "assistant/message", "old"), event(2, "turn/end"),
            event(3, "turn/start"), event(4, "assistant/message", "missing gap"), event(8, "assistant/message", "current"), event(9, "turn/end"))
        val review = ReviewSnapshot.latest(events)!!
        assertEquals(listOf(8L), review.sequences); assertEquals(9L, review.end)
        val frozen = review.json().toString()
        assertEquals(frozen, review.json().toString())
        assertEquals(2, ReviewChanges.parse(8, "```diff\ndiff --git a/a b/a\n--- a/a\n+++ b/a\n-old\n+new\ndiff --git a/b b/b\n--- a/b\n+++ b/b\n+second\n```").size)
    }
    @Test fun fileReferencesRejectBadHashesAndNeverTreatNamesAsPaths() {
        assertEquals("report.txt", SyncedFile.safeName("../report.txt"))
        assertThrows(IllegalArgumentException::class.java) { SyncedFile("../../escape", "x", 3, "text/plain") }
        assertThrows(IllegalArgumentException::class.java) { SyncedFile("a".repeat(64), "x", 268435457, "text/plain") }
    }
}
