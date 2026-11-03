package com.example.guardian_iot

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

/** No email is sent: HTTP stays on emulator loopback and all credentials are fictional. */
@RunWith(AndroidJUnit4::class)
class PasswordRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val sessions get() = EncryptedSessionStore(context)
    private val active = AuthSession("active-token", "active-refresh", System.currentTimeMillis() / 1000 + 7200,
        "active-owner", "active@example.invalid")
    private val redirect = "com.guardian.tzindaapp.v2://auth-callback"
    private val callback get() = Uri.parse("$redirect?code=one-use-code")
    private val email = "recover@example.invalid"

    @Before fun prepare() {
        check(context.packageName.endsWith(".audit"))
        sessions.recovery.clear()
        sessions.write(active)
    }
    @After fun clean() { sessions.recovery.clear(); sessions.clear() }

    @Test fun unsolicitedAndImplicitCallbacksNeverReplaceAnExistingSession() {
        val backend = SupabaseBackend(sessions)
        listOf(callback, Uri.parse("$redirect#access_token=attacker&refresh_token=attacker&type=recovery"),
            Uri.parse("$redirect?code=a&code=b"), Uri.parse("$redirect/other?code=a"),
            Uri.parse("$redirect?code=a&access_token=attacker"), Uri.parse("https://attacker.invalid?code=a"))
            .forEach { uri -> assertThrows(BackendFailure::class.java) { backend.acceptPasswordRecovery(uri) } }
        assertEquals(active, sessions.read())
        assertFalse(backend.hasPasswordRecovery)
    }

    @Test fun pkceSurvivesReopeningAndUpdatesOnlyTheIsolatedRecoveryAccount() {
        Loopback(listOf(200 to "{}", 200 to token(), 200 to """{"id":"recovered-owner"}""")).use { http ->
            val first = http.backend()
            first.requestPasswordRecovery(email)
            val sent = http.next()
            assertTrue(sent.line.startsWith("POST /auth/v1/recover?redirect_to="))
            assertEquals("s256", sent.body.getString("code_challenge_method"))
            val reopened = http.backend()
            val recovered = reopened.acceptPasswordRecovery(callback)
            val exchange = http.next()
            assertEquals("recovered-owner", recovered.userId)
            assertTrue(exchange.line.startsWith("POST /auth/v1/token?grant_type=pkce "))
            assertEquals("one-use-code", exchange.body.getString("auth_code"))
            assertEquals(sent.body.getString("code_challenge"), PasswordRecoveryStore.challenge(exchange.body.getString("code_verifier")))
            assertEquals(43, exchange.body.getString("code_verifier").length)
            assertEquals(active, sessions.read())
            assertTrue(http.backend().hasPasswordRecovery)
            val ciphertext = File(context.noBackupFilesDir, "password-recovery.v1").readText()
            assertFalse(ciphertext.contains("recovery-access"))
            assertFalse(ciphertext.contains(email))
            reopened.updatePassword("fictional-pass-123")
            val update = http.next()
            assertTrue(update.line.startsWith("PUT /auth/v1/user "))
            assertEquals("Bearer recovery-access", update.headers["authorization"])
            assertEquals("fictional-pass-123", update.body.getString("password"))
            assertEquals(active, sessions.read())
            assertFalse(reopened.hasPasswordRecovery)
            assertThrows(BackendFailure::class.java) { reopened.updatePassword("repeat") }
            assertThrows(BackendFailure::class.java) { reopened.acceptPasswordRecovery(callback) }
        }
    }

    @Test fun anExchangeForAnotherEmailIsRejectedWithoutBindingLocalData() {
        Loopback(listOf(200 to "{}", 200 to token("other@example.invalid"))).use { http ->
            val backend = http.backend()
            backend.requestPasswordRecovery(email)
            assertThrows(BackendFailure::class.java) { backend.acceptPasswordRecovery(callback) }
            assertEquals(active, sessions.read())
            assertFalse(backend.hasPasswordRecovery)
            assertNull(sessions.recovery.claim())
        }
    }

    @Test fun expiredAndConsumedFlowsCannotBeReusedAfterProcessRecreation() {
        var clock = System.currentTimeMillis()
        val store = PasswordRecoveryStore(context) { clock }
        store.begin(email)
        clock += 60 * 60 * 1000 + 1
        assertNull(store.claim())
        store.begin(email)
        assertNotNull(store.claim())
        assertNull(PasswordRecoveryStore(context) { clock }.claim())
        assertEquals(active, sessions.read())
    }

    @Test fun laterRequestOrCancellationRejectsTheOldExchangeResult() {
        val store = sessions.recovery
        val old = store.begin(email)
        assertNotNull(store.claim())
        val next = store.begin("next@example.invalid")
        assertFalse(store.complete(old.getString("id"), active))
        store.clear(old.getString("id"))
        assertEquals(next.getString("id"), store.claim()?.getString("id"))
        store.clear()
        assertFalse(store.complete(next.getString("id"), active))
        assertEquals(active, sessions.read())
    }

    @Test fun failedExchangeRequiresANewRequestAndDoesNotKeepTheVerifier() {
        Loopback(listOf(200 to "{}", 400 to """{"code":"invalid_grant"}""")).use { http ->
            val backend = http.backend()
            backend.requestPasswordRecovery(email)
            val failure = assertThrows(BackendFailure::class.java) { backend.acceptPasswordRecovery(callback) }
            assertEquals(BackendFailure.Category.RECOVERY, failure.category)
            assertNull(sessions.recovery.claim())
            assertEquals(active, sessions.read())
        }
    }

    @Test fun failedEmailRequestDoesNotLeaveAnAcceptableRecovery() {
        Loopback(listOf(429 to "{}")).use { http ->
            val backend = http.backend()
            val failure = assertThrows(BackendFailure::class.java) { backend.requestPasswordRecovery(email) }
            assertEquals(BackendFailure.Category.EMAIL_RATE_LIMIT, failure.category)
            assertNull(sessions.recovery.claim())
            assertEquals(active, sessions.read())
        }
    }

    @Test fun recoveryAuthorizationFailureDoesNotRefreshOrUseTheActiveSession() {
        Loopback(listOf(200 to "{}", 200 to token(), 401 to "{}")).use { http ->
            val backend = http.backend()
            backend.requestPasswordRecovery(email)
            backend.acceptPasswordRecovery(callback)
            assertThrows(BackendFailure::class.java) { backend.updatePassword("fictional-pass-123") }
            assertEquals(active, sessions.read())
            backend.cancelPasswordRecovery()
            assertFalse(backend.hasPasswordRecovery)
        }
    }

    @Test fun ordinarySignInInvalidatesPendingRecovery() {
        Loopback(listOf(200 to "{}", 200 to token())).use { http ->
            val backend = http.backend()
            backend.requestPasswordRecovery(email)
            backend.signIn(email, "fictional-pass-123")
            assertNull(sessions.recovery.claim())
            assertThrows(BackendFailure::class.java) { backend.acceptPasswordRecovery(callback) }
            assertEquals("recovered-owner", sessions.read()?.userId)
        }
    }

    @Test fun delayedExchangeShowsResetInTheRecreatedActivity() {
        val released = CountDownLatch(1)
        sessions.recovery.begin(email)
        val app = AppStore(context)
        app.bindLocalDataToUser(active.userId)
        AppStore(context).prefs.edit().putBoolean("monitor_enabled", false).commit()
        Loopback(listOf(200 to token()), released).use { http ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredField("backend").apply { isAccessible = true }.set(activity, http.backend())
                    InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity,
                        android.content.Intent(activity.intent).setData(callback))
                }
                http.next() // Request is in flight; response remains held by the test server.
                scenario.recreate()
                released.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                var shown = false
                while (!shown && System.nanoTime() < deadline) {
                    scenario.onActivity { activity ->
                        fun containsReset(view: android.view.View): Boolean = when (view) {
                            is android.view.ViewGroup -> (0 until view.childCount).any { containsReset(view.getChildAt(it)) }
                            is android.widget.TextView -> view.text.toString() == "Nueva contraseña"
                            else -> false
                        }
                        shown = containsReset(activity.window.decorView)
                    }
                    if (!shown) Thread.sleep(100)
                }
                assertTrue("The resumed Activity must observe the completed recovery", shown)
                assertEquals(active, sessions.read())
            }
        }
    }

    private fun token(forEmail: String = email) = JSONObject()
        .put("access_token", "recovery-access").put("refresh_token", "recovery-refresh").put("expires_in", 3600)
        .put("user", JSONObject().put("id", "recovered-owner").put("email", forEmail)).toString()

    private data class Captured(val line: String, val headers: Map<String, String>, val body: JSONObject)
    private inner class Loopback(responses: List<Pair<Int, String>>, private val releaseResponse: CountDownLatch? = null) : AutoCloseable {
        private val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 10_000 }
        private val requests = LinkedBlockingQueue<Captured>()
        private var failure: Throwable? = null
        private val worker = Thread {
            try {
                responses.forEach { (status, response) ->
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader()
                        val line = reader.readLine()
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val h = reader.readLine() ?: break
                            if (h.isEmpty()) break
                            headers[h.substringBefore(':').lowercase()] = h.substringAfter(':').trim()
                        }
                        val chars = CharArray(headers["content-length"]?.toInt() ?: 0)
                        var offset = 0
                        while (offset < chars.size) { val n = reader.read(chars, offset, chars.size - offset); check(n > 0); offset += n }
                        requests.put(Captured(line, headers, JSONObject(String(chars).ifBlank { "{}" })))
                        check(releaseResponse?.await(10, TimeUnit.SECONDS) != false)
                        val bytes = response.toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 $status Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    }
                }
            } catch (error: Throwable) { failure = error }
        }.apply { start() }
        fun backend() = SupabaseBackend(sessions, "http://127.0.0.1:${server.localPort}", "sb_publishable_audit_fake", redirect)
        fun next(): Captured = requests.poll(5, TimeUnit.SECONDS) ?: error("Missing local HTTP request")
        override fun close() {
            worker.join(5000)
            server.close()
            worker.join(1000)
            failure?.let { throw AssertionError("Local HTTP fixture failed", it) }
            assertFalse(worker.isAlive)
        }
    }
}
