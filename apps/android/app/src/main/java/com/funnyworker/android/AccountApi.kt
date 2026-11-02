package com.funnyworker.android

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiFailure(val status: Int, val code: String = "") : IOException("HTTP $status: $code")
internal fun chatTextDelta(data: String): String {
    val value = JSONObject(data)
    if (value.text("type") == "content_block_delta" && value.optJSONObject("delta")?.text("type") == "text_delta")
        return (value.optJSONObject("delta")?.opt("text") as? String).orEmpty()
    return (value.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.opt("content") as? String).orEmpty()
}
internal fun mobileThinkingLevels(row: Row?): List<String> = row?.json?.optJSONArray("reasoningEfforts")?.let { values ->
    (0 until values.length()).mapNotNull { values.opt(it) as? String }.filter { it in setOf("off", "low", "high", "max") }.distinct()
}.orEmpty()
data class Credential(val server: String, val token: String, val tenant: String, val principal: String, val name: String) {
    val key: String get() = ServiceIdentity.account(server, tenant, principal)
    fun json() = JSONObject().put("server", server).put("token", token).put("tenant", tenant).put("principal", principal).put("name", name)
    companion object { fun from(json: JSONObject) = Credential(json.getString("server"), json.getString("token"), json.getString("tenant"), json.getString("principal"), json.getString("name")) }
}

