package com.funnyworker.android

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AccountApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: AccountApi
    private lateinit var origin: String
    @Before fun start() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val host = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(host.sslSocketFactory(), false); start() }
        origin = server.url("/").toString().trimEnd('/')
        api = AccountApi(OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build())
    }
    @After fun close() { server.shutdown() }
    private fun credential() = Credential(origin, "test-only-token", "tenant", "principal", "Test")
    @Test fun admissionCodeIsPreservedWithoutReplayingTheCommand() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("{\"code\":\"SYNC_DECISION_EXPIRED\",\"message\":\"private diagnostic\"}"))
        try { api.call(credential(), "sync/sessions/test/commands", JSONObject().put("id", "test")); fail("Admission should reject") }
        catch (error: ApiFailure) {
            assertEquals("SYNC_DECISION_EXPIRED", error.code)
            assertFalse(error.message.orEmpty().contains("private diagnostic"))
        }
        assertEquals(1, server.requestCount)
    }
    @Test fun invalidErrorBodyCannotBecomeAnErrorCode() = runBlocking {
        for (body in listOf("<html>private diagnostic</html>", "{\"code\":\"invalid message with spaces\"}")) {
            server.enqueue(MockResponse().setResponseCode(409).setBody(body))
            try { api.call(credential(), "sync/sessions/test/commands", JSONObject()); fail("Admission should reject") }
            catch (error: ApiFailure) { assertEquals("", error.code) }
        }
        assertEquals(2, server.requestCount)
    }
    @Test fun loginUsesAndroidIdentityAndVerifiesSessionBeforeReturning() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"token\":\"new-token\"}"))
        server.enqueue(MockResponse().setBody("{\"tenant\":\"tenant\",\"principalId\":\"principal\",\"nickname\":\"测试\"}"))
        val result = api.login(origin, "user", "test-only-password")
        assertEquals("principal", result.principal)
        assertEquals("测试", result.name)
        val login = server.takeRequest()
        assertEquals("POST", login.method)
        assertEquals("android", JSONObject(login.body.readUtf8()).getString("client"))
        assertNull(login.getHeader("Authorization"))
        val session = server.takeRequest()
        assertEquals("/api/account/session", session.path)
        assertEquals("Bearer new-token", session.getHeader("Authorization"))
    }
    @Test fun unsupportedAndroidLoginNeverFallsBackToIos() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400))
        try { api.login(origin, "user", "test-only-password"); fail("Unsupported client accepted") }
        catch (error: ApiFailure) { assertEquals(400, error.status) }
        assertEquals(1, server.requestCount)
    }
    @Test fun unverifiedLoginIsRevokedWithoutReplacingOriginalError() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"token\":\"unverified-token\"}"))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(503))
        try { api.login(origin, "user", "test-only-password"); fail("Unverified session accepted") }
        catch (error: ApiFailure) { assertEquals(401, error.status) }
        server.takeRequest(); server.takeRequest()
        val logout = server.takeRequest()
        assertEquals("/api/account/logout", logout.path)
        assertEquals("POST", logout.method)
        assertEquals("Bearer unverified-token", logout.getHeader("Authorization"))
        assertEquals(3, server.requestCount)
    }
    @Test fun corruptSessionIdentityIsNotAccepted() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"token\":\"unverified-token\"}"))
        server.enqueue(MockResponse().setBody("{\"tenant\":\"tenant\"}"))
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        try { api.login(origin, "user", "test-only-password"); fail("Missing principal accepted") }
        catch (_: org.json.JSONException) { }
        assertEquals(3, server.requestCount)
    }
    @Test fun blankAccountIdentityIsRevoked() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"token\":\"unverified-token\"}"))
        server.enqueue(MockResponse().setBody("{\"tenant\":\"tenant\",\"principalId\":\" \"}"))
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        try { api.login(origin, "user", "test-only-password"); fail("Blank principal accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(3, server.requestCount)
    }
    @Test fun redirectsDoNotForwardAccountToken() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "$origin/other"))
        val error = try { api.call(credential(), "chats"); fail("Redirect accepted"); null } catch (error: ApiFailure) { error }
        assertEquals(302, error!!.status)
        assertEquals(1, server.requestCount)
        assertEquals("Bearer test-only-token", server.takeRequest().getHeader("Authorization"))
    }
    @Test fun feature401ChecksSessionWithoutReplayingWrite() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("{}"))
        val error = try { api.call(credential(), "chats/chat/turns", JSONObject()); fail("Expected feature failure"); null } catch (error: ApiFailure) { error }
        assertEquals("FEATURE_UNAVAILABLE", error!!.code)
        assertEquals(2, server.requestCount)
        assertEquals("POST", server.takeRequest().method)
        assertEquals("/api/account/session", server.takeRequest().path)
    }
    @Test fun onlySession401MarksTheSessionExpired() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(401)) }
        val error = try { api.call(credential(), "chats"); fail("Expected expiration"); null } catch (error: ApiFailure) { error }
        assertEquals("SESSION_EXPIRED", error!!.code)
    }
    @Test fun sessionNetworkFailureRetainsAccount() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val error = try { api.call(credential(), "chats"); fail("Expected feature failure"); null } catch (error: ApiFailure) { error }
        assertEquals("FEATURE_UNAVAILABLE", error!!.code)
        assertEquals(2, server.requestCount)
    }
    @Test fun interruptedWriteIsNeverRetried() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        try { api.call(credential(), "chats/chat/turns", JSONObject()); fail("Expected network failure") } catch (_: java.io.IOException) { }
        assertEquals(1, server.requestCount)
    }
    @Test fun postStreamPreservesTextWithoutPersistingClientUsage() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":null}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":{\"unexpected\":true}}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"你好🙂\"}}]}\r\n\r\ndata: [DONE]\n\n"))
        val text = StringBuilder()
        api.execute(credential(), PendingTurn("chat", "turn", "model", "message", 0), { text.append(it) })
        assertEquals("你好🙂", text.toString())
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/account/chats/chat/turns/turn/execute", request.path)
        assertEquals(1, server.requestCount)
    }
    @Test fun resultRangesBindHashAndExactByteWindow(): Unit = runBlocking {
        val file = SyncedFile("a".repeat(64), "sample.txt", 3, "text/plain")
        server.enqueue(MockResponse().setResponseCode(206).setHeader("ETag", "\"${file.hash}\"")
            .setHeader("Content-Range", "bytes 1-2/3").setBody("bc"))
        assertArrayEquals("bc".toByteArray(), api.fileRange(credential(), "sync/sessions/s/results/r/versions/1/files/original", file, 1))
        val request = server.takeRequest()
        assertEquals("bytes=1-2", request.getHeader("Range"))
        assertEquals("\"${file.hash}\"", request.getHeader("If-Range"))
    }
    @Test fun ignoredRangeOrChangedEtagCannotOverwritePartialFile(): Unit = runBlocking {
        val file = SyncedFile("a".repeat(64), "sample.txt", 3, "text/plain")
        for (response in listOf(MockResponse().setResponseCode(200).setHeader("ETag", "\"${file.hash}\"").setBody("abc"),
            MockResponse().setResponseCode(206).setHeader("ETag", "\"different\"").setHeader("Content-Range", "bytes 1-2/3").setBody("bc"))) {
            server.enqueue(response)
            try { api.fileRange(credential(), "sync/sessions/s/results/r/versions/1/files/original", file, 1); fail("Invalid range accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(2, server.requestCount)
    }
}
