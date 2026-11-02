package com.funnyworker.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class SyncedFile(val hash: String, val name: String, val bytes: Long, val mediaType: String) {
    init { require(hash.matches(Regex("[a-f0-9]{64}")) && bytes in 0..268435456) }
    fun json() = JSONObject().put("hash", hash).put("name", name).put("bytes", bytes).put("mediaType", mediaType)
    companion object {
        fun safeName(value: String) = value.filter { !it.isISOControl() && it !in "/\\:" }.trim().trim('.').takeLast(160).ifBlank { "file" }
        fun from(value: JSONObject): SyncedFile = SyncedFile(value.getString("hash"), safeName(value.getString("name")), requireNotNull(value.sequence("bytes")), value.getString("mediaType"))
        fun reference(value: JSONObject): SyncedFile {
            val ref = value.optJSONObject("attachment") ?: value
            val id = ref.getString("attachmentId"); require(id.startsWith("sha256:"))
            return SyncedFile(id.substring(7), safeName(ref.text("name")), requireNotNull(ref.sequence("bytes")), ref.text("mediaType").ifBlank { "application/octet-stream" })
        }
    }
}

/** Account-scoped, backup-excluded files; filenames never become paths. */
class ClientFiles(private val context: Context) {
    private val root get() = File(context.filesDir, "transfers")
    fun directory(account: String): File {
        require(account.matches(Regex("[a-f0-9]{64}")))
        return File(root, account).apply { check(isDirectory || mkdirs()) { "Unable to open transfer directory" } }
    }
    fun local(account: String, file: SyncedFile) = File(directory(account), file.hash)
    fun partial(account: String, file: SyncedFile) = File(directory(account), file.hash + ".part")
    fun clear(account: String) { require(account.matches(Regex("[a-f0-9]{64}"))); File(root, account).deleteRecursively() }
    data class Usage(val bytes: Long, val count: Int)
    private fun downloads(account: String, protected: Set<String>) = (directory(account).listFiles() ?: throw java.io.IOException("Unable to read transfer directory")).filter {
        it.isFile && it.name.matches(Regex("[a-f0-9]{64}(\\.part)?")) && it.name.removeSuffix(".part") !in protected
    }
    fun downloadedUsage(account: String, protected: Set<String>): Usage {
        val files = downloads(account, protected)
        return Usage(files.sumOf { it.length() }, files.size)
    }
    /** Imported attachments remain available even when they share a downloaded hash. */
    fun clearDownloads(account: String, protected: Set<String>) {
        downloads(account, protected).forEach { check(it.delete() || !it.exists()) { "Unable to remove downloaded file" } }
    }
    fun verified(account: String, file: SyncedFile): File? = local(account, file).takeIf { it.isFile && it.length() == file.bytes && digest(it) == file.hash }
    suspend fun import(account: String, uri: Uri): SyncedFile {
        require(uri.scheme == "content")
        val dir = directory(account)
        require(dir.listFiles().orEmpty().count { it.isFile } < 128)
        val remaining = 256L * 1024 * 1024 - dir.listFiles().orEmpty().sumOf { it.length() }
        require(remaining > 0)
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }.orEmpty()
        val mime = context.contentResolver.getType(uri)?.takeIf { it.matches(Regex("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+")) } ?: "application/octet-stream"
        val temporary = File(dir, "import-" + UUID.randomUUID())
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var length = 0L
            context.contentResolver.openInputStream(uri)!!.use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    length += count; require(length <= minOf(33554432L, remaining))
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
                output.fd.sync()
            } }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val result = SyncedFile(hash, SyncedFile.safeName(name), length, mime)
            val target = local(account, result)
            if (!target.exists()) check(temporary.renameTo(target)) else require(ClientFiles.digest(target) == hash)
            return result
        } finally { temporary.delete() }
    }
    companion object {
        fun digest(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
