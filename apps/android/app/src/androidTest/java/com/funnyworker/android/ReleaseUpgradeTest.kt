package com.funnyworker.android

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Opt-in signed-package upgrade audit. No activity, login, network request or real-account mutation. */
class ReleaseUpgradeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val marker get() = File(context.filesDir, "release-upgrade-audit.json")
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun accountHashes(except: String): JSONObject = JSONObject().apply {
        File(context.noBackupFilesDir, "accounts").listFiles().orEmpty()
            .filter { it.isFile && it.name != "$except.enc" }.sortedBy { it.name }
            .forEach { put(sha(it.name.toByteArray()), sha(it.readBytes())) }
    }
    private fun assertReleaseIdentity() {
        assertEquals("com.funnyworker.android", context.packageName)
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = info.signingInfo!!.apkContentsSigners.single()
        assertEquals("31033cf31c186c9628dda0ffd148e04a6c0e0d36e78cbcd6501aa934b6cb2598", sha(signer.toByteArray()))
    }
    @Test fun seedBeforeUpgrade() {
        assumeTrue(args.getString("releaseUpgradeAudit") == "true")
        assertReleaseIdentity()
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        assertEquals(args.getString("fromCode")!!.toLong(), version)
        assertFalse("An earlier upgrade audit must be resolved first", marker.exists())
        val key = "release_upgrade_" + UUID.randomUUID().toString().replace("-", "")
        val payload = JSONObject().put("draft", "Preserve this task draft").put("workspace", "local-audit")
            .put("history", JSONObject().put("selected", "local-chat").put("readThrough", 17))
        SecureStore(context).write(key, payload)
        marker.writeText(JSONObject().put("key", key).put("fromCode", version).put("payload", payload)
            .put("accounts", accountHashes(key)).toString())
        println("SIGNED_UPGRADE_SEEDED code=$version; existing account payloads were not decrypted")
    }
    @Test fun verifyAfterUpgrade() {
        assumeTrue(args.getString("releaseUpgradeAudit") == "true")
        assertReleaseIdentity()
        val saved = JSONObject(marker.readText()); val key = saved.getString("key")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        assertEquals(args.getString("toCode")!!.toLong(), version)
        assertTrue(version > saved.getLong("fromCode"))
        val restored = SecureStore(context).read(key)!!
        assertEquals(saved.getJSONObject("payload").toString(), restored.toString())
        val before = saved.getJSONObject("accounts"); val after = accountHashes(key)
        assertEquals(before.keys().asSequence().toSet(), after.keys().asSequence().toSet())
        before.keys().forEach { assertEquals(before.getString(it), after.getString(it)) }
        SecureStore(context).remove(key); assertTrue(marker.delete())
        println("SIGNED_UPGRADE_VERIFIED from=${saved.getLong("fromCode")} to=$version; account files unchanged; encrypted draft and history readable")
    }
}
