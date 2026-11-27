package com.example.guardian_iot

import android.app.Activity
import android.util.Base64
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

internal object GoogleSignIn {
    private val clientId = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim()
    val isConfigured: Boolean get() = clientId.endsWith(".apps.googleusercontent.com")

    suspend fun signIn(activity: Activity, backend: SupabaseBackend): AuthSession {
        check(isConfigured && backend.isConfigured)
        val randomBytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val nonce = Base64.encodeToString(randomBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val hashedNonce = MessageDigest.getInstance("SHA-256")
            .digest(nonce.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        val option = GetSignInWithGoogleOption.Builder(clientId).setNonce(hashedNonce).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
        val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
        return withContext(Dispatchers.IO) { backend.signInWithGoogle(token, nonce) }
    }

    suspend fun clear(activity: Activity) {
        if (isConfigured) CredentialManager.create(activity).clearCredentialState(ClearCredentialStateRequest())
    }
}