class AccountApi internal constructor(testEngine: OkHttpClient? = null, private val automaticRouting: Boolean = true, private val fixedOrigin: String? = null) {
    init { require(fixedOrigin == null || fixedOrigin in ServiceIdentity.official) }
    private val client = (testEngine?.newBuilder() ?: OkHttpClient.Builder()).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).build()
    private var active: String? = null
    private val cooling = mutableMapOf<String, Long>()
    private fun request(origin: String, path: String, token: String, body: JSONObject?): Request {
        require(!path.startsWith('/') && path.substringBefore('?').split('/').none { it == ".." } && !path.contains('#'))
        val builder = Request.Builder().url("${ServiceIdentity.origin(origin)}/api/account/$path")
            .header("User-Agent", "FunnyWork-Android/0.1.0").header("Cache-Control", "no-store")
        if (token.isNotEmpty()) builder.header("Authorization", "Bearer $token")
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        return builder.build()
    }
    private suspend fun response(request: Request, engine: OkHttpClient = client, stream: ((Response) -> String)? = null): String = suspendCancellableCoroutine { continuation ->
        val call = engine.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) { if (continuation.isActive) continuation.resumeWithException(error) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use { result ->
                        if (!result.isSuccessful) {
                            val code = runCatching { JSONObject(result.peekBody(4096).string()).opt("code") as? String }
                                .getOrNull()?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,79}")) }.orEmpty()
                            throw ApiFailure(result.code, code)
                        }
                        if (stream != null) stream(result) else {
                            val input = result.body?.byteStream() ?: throw IOException("Missing response")
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > 4 * 1024 * 1024) throw IOException("Response exceeds limit")
                                output.write(buffer, 0, count)
                            }
                            output.toString(Charsets.UTF_8.name())
                        }
                    }
                    if (continuation.isActive) continuation.resume(value)
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
        })
    }
    suspend fun endpoint(server: String): String = coroutineScope {
        val origin = ServiceIdentity.origin(server)
        if (origin in ServiceIdentity.official && fixedOrigin != null) return@coroutineScope fixedOrigin
        if (!automaticRouting || origin !in ServiceIdentity.official) return@coroutineScope origin
        active?.takeIf { (cooling[it] ?: 0) < System.currentTimeMillis() }?.let { return@coroutineScope it }
        val candidates = ServiceIdentity.official.filter { (cooling[it] ?: 0) < System.currentTimeMillis() }.ifEmpty { ServiceIdentity.official.toList() }
        val results = Channel<String?>(candidates.size)
        val jobs = candidates.map { entry -> launch {
            val available = try {
                response(request(entry, "session", "", null), client.newBuilder().callTimeout(4, TimeUnit.SECONDS).build())
                false
            } catch (error: ApiFailure) { error.status == 401 } catch (_: IOException) { false }
            results.send(if (available) entry else null)
        } }
        repeat(candidates.size) { results.receive()?.let { found -> jobs.forEach { it.cancel() }; active = found; return@coroutineScope found } }
        throw IOException("No trusted entry available")
    }
    suspend fun json(server: String, token: String, path: String, body: JSONObject? = null): String {
        val entry = endpoint(server)
        try { return response(request(entry, path, token, body)) }
        catch (error: IOException) {
            if (error is ApiFailure && error.status !in listOf(502, 503, 504)) throw error
            if (!automaticRouting || fixedOrigin != null || server !in ServiceIdentity.official) throw error
            cooling[entry] = System.currentTimeMillis() + 30_000
            active = null
            if (body != null) throw error // Never replay writes, even across official origins.
            val other = ServiceIdentity.official.first { it != entry }
            val value = response(request(other, path, token, null))
            active = other
            return value
        }
    }
    suspend fun call(credential: Credential, path: String, body: JSONObject? = null): String {
        try { return json(credential.server, credential.token, path, body) }
        catch (error: ApiFailure) {
            if (error.status != 401 || path == "session") throw error
            // Only a second 401 from the exact session invalidates this account.
            try { json(credential.server, credential.token, "session") }
            catch (check: ApiFailure) { if (check.status == 401) throw ApiFailure(401, "SESSION_EXPIRED") }
            catch (_: IOException) { }
            throw ApiFailure(403, "FEATURE_UNAVAILABLE")
        }
    }
    suspend fun login(server: String, username: String, password: String): Credential {
        val origin = ServiceIdentity.origin(server)
        val normalized = ServiceIdentity.canonical(origin)
        val login = JSONObject(json(origin, "", "login", JSONObject().put("username", username).put("password", password)
            .put("device", "FunnyWork Android").put("client", "android")))
        val token = login.getString("token")
        require(token.isNotBlank() && token.length <= 16000)
        try {
            val session = JSONObject(json(origin, token, "session"))
            val tenant = session.getString("tenant")
            val principal = session.getString("principalId")
            require(tenant.isNotBlank() && principal.isNotBlank()) { "Missing stable account identity" }
            return Credential(normalized, token, tenant, principal,
                session.optString("nickname").ifBlank { session.optString("username", username) })
        } catch (error: Exception) {
            // Best effort revoke only the new, unverified login. Never fall back to client=ios.
            withContext(NonCancellable) { runCatching { json(origin, token, "logout", JSONObject()) } }
            throw error
        }
    }
    suspend fun execute(credential: Credential, pending: PendingTurn, onDelta: (String) -> Unit) {
        val origin = endpoint(credential.server)
        val engine = client.newBuilder().readTimeout(650, TimeUnit.SECONDS).callTimeout(650, TimeUnit.SECONDS).build()
        response(request(origin, "chats/${pending.chat}/turns/${pending.id}/execute", credential.token, JSONObject()), engine) { result ->
            if (result.header("Content-Type")?.startsWith("text/event-stream", true) != true) throw IOException("Expected SSE")
            var units = 0
            var finished = false
            val reader = SseReader { data ->
                if (data == "[DONE]" || JSONObject(data).text("type") == "message_stop") finished = true
                else {
                    // Android's platform org.json can stringify JSON null/non-text values.
                    val delta = chatTextDelta(data)
                    units += delta.length
                    check(units <= 262144)
                    if (delta.isNotEmpty()) onDelta(delta)
                }
            }
            val input = result.body!!.byteStream()
            val buffer = ByteArray(8192)
            stream@ while (!finished) {
                val count = input.read(buffer)
                if (count < 0) break
                for (index in 0 until count) {
                    reader.byte(buffer[index].toInt() and 255)
                    if (finished) break@stream
                }
            }
            ""
        }
    }
    suspend fun fileRange(credential: Credential, path: String, file: SyncedFile, offset: Long): ByteArray {
        require(offset >= 0 && (offset < file.bytes || file.bytes == 0L && offset == 0L))
        val last = minOf(file.bytes - 1, offset + 262143)
        val builder = request(endpoint(credential.server), path, credential.token, null).newBuilder()
            .header("If-Range", "\"${file.hash}\"")
        if (file.bytes > 0) builder.header("Range", "bytes=$offset-$last")
        val encoded = response(builder.build()) { response ->
            require(response.header("ETag") == "\"${file.hash}\"")
            require(if (file.bytes == 0L) response.code == 200 else response.code == 206 && response.header("Content-Range") == "bytes $offset-$last/${file.bytes}")
            val expected = if (file.bytes == 0L) 0 else (last - offset + 1).toInt()
            val output = java.io.ByteArrayOutputStream()
            val input = requireNotNull(response.body).byteStream(); val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= expected); output.write(buffer, 0, count)
            }
            require(output.size() == expected)
            java.util.Base64.getEncoder().encodeToString(output.toByteArray())
        }
        return java.util.Base64.getDecoder().decode(encoded)
    }
    suspend fun delete(credential: Credential, path: String) {
        response(request(endpoint(credential.server), path, credential.token, null).newBuilder().delete().build())
    }
}
