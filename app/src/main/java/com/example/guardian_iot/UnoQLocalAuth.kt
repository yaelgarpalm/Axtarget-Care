package com.example.guardian_iot

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** Keeps the local UNO Q API token encrypted with a non-exportable Android Keystore key. */
internal object UnoQLocalAuth {
    private const val PREFS = "tzinda_native"
    private const val TOKEN_KEY = "uno_q_api_token_encrypted"
    private const val KEY_ALIAS = "tzinda_uno_q_local_api"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun isValidToken(token: String): Boolean = token == token.trim() &&
        token.toByteArray(StandardCharsets.UTF_8).size >= MIN_TOKEN_BYTES &&
        token.none(Char::isWhitespace)

    fun saveToken(context: Context, token: String) {
        require(isValidToken(token)) { "El token debe tener al menos 32 bytes y no contener espacios." }
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = preferences.edit()
        putEncryptedToken(editor, context, token)
        check(editor.commit()) { "No se pudo guardar el token del UNO Q." }
    }

    internal fun putEncryptedToken(editor: SharedPreferences.Editor, context: Context, token: String) {
        require(isValidToken(token)) { "El token debe tener al menos 32 bytes y no contener espacios." }
        editor.putString(TOKEN_KEY, encryptToken(context, token))
    }

    private fun encryptToken(context: Context, token: String): String {
        val scope = AccountStorage.key(context).toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
            updateAAD(scope)
        }
        val encrypted = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
        val packed = cipher.iv + encrypted
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    fun token(context: Context): String? = runCatching {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TOKEN_KEY, null) ?: return null
        tokenFromEncrypted(context, encoded)
    }.getOrNull()

    internal fun tokenFromSnapshot(context: Context, snapshot: Map<String, *>): String? =
        tokenFromEncrypted(context, snapshot[TOKEN_KEY] as? String)

    internal fun tokenFromEncrypted(context: Context, encoded: String?): String? = runCatching {
        encoded ?: return null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > GCM_IV_BYTES)
        val scope = AccountStorage.key(context).toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, packed.copyOfRange(0, GCM_IV_BYTES)))
        cipher.updateAAD(scope)
        cipher.doFinal(packed.copyOfRange(GCM_IV_BYTES, packed.size)).toString(StandardCharsets.UTF_8)
            .takeIf(::isValidToken)
    }.getOrNull()

    private fun secretKey(): java.security.Key = (KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        .getKey(KEY_ALIAS, null) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        })

    private const val MIN_TOKEN_BYTES = 32
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
}
