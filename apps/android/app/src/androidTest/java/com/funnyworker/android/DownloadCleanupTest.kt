package com.funnyworker.android

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DownloadCleanupTest {
    @Test fun cleanupPreservesEncryptedDraftsOtherAccountsAndStagedAttachmentHashes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = ClientFiles(context); val store = SecureStore(context)
        fun key() = UUID.randomUUID().toString().replace("-", "").repeat(2)
        val account = key(); val other = key()
        fun file(accountKey: String, value: String): SyncedFile {
            val temporary = files.directory(accountKey).resolve("fixture")
            temporary.writeText(value)
            val file = SyncedFile(ClientFiles.digest(temporary), "fixture.txt", temporary.length(), "text/plain")
            check(temporary.renameTo(files.local(accountKey, file))); return file
        }
        try {
            store.write(account, JSONObject().put("drafts", JSONObject().put("mobile:home", "Keep all text")))
            val staged = file(account, "Pending attachment shared with download")
            val downloaded = file(account, "Downloaded result")
            val partial = files.partial(account, downloaded); partial.writeText("partial")
            val foreign = file(other, "Other account")
            val import = files.directory(account).resolve("import-active").apply { writeText("Owned by import") }
            val protected = setOf(staged.hash)
            assertEquals(2, files.downloadedUsage(account, protected).count)
            assertEquals(downloaded.bytes + partial.length(), files.downloadedUsage(account, protected).bytes)
            files.clearDownloads(account, protected)
            assertEquals(0, files.downloadedUsage(account, protected).count)
            assertNotNull(files.verified(account, staged)); assertNotNull(files.verified(other, foreign))
            assertTrue(import.exists())
            assertEquals("Keep all text", store.read(account)!!.getJSONObject("drafts").getString("mobile:home"))
            files.clearDownloads(account, protected) // A second cleanup is harmless.
        } finally { files.clear(account); files.clear(other); store.remove(account) }
    }
}
