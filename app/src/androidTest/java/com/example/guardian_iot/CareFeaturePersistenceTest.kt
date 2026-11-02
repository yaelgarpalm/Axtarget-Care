package com.example.guardian_iot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CareFeaturePersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun prepare() {
        check(context.packageName.endsWith(".audit")) { "Requires isolated audit build" }
        context.getSharedPreferences("tzinda_account_storage", 0).edit().clear().commit()
        context.getSharedPreferences("tzinda_native", 0).edit().clear().putBoolean("migration_done", true)
            .putString("user_role", AppStore.ROLE_WEARER).putString("local_data_owner_id", "audit-care-owner").commit()
        CareFeatureStore(context).clearAll()
    }
    @After fun clear() {
        CareFeatureStore(context).clearAll()
        context.getSharedPreferences("tzinda_native", 0).edit().clear().putBoolean("migration_done", true).commit()
    }

    @Test fun lateAcknowledgementDoesNotEraseNewEditFromAnotherStore() {
        val first = CareFeatureStore(context)
        val second = CareFeatureStore(context)
        val original = first.upsert("journal", JSONObject().put("description", "initial"))
        val updated = second.upsert("journal", JSONObject(original.toString()).put("description", "edited"))
        first.markSynced(original.getString("id"), original.getLong("updatedAt"))
        assertEquals(1, second.syncState().pending)
        assertEquals("edited", first.rows("journal").single().getString("description"))
        first.markSynced(updated.getString("id"), updated.getLong("updatedAt"))
        assertEquals(0, second.syncState().pending)
    }

    @Test fun deleteRemainsPendingAfterStoreReopen() {
        val first = CareFeatureStore(context)
        val row = first.upsert("routine", JSONObject().put("name", "test"))
        first.markSynced(row.getString("id"), row.getLong("updatedAt"))
        first.delete("routine", row.getString("id"))
        val reopened = CareFeatureStore(context)
        assertEquals(1, reopened.syncState().pending)
        assertEquals(listOf(row.getString("id")), reopened.pendingDeletes())
    }

    @Test fun locationInitializesRuntimeWithoutDirtyingZoneConfiguration() {
        val first = CareFeatureStore(context)
        val zone = first.upsert("zone", JSONObject().put("name", "test zone")
            .put("latitude", 19.0).put("longitude", -99.0).put("radius", 150.0))
        first.markSynced(zone.getString("id"), zone.getLong("updatedAt"))
        val now = System.currentTimeMillis()
        assertTrue(first.onLocation(19.0, -99.0, 5f, now).isEmpty())
        val reopened = CareFeatureStore(context)
        assertEquals(0, reopened.syncState().pending)
        assertTrue(reopened.zones().single().getBoolean("lastInside"))
        assertEquals(now, reopened.zones().single().getLong("lastLocationAt"))
        assertFalse(reopened.rowsAll().single().has("lastLocationAt"))
        assertEquals(zone.getLong("updatedAt"), reopened.rowsAll().single().getLong("updatedAt"))
    }
}
