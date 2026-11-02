package com.example.guardian_iot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MedicationAuthorizationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val actor = "audit-medication-actor"
    private val protectedOwner = "audit-protected-owner"
    private lateinit var store: AppStore
    private lateinit var repository: MedicationRepository
    private val database get() = repository.database

    @Before fun prepare() {
        check(context.packageName.endsWith(".audit"))
        context.getSharedPreferences("tzinda_account_storage", 0).edit().clear().commit()
        context.getSharedPreferences("tzinda_native", 0).edit().clear().putBoolean("migration_done", true)
            .putString("local_data_owner_id", actor).commit()
        EncryptedSessionStore(context).write(AuthSession("fake-access", "fake-refresh",
            System.currentTimeMillis() / 1000 + 7200, actor, "audit@example.invalid"))
        store = AppStore(context)
        store.saveRole(AppStore.ROLE_WEARER)
        repository = MedicationRepository(context, SupabaseBackend(EncryptedSessionStore(context)), store)
        database.clearAll()
    }

    @After fun clean() {
        database.allActiveMedications().forEach { MedicationReminderScheduler.cancelMedication(context, it) }
        database.intakes(actor).forEach { MedicationReminderScheduler.cancelOverdueCheck(context, it.id) }
        database.clearAll()
        EncryptedSessionStore(context).clear()
        store.prefs.edit().clear().putBoolean("migration_done", true).commit()
    }

    @Test fun ownerCanEditOwnMedicationButCannotSupplyAnotherOwnerOrExistingRecord() {
        val own = create(actor)
        assertEquals(actor, own.ownerUserId)
        assertThrows(IllegalStateException::class.java) { create(protectedOwner) }
        val foreign = database.saveMedication(medication(protectedOwner))
        assertThrows(IllegalStateException::class.java) { create(actor, foreign) }
        assertThrows(IllegalStateException::class.java) { repository.deleteMedication(protectedOwner, foreign.id) }
        assertThrows(IllegalStateException::class.java) { repository.shareAllWithCaregiver(protectedOwner) }
        assertNotNull(database.getMedication(protectedOwner, foreign.id))
    }

    @Test fun caregiverCanRecordSharedIntakeWithActorButCannotEditPrescription() {
        link()
        val med = database.saveMedication(medication(protectedOwner, shared = true))
        val intake = database.ensureIntake(med, System.currentTimeMillis())
        assertTrue(repository.acceptIntakeAction(intake.id, "taken"))
        assertEquals(actor, database.getIntake(intake.id)?.actorUserId)
        assertEquals("taken", database.getIntake(intake.id)?.status)
        assertFalse(repository.acceptIntakeAction(intake.id, "skipped"))
        assertThrows(IllegalStateException::class.java) { create(protectedOwner, med) }
        assertThrows(IllegalStateException::class.java) { repository.shareAllWithCaregiver(protectedOwner) }
    }

    @Test fun privateOrDifferentPersonsIntakesAreRejectedWithoutChangingThem() {
        link()
        listOf(medication(protectedOwner), medication("different-owner", shared = true)).forEach { med ->
            database.saveMedication(med)
            val intake = database.ensureIntake(med, System.currentTimeMillis())
            assertFalse(repository.acceptIntakeAction(intake.id, "taken"))
            assertEquals("pending", database.getIntake(intake.id)?.status)
            assertNull(database.getIntake(intake.id)?.actorUserId)
        }
    }

    @Test fun revokedLinkBlocksCachedIntakeAndKeepsOfflineEvidence() {
        link()
        val med = database.saveMedication(medication(protectedOwner, shared = true))
        val intake = database.ensureIntake(med, System.currentTimeMillis())
        assertTrue(repository.canActOnIntake(intake.id))
        store.clearRemoteDerivedCaches()
        assertFalse(repository.canReadSharedOwner(protectedOwner))
        assertFalse(repository.acceptIntakeAction(intake.id, "taken"))
        assertNotNull(database.getIntake(intake.id))
    }

    @Test fun changedPersonOrMissingSessionInvalidatesAlreadyOpenedRepository() {
        link()
        val med = database.saveMedication(medication(protectedOwner, shared = true))
        val intake = database.ensureIntake(med, System.currentTimeMillis())
        store.prefs.edit().putString("caregiver_owner_id", "different-owner").commit()
        assertFalse(repository.acceptIntakeAction(intake.id, "taken"))
        link()
        EncryptedSessionStore(context).clear()
        assertFalse(repository.acceptIntakeAction(intake.id, "taken"))
    }

    @Test fun caregiverAndCameraModesDoNotCreateSharedRemindersOrAutomaticActions() {
        link()
        val med = database.saveMedication(medication(protectedOwner, shared = true))
        val intake = database.ensureIntake(med, System.currentTimeMillis() - 3_600_000)
        MedicationReminderScheduler.synchronizeAll(context)
        assertEquals(1, database.intakes(protectedOwner).size)
        assertEquals("pending", database.getIntake(intake.id)?.status)
        assertNull(MedicationReminderScheduler.scheduleSlotAfter(context, med, "08:00", System.currentTimeMillis()))
        store.saveRole(AppStore.ROLE_CAMERA)
        assertFalse(repository.canManageOwner(actor))
        assertFalse(repository.canActOnIntake(intake.id))
    }

    @Test fun ownerOverdueUpdateDoesNotChangeSharedCache() {
        val at = System.currentTimeMillis() - 3_600_000
        val own = database.saveMedication(medication(actor))
        val shared = database.saveMedication(medication(protectedOwner, shared = true))
        val ownIntake = database.ensureIntake(own, at)
        val sharedIntake = database.ensureIntake(shared, at)
        database.markOverdue(actor)
        assertEquals("overdue", database.getIntake(ownIntake.id)?.status)
        assertEquals("pending", database.getIntake(sharedIntake.id)?.status)
        assertTrue(MedicationReminderScheduler.canRemind(context, actor))
        assertFalse(MedicationReminderScheduler.canRemind(context, protectedOwner))
    }

    @Test fun offlineOwnerActionRetainsItsAccountAttribution() {
        repository.currentOwnerScope()
        val med = database.saveMedication(medication(actor))
        val intake = database.ensureIntake(med, System.currentTimeMillis())
        EncryptedSessionStore(context).clear()
        assertTrue(repository.acceptIntakeAction(intake.id, "taken"))
        assertEquals(actor, database.getIntake(intake.id)?.actorUserId)
    }

    @Test fun anonymousActionIsAdoptedWithItsStableIdWhenOwnerSignsIn() {
        EncryptedSessionStore(context).clear()
        store.prefs.edit().remove("local_data_owner_id").commit()
        val localOwner = repository.localScope()
        val med = database.saveMedication(medication(localOwner))
        val intake = database.ensureIntake(med, System.currentTimeMillis())
        assertTrue(repository.acceptIntakeAction(intake.id, "taken"))
        assertNull(database.getIntake(intake.id)?.actorUserId)
        database.claimLocalData(localOwner, actor)
        val adopted = database.getIntake(intake.id)!!
        assertEquals(actor, adopted.ownerUserId)
        assertEquals(actor, adopted.actorUserId)
        assertEquals(intake.scheduledAtMillis, adopted.scheduledAtMillis)
        assertEquals(intake.scheduleInstanceId, adopted.scheduleInstanceId)
    }

    private fun link() {
        store.saveRole(AppStore.ROLE_CAREGIVER)
        store.prefs.edit().putString("caregiver_owner_id", protectedOwner).putString("caregiver_link_id", "audit-link").commit()
    }

    private fun medication(owner: String, shared: Boolean = false) = Medication(
        UUID.randomUUID().toString(), owner, "Prueba", "Indicada", "", "Diaria", listOf("08:00"),
        LocalDate.now().plusDays(30).toString(), null, "", true, shareWithCaregiver = shared,
    )

    private fun create(owner: String, existing: Medication? = null) = repository.createMedication(
        owner, "Prueba", "Indicada", "", "Diaria", listOf("08:00"),
        LocalDate.now().plusDays(30).toString(), null, "", existing = existing,
    )
}
