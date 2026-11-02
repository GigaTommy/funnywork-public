package com.funnyworker.android

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun officialEntriesShareIdentityButCustomServersRemainIsolated() {
        assertEquals(ServiceIdentity.account(ServiceIdentity.MAINLAND, "tenant", "user"), ServiceIdentity.account(ServiceIdentity.INTERNATIONAL, "tenant", "user"))
        assertNotEquals(ServiceIdentity.account(ServiceIdentity.MAINLAND, "tenant", "user"), ServiceIdentity.account("https://custom.example", "tenant", "user"))
        assertNotEquals(ServiceIdentity.account(ServiceIdentity.MAINLAND, "tenant", "user"), ServiceIdentity.account(ServiceIdentity.MAINLAND, "other", "user"))
    }
    @Test fun originsRejectCredentialsHttpAndNestedPaths() {
        for (value in listOf("http://example.com", "https://u:p@example.com", "https://example.com/api", "https://example.com?token=a", "https://example.com#secret", "https://example.com//other")) {
            assertThrows(Exception::class.java) { ServiceIdentity.origin(value) }
        }
        assertEquals(ServiceIdentity.INTERNATIONAL, ServiceIdentity.canonical(ServiceIdentity.INTERNATIONAL.uppercase().replace("HTTPS:", "https:") + ":443/"))
    }
    @Test fun recoveryCannotReplayAttemptedExecution() {
        val initial = PendingTurn("chat", "turn", "model", "message", 2)
        val attempted = initial.attemptingExecution()
        assertTrue(attempted.executionAttempted)
        assertTrue(attempted.interrupted().executionAttempted)
        assertThrows(IllegalStateException::class.java) { attempted.interrupted().attemptingExecution() }
        assertEquals(initial.id, attempted.interrupted().id)
    }
    @Test fun ssePreservesUtf8AcrossByteBoundariesAndCrLf() {
        val events = mutableListOf<String>()
        val reader = SseReader { events.add(it) }
        ":keepalive\r\ndata: {\"text\":\"你好🙂\"}\r\n\r\ndata: [DONE]\n".toByteArray().forEach { reader.byte(it.toInt() and 255) }
        assertEquals(listOf("{\"text\":\"你好🙂\"}", "[DONE]"), events)
    }
    @Test fun longSseLineIsRejectedRatherThanTruncated() {
        val reader = SseReader { fail("No completed line expected") }
        repeat(65536) { reader.byte(65) }
        assertThrows(IllegalStateException::class.java) { reader.byte(65) }
    }
    @Test fun idsDoNotPermitTraversalOrEmptyValues() {
        assertTrue(ServiceIdentity.validId("a-b_123"))
        for (id in listOf("", "../user", "a/b", "a".repeat(65))) assertFalse(ServiceIdentity.validId(id))
    }
}
