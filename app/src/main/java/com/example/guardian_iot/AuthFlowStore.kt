package com.example.guardian_iot

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Persists one pending PKCE flow across browser launches and Activity recreation. */
internal class AuthFlowStore(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    enum class Purpose { RECOVERY, GOOGLE }

    private val file = AtomicFile(File(context.noBackupFilesDir, "password-recovery.v1"))
    val revision: Long get() = changes.get()

    fun begin(purpose: Purpose, email: String = ""): JSONObject = synchronized(lock) {
        JSONObject().put("id", random()).put("purpose", purpose.name).put("email", email.trim())
            .put("verifier", random()).put("created_at", now()).put("phase", "pending")
            .also(::write)
    }

    fun hasGoogleFlow(): Boolean = synchronized(lock) { read()?.isFor(Purpose.GOOGLE) == true }

    /** Claim before exchanging: duplicate callbacks cannot exchange the same local flow. */
    fun claim(purpose: Purpose): JSONObject? = synchronized(lock) {
        val state = read() ?: return null
        if (!state.isFor(purpose) || state.optString("phase") != "pending") return null
        write(JSONObject(state.toString()).put("phase", "exchanging").removeVerifier())
        state
    }

    fun completeRecovery(id: String, session: AuthSession): Boolean = synchronized(lock) {
        val state = read() ?: return false
        if (!state.isExchanging(Purpose.RECOVERY, id)) return false
        state.put("phase", "ready").put("access_token", session.accessToken)
            .put("refresh_token", session.refreshToken).put("expires_at", session.expiresAtSeconds)
            .put("user_id", session.userId)
        write(state)
        true
    }

    fun isExchangingGoogle(id: String): Boolean = synchronized(lock) {
        read()?.isExchanging(Purpose.GOOGLE, id) == true
    }

    fun ready(): JSONObject? = synchronized(lock) {
        read()?.takeIf { it.isFor(Purpose.RECOVERY) && it.optString("phase") == "ready" && it.optLong("expires_at") > now() / 1000 }
    }

    fun clear(id: String? = null, purpose: Purpose? = null): Unit = synchronized(lock) {
        if (id != null || purpose != null) {
            val state = read() ?: return@synchronized
            if (id != null && state.optString("id") != id) return@synchronized
            if (purpose != null && !state.isFor(purpose)) return@synchronized
        }
        file.delete()
        changes.incrementAndGet()
    }

    // State written by earlier versions only contains password recovery attempts.
    private fun JSONObject.isFor(purpose: Purpose): Boolean =
        optString("purpose", Purpose.RECOVERY.name) == purpose.name

    private fun JSONObject.isExchanging(purpose: Purpose, id: String): Boolean {
        return isFor(purpose) && optString("id") == id && optString("phase") == "exchanging"
    }

    private fun read(): JSONObject? = runCatching {
        val parts = file.openRead().bufferedReader().use { it.readText() }.split(':', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
            .takeIf { now() - it.optLong("created_at") in 0..MAX_AGE_MS }
    }.getOrNull()

    private fun write(state: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.doFinal(state.toString().toByteArray(Charsets.UTF_8))
        val text = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(bytes, Base64.NO_WRAP)
        val output = file.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
            changes.incrementAndGet()
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun JSONObject.removeVerifier() = apply { remove("verifier") }

    companion object {
        private val lock = Any()
        private val changes = AtomicLong()
        private const val KEY_ALIAS = "tzinda.password.recovery.v1"
        private const val MAX_AGE_MS = 60 * 60 * 1000L
        private fun random() = ByteArray(32).also(SecureRandom()::nextBytes).let(::base64Url)
        private fun base64Url(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        fun challenge(verifier: String): String = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }
}
