package com.funnyworker.android

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class SecureStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun encryptedPayloadsCannotBeSwappedBetweenAccountNames() {
        val store = SecureStore(context)
        val a = "test_" + UUID.randomUUID().toString().replace("-", "")
        val b = "test_" + UUID.randomUUID().toString().replace("-", "")
        try {
            store.write(a, JSONObject().put("text", "private-draft-A"))
            store.write(b, JSONObject().put("text", "private-draft-B"))
            assertEquals("private-draft-A", store.read(a)!!.getString("text"))
            val directory = File(context.noBackupFilesDir, "accounts")
            val raw = File(directory, "$a.enc").readBytes()
            assertFalse(String(raw, Charsets.ISO_8859_1).contains("private-draft-A"))
            File(directory, "$b.enc").writeBytes(raw)
            assertThrows(Exception::class.java) { store.read(b) }
            assertEquals("private-draft-A", store.read(a)!!.getString("text"))
        } finally { store.remove(a); store.remove(b) }
    }
    @Test fun corruptCiphertextFailsWithoutDeletingTheOnlyDraftCopy() {
        val store = SecureStore(context)
        val name = "test_" + UUID.randomUUID().toString().replace("-", "")
        try {
            store.write(name, JSONObject().put("text", "saved-draft"))
            val file = File(File(context.noBackupFilesDir, "accounts"), "$name.enc")
            val data = file.readBytes(); data[data.lastIndex] = (data.last().toInt() xor 1).toByte(); file.writeBytes(data)
            assertThrows(Exception::class.java) { store.read(name) }
            assertTrue(file.exists())
        } finally { store.remove(name) }
    }
}
