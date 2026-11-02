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

    @Test fun recordCannotBeReassignedToTheNewLinkedPerson() {
        link("a")
        val row = care.upsert("routine",JSONObject().put("name","A routine"))
        link("b")
        assertThrows(IllegalStateException::class.java) { care.upsert("routine",row) }
        assertThrows(IllegalStateException::class.java) { care.delete("routine",row.getString("id")) }
        assertEquals("owner-a",care.rowsAll().single().getString("ownerUserId"))
    }

    private fun identity(id:String,owner:String)=JSONObject().put("id",id).put("owner_user_id",owner)
    private fun link(id:String) {
        store.saveRole(AppStore.ROLE_CAREGIVER)
        assertTrue(CaregiverAccess(store).select(JSONObject().put("id","link-$id").put("owner_user_id","owner-$id"),actor))
    }
}
