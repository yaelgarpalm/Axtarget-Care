package com.example.guardian_iot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Real HTTP on emulator loopback; no project URL, key, email or alert is used. */
@RunWith(AndroidJUnit4::class)
class MedicationHttpContractTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val sessions get() = EncryptedSessionStore(context)
    private val actor = "audit-caregiver"
    private val intake = MedicationIntake("audit-intake", "audit-med", "audit-owner", "audit-slot",
        1_800_000_000_000, 1_800_000_001_000, "taken", null, 0, 1_800_000_000_000, 1_800_000_001_000,
        actorUserId = actor)

    @Before fun prepare() {
        check(context.packageName.endsWith(".audit"))
        sessions.write(AuthSession("fake-access", "fake-refresh", System.currentTimeMillis() / 1000 + 7200,
            actor, "audit@example.invalid"))
    }
    @After fun clean() { sessions.clear() }

    @Test fun sharedActionUsesPatchAndDoesNotSendScheduleOrPrescriptionFields() {
        val saved = JSONArray().put(JSONObject().put("id", intake.id).put("owner_user_id", intake.ownerUserId)
            .put("actor_user_id", actor).put("status", "taken"))
        withServer(200, saved.toString()) { backend, request ->
            backend.updateSharedMedicationIntake(intake)
            val received = request.get(5, TimeUnit.SECONDS)
            assertTrue(received.line.startsWith("PATCH /rest/v1/medication_intakes?id=eq.audit-intake&owner_user_id=eq.audit-owner "))
            val body = JSONObject(received.body)
            assertEquals(setOf("status", "action_at", "actor_user_id", "snoozed_until", "snooze_count", "updated_at"), body.keys().asSequence().toSet())
            assertEquals(actor, body.getString("actor_user_id"))
        }
    }

    @Test fun zeroRowsFromRevokedLinkDoesNotCountAsAcknowledgement() {
        withServer(200, "[]") { backend, _ ->
            assertThrows(BackendFailure::class.java) { backend.updateSharedMedicationIntake(intake) }
        }
    }

    @Test fun mismatchedActorInResponseDoesNotCountAsAcknowledgement() {
        withServer(200, """[{"id":"audit-intake","owner_user_id":"audit-owner","actor_user_id":"different-actor","status":"taken"}]""") { backend, _ ->
            assertThrows(BackendFailure::class.java) { backend.updateSharedMedicationIntake(intake) }
        }
    }

    @Test fun missingActorColumnReturnsOriginalFailureWithoutRetryingAnAnonymousAction() {
        val own = intake.copy(ownerUserId = actor)
        withServer(400, """{"code":"PGRST204","message":"actor_user_id is missing"}""") { backend, request ->
            val failure = assertThrows(BackendFailure::class.java) { backend.upsertMedicationIntake(own) }
            assertEquals(400, failure.status)
            assertEquals(actor, JSONObject(request.get(5, TimeUnit.SECONDS).body).getString("actor_user_id"))
        }
    }

    @Test fun legacyOfflineActionUsesTheAuthenticatedOwnerWhenActorWasMissing() {
        withServer(201, "[]") { backend, request ->
            backend.upsertMedicationIntake(intake.copy(ownerUserId = actor, actorUserId = null))
            assertEquals(actor, JSONObject(request.get(5, TimeUnit.SECONDS).body).getString("actor_user_id"))
        }
    }

    @Test fun caregiverSharedEventHistoryUsesStableBoundedOffsetPages() {
        withServer(200, """[{"id":"audit-event","occurred_at":"2026-11-03T10:00:00Z"}]""") { backend, request ->
            val page = backend.sharedEventPage("audit-owner", "audit-device", offset = 200, limit = 50)
            assertEquals("audit-event", page.getJSONObject(0).getString("id"))
            val sent = request.get(5, TimeUnit.SECONDS)
            assertTrue(sent.line.startsWith("GET /rest/v1/fall_events?select="))
            assertTrue(sent.line.contains("user_id=eq.audit-owner&device_id=eq.audit-device&order=occurred_at.desc,id.desc&limit=50&offset=200"))
            assertEquals("Bearer fake-access", sent.headers["authorization"])
        }
    }

    @Test fun profileSyncDoesNotSendTheDeviceRole() {
        withServer(200, """[{"user_id":"$actor","full_name":"Persona","preferences":{}}]""") { backend, request ->
            backend.updateProfile("Persona", JSONObject().put("privacy_notice_version", "1"))
            val body = JSONObject(request.get(5, TimeUnit.SECONDS).body)
            assertEquals(setOf("full_name", "preferences"), body.keys().asSequence().toSet())
            assertFalse(body.has("role"))
            assertFalse(body.has("user_role"))
        }
    }

    private data class CapturedRequest(val line: String, val body: String, val headers: Map<String, String>)

    @Test fun cloudScanUsesAuthenticatedFunctionAndSendsTheImageAndDocumentKind() {
        withServer(200, """{"scan":{"medications":[{"name":"Tempra"},{"name":"Losartán"}]}}""") { backend, request ->
            val result = backend.readMedicationLabel("test-base64", MedicationDocument.PRESCRIPTION).getJSONObject("scan")
            assertEquals(2, result.getJSONArray("medications").length())
            val sent = request.get(5, TimeUnit.SECONDS)
            assertTrue(sent.line.startsWith("POST /functions/v1/medication-label "))
            assertEquals("Bearer fake-access", sent.headers["authorization"])
            val body = JSONObject(sent.body)
            assertEquals(setOf("image_base64", "document"), body.keys().asSequence().toSet())
            assertEquals("prescription", body.getString("document"))
        }
    }

    @Test fun googleSignInSendsTheIdTokenAndNonceAndUsesTheNormalSessionStore() {
        withServer(200, """{"access_token":"google-session","refresh_token":"google-refresh","expires_in":3600,"user":{"id":"$actor","email":"person@example.test"}}""") { backend, request ->
            val session = backend.signInWithGoogle("test-id-token", "test-original-nonce")
            assertEquals("google-session", session.accessToken)
            assertEquals(session, sessions.read())
            val sent = request.get(5, TimeUnit.SECONDS)
            assertTrue(sent.line.startsWith("POST /auth/v1/token?grant_type=id_token "))
            val body = JSONObject(sent.body)
            assertEquals("google", body.getString("provider"))
            assertEquals("test-id-token", body.getString("id_token"))
            assertEquals("test-original-nonce", body.getString("nonce"))
        }
    }

    @Test fun unoQTokenIsEncryptedAtRestAndCanBeReadBack() {
        val prefs = context.getSharedPreferences("tzinda_native", android.content.Context.MODE_PRIVATE)
        val token = "audit-uno-q-token-0123456789abcdef"
        try {
            UnoQLocalAuth.saveToken(context, token)
            assertEquals(token, UnoQLocalAuth.token(context))
            assertFalse(prefs.getString("uno_q_api_token_encrypted", "").orEmpty().contains(token))
        } finally {
            prefs.edit().remove("uno_q_api_token_encrypted").commit()
        }
    }

    @Test fun cloudScanBoundsTheResponseBeforeParsing() {
        withServer(200, "x".repeat(64 * 1024 + 1)) { backend, _ ->
            assertThrows(BackendFailure::class.java) { backend.readMedicationLabel("test-base64", MedicationDocument.LABEL) }
        }
    }

    @Test fun cloudScanRequiresAnActiveSessionBeforeOpeningTheConnection() {
        sessions.clear()
        val backend = SupabaseBackend(sessions, "http://127.0.0.1:1", "sb_publishable_audit_fake", "")
        assertEquals(BackendFailure.Category.SESSION,
            assertThrows(BackendFailure::class.java) { backend.readMedicationLabel("test-base64", MedicationDocument.LABEL) }.category)
    }

    @Test fun zeroDeletedCareRowsDoesNotAcknowledgeAnUnconfirmedDeletion() {
        withServer(200, "[]") { backend, _ ->
            assertThrows(BackendFailure::class.java) { backend.deleteCareRecord("audit-owner", "audit-care") }
        }
    }

    @Test fun careDeletionAcknowledgesTheExactReturnedOwnerAndId() {
        withServer(200, """[{"id":"audit-care","owner_user_id":"audit-owner"}]""") { backend, request ->
            backend.deleteCareRecord("audit-owner", "audit-care")
            assertTrue(request.get(5, TimeUnit.SECONDS).line.startsWith("DELETE /rest/v1/care_records?id=eq.audit-care&owner_user_id=eq.audit-owner "))
        }
    }

    private fun withServer(status: Int, response: String, block: (SupabaseBackend, CompletableFuture<CapturedRequest>) -> Unit) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5_000 }
        val captured = CompletableFuture<CapturedRequest>()
        val worker = Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val line = reader.readLine()
                    var length = 0
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isEmpty()) break
                        headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                        if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
                    }
                    val chars = CharArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = reader.read(chars, offset, length - offset)
                        check(count > 0)
                        offset += count
                    }
                    captured.complete(CapturedRequest(line, String(chars), headers))
                    val bytes = response.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                        write(bytes)
                        flush()
                    }
                }
            } catch (failure: Throwable) { captured.completeExceptionally(failure) }
            finally { server.close() }
        }.apply { start() }
        try {
            block(SupabaseBackend(sessions, "http://127.0.0.1:${server.localPort}", "sb_publishable_audit_fake", ""), captured)
            captured.get(5, TimeUnit.SECONDS)
        } finally {
            server.close()
            worker.join(5_000)
        }
    }
}
