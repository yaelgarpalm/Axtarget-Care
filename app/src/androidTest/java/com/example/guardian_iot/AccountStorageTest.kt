package com.example.guardian_iot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AccountStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val ownerA = "audit-account-a"
    private val ownerB = "audit-account-b-${UUID.randomUUID()}"

    @Before fun prepare() {
        check(context.packageName.endsWith(".audit"))
        context.getSharedPreferences("tzinda_account_storage", 0).edit().clear().commit()
        context.getSharedPreferences("tzinda_native", 0).edit().clear().putBoolean("migration_done", true)
            .putString("local_data_owner_id", ownerA).commit()
        EncryptedSessionStore(context).clear()
        CareFeatureStore(context).clearAll()
        MedicationDatabase.getInstance(context).clearAll()
    }
    @After fun restoreLegacyScope() {
        AccountStorage.activate(context, ownerA)
        context.getSharedPreferences("tzinda_account_storage", 0).edit().clear().commit()
        context.getSharedPreferences("tzinda_native", 0).edit().clear().putBoolean("migration_done", true).commit()
        CareFeatureStore(context).clearAll()
        EncryptedSessionStore(context).clear()
    }

    @Test fun switchAtoBtoARestoresPendingDataWithoutCopyingItToB() {
        val a = AppStore(context)
        a.saveRole(AppStore.ROLE_WEARER)
        a.saveProfile(JSONObject().put("fullName", "Persona A").put("dataConsent", true))
        a.saveContacts(listOf(TrustedContact("Contacto A", "5500001122")))
        val careA = CareFeatureStore(a.appContext)
        careA.upsert("journal", JSONObject().put("description", "Private A"))
        val medA = MedicationDatabase.getInstance(a.appContext)
        medA.saveMedication(Medication(id = "a-med", ownerUserId = ownerA, name = "A medicine", prescribedDose = "test",
            presentation = "", frequency = "Diaria", scheduleTimes = listOf("08:00"), startDate = "2026-11-01", endDate = null, instructions = "", isActive = true))
        assertTrue(a.bindLocalDataToUser(ownerB))
        val b = AppStore(context)
        assertEquals(ownerB, b.ownerUserId())
        assertTrue(b.contacts().isEmpty())
        assertEquals("", b.profile().optString("fullName"))
        assertTrue(CareFeatureStore(context).rowsAll().isEmpty())
        assertTrue(MedicationDatabase.getInstance(context).medications(ownerA).isEmpty())
        b.saveProfile(JSONObject().put("fullName", "Persona B").put("dataConsent", true))
        // A task that already captured A stays in A's files, never in B's namespace.
        careA.upsert("journal", JSONObject().put("description", "A queued edit"))
        assertTrue(CareFeatureStore(context).rowsAll().isEmpty())
        assertTrue(b.bindLocalDataToUser(ownerA))
        val reopened = AppStore(context)
        assertEquals("Persona A", reopened.profile().getString("fullName"))
        assertEquals("Contacto A", reopened.contacts().single().name)
        assertEquals(2, CareFeatureStore(context).syncState().pending)
        assertEquals("a-med", MedicationDatabase.getInstance(context).pendingMedications(ownerA).single().id)
        assertTrue(reopened.telemetry.syncStateSummary().pending > 0)
    }

    @Test fun expiredOwnerCannotReadOrClearNewAccountSession() {
        val sessions = EncryptedSessionStore(context)
        val a = session(ownerA)
        sessions.write(a)
        val oldBackend = SupabaseBackend(sessions)
        val b = session(ownerB)
        sessions.write(b)
        assertThrows(BackendFailure::class.java) { oldBackend.currentSession() }
        runCatching { oldBackend.signOut() } // audit build has no network configuration
        assertEquals(b, sessions.read())
        assertFalse(sessions.writeIfCurrent(a, a.copy(accessToken = "late-refresh")))
        assertEquals(b, sessions.read())
    }

    @Test fun staleStoreDetectsSwitchAlreadyPerformedByBackgroundRunner() {
        val a = AppStore(context)
        AccountStorage.activate(context, ownerB)
        assertTrue(a.bindLocalDataToUser(ownerB))
        assertEquals(ownerA, a.ownerUserId())
        assertEquals(ownerB, AppStore(context).ownerUserId())
    }

    @Test fun activityRestoresSessionAccountBeforeShowingPreviousOwnersData() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val a = AppStore(context)
        a.saveProfile(JSONObject().put("fullName", "Persona A privada").put("dataConsent", true))
        EncryptedSessionStore(context).write(session(ownerB))
        val activity = instrumentation.startActivitySync(android.content.Intent(context, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.waitForIdleSync()
            assertEquals(ownerB, AppStore(context).ownerUserId())
            assertEquals("", AppStore(context).profile().optString("fullName"))
            assertEquals("Persona A privada", a.profile().getString("fullName"))
            instrumentation.runOnMainSync {
                fun labels(view: android.view.View): List<String> = when (view) {
                    is android.view.ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
                    is android.widget.TextView -> listOf(view.text.toString())
                    else -> emptyList()
                }
                assertFalse(labels(activity.window.decorView).any { it.contains("Persona A privada") })
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun session(owner: String) = AuthSession("fake-access-$owner", "fake-refresh-$owner",
        System.currentTimeMillis() / 1000 + 7200, owner, "$owner@example.invalid")
}
