package com.funnyworker.android

import java.net.URI
import java.security.MessageDigest

object ServiceIdentity {
    val MAINLAND = BuildConfig.REGIONAL_ORIGIN.ifEmpty { INTERNATIONAL }
    const val INTERNATIONAL = "https://funnywork.funnypixelsapp.com"
    val official = setOf(MAINLAND, INTERNATIONAL)
    fun origin(input: String): String {
        val uri = URI(input.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in listOf("", "/"))
        require(uri.port == -1 || uri.port in 1..65535)
        return URI("https", null, uri.host.lowercase(), if (uri.port == 443) -1 else uri.port, null, null, null).toString()
    }
    fun canonical(input: String): String = origin(input).let { if (it in official) INTERNATIONAL else it }
    fun account(server: String, tenant: String, principal: String): String {
        require(tenant.isNotBlank() && principal.isNotBlank())
        return MessageDigest.getInstance("SHA-256").digest("${canonical(server)}\n$tenant\n$principal".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    fun validId(id: String) = id.matches(Regex("[A-Za-z0-9_-]{1,64}"))
}

enum class TurnPhase { PREPARING, WAITING, EXECUTING, NEEDS_CHECK }

/** Persist before any write. Execution attempts survive process death and are never replayed. */
data class PendingTurn(val chat: String, val id: String, val model: String, val text: String,
    val revision: Long, val phase: TurnPhase = TurnPhase.PREPARING, val executionAttempted: Boolean = false) {
    fun attemptingExecution(): PendingTurn {
        check(!executionAttempted)
        return copy(phase = TurnPhase.EXECUTING, executionAttempted = true)
    }
    fun interrupted() = copy(phase = TurnPhase.NEEDS_CHECK)
}

/** Small bounded SSE reader shared with JVM protocol tests. */
class SseReader(private val onData: (String) -> Unit) {
    private val line = java.io.ByteArrayOutputStream()
    private var bytes = 0
    fun byte(value: Int) {
        check(++bytes <= 8 * 1024 * 1024) { "Stream exceeds limit" }
        if (value == 10) {
            val text = line.toString(Charsets.UTF_8.name()).trimEnd('\r')
            line.reset()
            if (text.startsWith("data:")) onData(text.substring(5).trimStart())
        } else {
            check(line.size() < 65536) { "SSE line exceeds limit" }
            line.write(value)
        }
    }
}
