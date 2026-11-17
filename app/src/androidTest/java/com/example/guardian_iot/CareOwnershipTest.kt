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
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CareOwnershipTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val actor = "audit-care-actor"
    private lateinit var store: AppStore
    private lateinit var care: CareFeatureStore
    @Before fun prepare() {
        check(context.packageName.endsWith(".audit"))
        context.getSharedPreferences("tzinda_account_storage", 0).edit().clear().commit()
        context.getSharedPreferences("tzinda_native", 0).edit().clear().putBoolean("migration_done", true)
            .putString("local_data_owner_id", actor).commit()
        EncryptedSessionStore(context).write(AuthSession("fake-access", "fake-refresh", System.currentTimeMillis()/1000+7200,actor,"audit@example.invalid"))
        store = AppStore(context)
        store.saveRole(AppStore.ROLE_WEARER)
        care = CareFeatureStore(context)
        care.clearAll()
    }
    @After fun clean() {
        care.clearAll()
        EncryptedSessionStore(context).clear()
        store.prefs.edit().clear().putBoolean("migration_done", true).commit()
    }

    @Test fun caregiverDeletionRetainsOriginalOwnerAcrossAnotherLinkAndReopen() {
        link("a")
        val row = care.upsert("zone", JSONObject().put("name", "A zone"))
        assertEquals("owner-a", row.getString("ownerUserId"))
        assertEquals(actor, row.getString("actorUserId"))
        care.delete("zone", row.getString("id"))
        link("b")
        val reopened = CareFeatureStore(context)
        assertTrue(reopened.rows("zone").isEmpty())
        assertEquals("owner-a", reopened.pendingDeleteRecords().single().getString("ownerUserId"))
        assertEquals(1, reopened.syncState().pending)
    }

    @Test fun changingModeSeparatesOwnAndProtectedRecordsAndStopsCaregiverGeofencing() {
        care.upsert("journal", JSONObject().put("description", "Own note"))
        link("a")
        care.upsert("journal", JSONObject().put("description", "Protected note"))
        care.upsert("zone", JSONObject().put("name", "A zone").put("latitude",19.0).put("longitude",-99.0).put("radius",150))
        assertEquals("Protected note", care.rows("journal").single().getString("description"))
        assertTrue(care.onLocation(19.0,-99.0,5f,System.currentTimeMillis()).isEmpty())
        assertFalse(care.zones().single().has("lastLocationAt"))
        store.saveRole(AppStore.ROLE_WEARER)
        assertEquals("Own note", care.rows("journal").single().getString("description"))
        assertTrue(care.zones().isEmpty())
        assertEquals(3, care.rowsAll().size)
    }

    @Test fun oldDeleteAcknowledgementCannotClearADeleteAfterRestore() {
        val row = care.upsert("zone", JSONObject().put("name", "Own zone"))
        val id = row.getString("id")
        care.delete("zone", id)
        val oldDelete = care.pendingDeleteRecords().single()
        care.upsert("zone", row)
        care.delete("zone", id)
        val newDelete = care.pendingDeleteRecords().single()
        assertTrue(newDelete.getLong("deletedAt") > oldDelete.getLong("deletedAt"))
        care.markDeletedSynced(id, actor, oldDelete.getLong("deletedAt"))
        assertEquals(1, care.pendingDeleteRecords().size)
        care.markDeletedSynced(id, actor, newDelete.getLong("deletedAt"))
        assertTrue(care.pendingDeleteRecords().isEmpty())
    }

    @Test fun completeOwnerSnapshotConfirmsOnlyDeletesAlreadyAbsentRemotely() {
        val absent = care.upsert("zone", JSONObject().put("name", "Ya eliminado en servidor"))
        care.delete("zone", absent.getString("id"))
        val present = care.upsert("zone", JSONObject().put("name", "Aún remoto"))
        care.delete("zone", present.getString("id"))

        val remote = listOf(remoteCareRow(present.getString("id"), "zone", actor,
            System.currentTimeMillis(), JSONObject().put("name", "Aún remoto")))
        assertEquals(1, care.acknowledgeDeletesAbsentFromSnapshot(remote, actor))
        assertEquals(listOf(present.getString("id")), care.pendingDeletes())
    }

    @Test fun anonymousCareAdoptionKeepsIdsAndDeletionOwnership() {
        store.prefs.edit().remove("local_data_owner_id").commit()
        val row = care.upsert("journal", JSONObject().put("description", "Local note"))
        val removed = care.upsert("zone", JSONObject().put("name", "Local zone"))
        care.delete("zone", removed.getString("id"))
        store.prefs.edit().putString("local_data_owner_id", actor).commit()
        care.claimLocalData(actor)
        val adopted = care.rows("journal").single()
        assertEquals(row.getString("id"), adopted.getString("id"))
        assertEquals(row.getLong("updatedAt"), adopted.getLong("updatedAt"))
        assertEquals(actor, adopted.getString("actorUserId"))
        assertEquals(actor, care.pendingDeleteRecords().single().getString("ownerUserId"))
    }

    @Test fun unknownLegacyOwnersAreNotGuessedAndOnlyValidatedIdsReconcile() {
        val id = UUID.randomUUID().toString()
        val deletedId = UUID.randomUUID().toString()
        context.getSharedPreferences("tzinda_care_features",0).edit()
            .putString("records",JSONArray().put(JSONObject().put("id",id).put("kind","routine").put("syncState","pending").put("updatedAt",42)).toString())
            .putString("deleted",JSONArray().put(deletedId).toString()).commit()
        assertEquals(2,care.unassignedCount())
        assertEquals(2,care.syncState().failed)
        assertTrue(care.rows("routine").isEmpty())
        assertThrows(IllegalStateException::class.java) { care.reconcileOwners(listOf(identity(id,"different-owner")),actor) }
        assertEquals(2,care.unassignedCount())
        care.reconcileOwners(listOf(identity(id,actor),identity(deletedId,actor)),actor)
        assertEquals(0,care.unassignedCount())
        assertEquals(42,care.rows("routine").single().getLong("updatedAt"))
        assertEquals(actor,care.pendingDeleteRecords().single().getString("ownerUserId"))
    }

    @Test fun legacyRecordsRequireExplicitOwnerClaimAndKeepTheirIds() {
        val recordId = UUID.randomUUID().toString()
        val deletionId = UUID.randomUUID().toString()
        context.getSharedPreferences("tzinda_care_features",0).edit()
            .putString("records",JSONArray().put(JSONObject().put("id",recordId).put("kind","journal")
                .put("description","Nota antigua").put("updatedAt",42).put("syncState","pending")).toString())
            .putString("deleted",JSONArray().put(deletionId).toString()).commit()

        assertEquals(2, care.unassignedCount())
        assertTrue(care.claimUnassignedEntry(care.unassignedEntries().single { !it.deletion }, actor, actor))
        val claimed = care.rowsAll().single()
        assertEquals(recordId, claimed.getString("id"))
        assertEquals(actor, claimed.getString("ownerUserId"))
        assertEquals(actor, claimed.getString("actorUserId"))
        assertEquals("pending", claimed.getString("syncState"))

        val deletion = care.unassignedEntries().single { it.deletion }
        assertTrue(care.claimUnassignedEntry(deletion, actor, actor))
        assertEquals(deletionId, care.pendingDeleteRecords().single().getString("id"))
        assertEquals(actor, care.pendingDeleteRecords().single().getString("ownerUserId"))
        assertEquals(0, care.unassignedCount())
    }

    @Test fun caregiverCannotClaimAmbiguousRecordsForTheLinkedOwner() {
        val id = UUID.randomUUID().toString()
        context.getSharedPreferences("tzinda_care_features",0).edit()
            .putString("records",JSONArray().put(JSONObject().put("id",id).put("kind","journal")).toString()).commit()
        link("a")
        val entry = care.unassignedEntries().single()
        assertThrows(IllegalStateException::class.java) { care.claimUnassignedEntry(entry, "owner-a", actor) }
        assertEquals(1, care.unassignedCount())
    }

    @Test fun remoteSnapshotRestoresMissingRowsAndDoesNotClobberPendingLocalWork() {
        val synced = care.upsert("journal", JSONObject().put("description", "Versión anterior"))
        care.markSynced(synced.getString("id"), synced.getLong("updatedAt"))
        val pending = care.upsert("routine", JSONObject().put("name", "Edición sin conexión"))
        val localOnly = care.upsert("zone", JSONObject().put("name", "Zona solo local"))
        care.markSynced(localOnly.getString("id"), localOnly.getLong("updatedAt"))
        val newerRemoteAt = System.currentTimeMillis() + 60_000
        val pendingRemoteAt = newerRemoteAt + 1
        val remoteOnlyId = UUID.randomUUID().toString()
        val remote = listOf(
            remoteCareRow(synced.getString("id"), "journal", actor, newerRemoteAt,
                JSONObject().put("description", "Versión remota más nueva")),
            remoteCareRow(pending.getString("id"), "routine", actor, pendingRemoteAt,
                JSONObject().put("name", "Versión remota antigua")),
            remoteCareRow(remoteOnlyId, "journal", actor, newerRemoteAt + 1,
                JSONObject().put("description", "Recuperado al reinstalar")),
        )

        care.mergeRemoteSnapshot(remote, actor)
        val rows = care.rowsAll().associateBy { it.optString("id") }
        assertEquals("Versión remota más nueva", rows.getValue(synced.getString("id")).getString("description"))
        assertEquals("synced", rows.getValue(synced.getString("id")).getString("syncState"))
        assertEquals("Edición sin conexión", rows.getValue(pending.getString("id")).getString("name"))
        assertEquals("pending", rows.getValue(pending.getString("id")).getString("syncState"))
        assertEquals("Zona solo local", rows.getValue(localOnly.getString("id")).getString("name"))
        assertEquals("Recuperado al reinstalar", rows.getValue(remoteOnlyId).getString("description"))
        assertEquals(actor, rows.getValue(remoteOnlyId).getString("ownerUserId"))
    }

    @Test fun invalidRemoteSnapshotIsRejectedWithoutPartialImport() {
        val valid = remoteCareRow(UUID.randomUUID().toString(), "journal", actor, System.currentTimeMillis(),
            JSONObject().put("description", "No debe importarse parcialmente"))
        val foreign = remoteCareRow(UUID.randomUUID().toString(), "journal", "other-owner", System.currentTimeMillis(),
            JSONObject().put("description", "Otra cuenta"))
        assertThrows(IllegalStateException::class.java) { care.mergeRemoteSnapshot(listOf(valid, foreign), actor) }
        assertTrue(care.rowsAll().isEmpty())
    }

    @Test fun remoteAlertFollowupsRestoreHistoryAndPreservePendingLocalActions() {
        val pendingId = UUID.randomUUID().toString()
        val localEvent = UUID.randomUUID().toString()
        care.upsert("alert_followup", JSONObject().put("id", pendingId).put("fallEventId", localEvent)
            .put("status", "attended").put("reason", "Acción sin conexión").put("occurredAt", System.currentTimeMillis()))
        val restoredId = UUID.randomUUID().toString()
        val remoteAt = System.currentTimeMillis() + 10_000
        care.mergeRemoteFollowups(listOf(
            remoteFollowup(pendingId, actor, UUID.randomUUID().toString(), remoteAt, "closed", "No debe reemplazar la edición"),
            remoteFollowup(restoredId, actor, UUID.randomUUID().toString(), remoteAt + 1, "dismissed", "Falsa alarma"),
        ), actor)

        val rows = care.rowsAll().associateBy { it.optString("id") }
        assertEquals("Acción sin conexión", rows.getValue(pendingId).getString("reason"))
        assertEquals("pending", rows.getValue(pendingId).getString("syncState"))
        assertEquals("alert_followup", rows.getValue(restoredId).getString("kind"))
        assertEquals("dismissed", rows.getValue(restoredId).getString("status"))
        assertEquals("Falsa alarma", rows.getValue(restoredId).getString("reason"))
        assertEquals("synced", rows.getValue(restoredId).getString("syncState"))
        assertEquals(actor, rows.getValue(restoredId).getString("ownerUserId"))
    }

    @Test fun foreignRemoteFollowupIsRejectedWithoutPartialImport() {
        val valid = remoteFollowup(UUID.randomUUID().toString(), actor, UUID.randomUUID().toString(),
            System.currentTimeMillis(), "confirmed", "")
        val foreign = remoteFollowup(UUID.randomUUID().toString(), "other-owner", UUID.randomUUID().toString(),
            System.currentTimeMillis(), "attended", "")
        assertThrows(IllegalStateException::class.java) { care.mergeRemoteFollowups(listOf(valid, foreign), actor) }
        assertTrue(care.rowsAll().isEmpty())
    }

    @Test fun recordCannotBeReassignedToTheNewLinkedPerson() {
        link("a")
        val row = care.upsert("routine",JSONObject().put("name","A routine"))
        link("b")
        assertThrows(IllegalStateException::class.java) { care.upsert("routine",row) }
        assertThrows(IllegalStateException::class.java) { care.delete("routine",row.getString("id")) }
        assertEquals("owner-a",care.rowsAll().single().getString("ownerUserId"))
    }

    private fun identity(id:String,owner:String)=JSONObject().put("id",id).put("owner_user_id",owner)
    private fun remoteCareRow(id: String, kind: String, owner: String, updatedAt: Long, fields: JSONObject) =
        JSONObject().put("id", id).put("owner_user_id", owner).put("record_kind", kind)
            .put("record", JSONObject(fields.toString()).put("id", id).put("kind", kind)
                .put("ownerUserId", owner).put("updatedAt", updatedAt).put("actorUserId", owner))
            .put("updated_at", java.time.Instant.ofEpochMilli(updatedAt).toString())

    private fun remoteFollowup(id: String, owner: String, event: String, createdAt: Long, status: String, reason: String) =
        JSONObject().put("id", id).put("owner_user_id", owner).put("fall_event_id", event)
            .put("actor_user_id", UUID.randomUUID().toString()).put("status", status).put("reason", reason)
            .put("created_at", java.time.Instant.ofEpochMilli(createdAt).toString())

    private fun link(id:String) {
        store.saveRole(AppStore.ROLE_CAREGIVER)
        assertTrue(CaregiverAccess(store).select(JSONObject().put("id","link-$id").put("owner_user_id","owner-$id"),actor))
    }
}
