package com.funnyworker.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** All account payloads and tokens remain encrypted and excluded from Android backup. */
class SecureStore(context: Context, directoryName: String = "accounts") {
    init { require(directoryName.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) }
    private val directory = File(context.noBackupFilesDir, directoryName).apply { mkdirs() }
    private val alias = "funnywork.account.v1"
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun file(name: String): AtomicFile {
        require(name.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        return AtomicFile(File(directory, "$name.enc"))
    }
    @Synchronized fun read(name: String): JSONObject? {
        val file = file(name)
        if (!file.baseFile.exists()) return null
        val bytes = file.readFully()
        require(bytes.size in 29..(4 * 1024 * 1024))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(name.toByteArray())
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
    @Synchronized fun write(name: String, json: JSONObject) {
        val raw = json.toString().toByteArray()
        require(raw.size < 4 * 1024 * 1024)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray())
        val file = file(name)
        val output = file.startWrite()
        try { output.write(cipher.iv + cipher.doFinal(raw)); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    @Synchronized fun remove(name: String) { file(name).delete() }
}
