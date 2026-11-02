package com.example.guardian_iot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CaregiverAccessTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val actor = "audit-caregiver-access"
    private lateinit var store: AppStore
    private lateinit var access: CaregiverAccess

    @Before fun prepare() {
        check(context.packageName.endsWith(".audit"))
        context.getSharedPreferences("tzinda_account_storage", 0).edit().clear().commit()
        context.getSharedPreferences("tzinda_native", 0).edit().clear().putBoolean("migration_done", true)
            .putString("local_data_owner_id", actor).commit()
        EncryptedSessionStore(context).write(session(actor))
        store = AppStore(context)
        store.saveRole(AppStore.ROLE_CAREGIVER)
        access = CaregiverAccess(store)
        CareFeatureStore(context).clearAll()
    }
    @After fun clean() {
        AccountStorage.activate(context, actor)
        EncryptedSessionStore(context).clear()
        store.prefs.edit().clear().putBoolean("migration_done", true).commit()
        CareFeatureStore(context).clearAll()
    }

    @Test fun selectingAnotherPersonClearsDerivedCachesAndRejectsOldResponse() {
        store.saveProfile(JSONObject().put("fullName", "Caregiver profile").put("dataConsent", true))
        CareFeatureStore(context).upsert("journal", JSONObject().put("description", "Pending local note"))
        assertTrue(access.select(link("a"), actor))
        val before = access.snapshot()!!
        assertTrue(access.write(before) {
            putString("caregiver_shared_name", "Person A")
            putString("caregiver_shared_events", "[\"private-a\"]")
            putString("caregiver_shared_care_records", "[\"private-care-a\"]")
            putString("caregiver_shared_location", "private-location-a")
        })
        assertTrue(access.select(link("b"), actor))
        assertFalse(store.prefs.contains("caregiver_shared_name"))
        assertFalse(store.prefs.contains("caregiver_shared_events"))
        assertFalse(store.prefs.contains("caregiver_shared_care_records"))
        assertFalse(store.prefs.contains("caregiver_shared_location"))
        assertFalse(access.write(before) { putString("caregiver_shared_name", "Late A") })
        assertFalse(access.clear(before))
        assertEquals("owner-b", access.snapshot()!!.owner)
        assertEquals("Caregiver profile", store.profile().getString("fullName"))
        assertEquals(1, CareFeatureStore(context).rowsAll().size)
    }

    @Test fun unlinkAndRelinkSamePairInvalidatesPriorSubscriptionGeneration() {
        access.select(link("a"), actor)
        val before = access.snapshot()!!
        assertTrue(access.clear(before))
        access.select(link("a"), actor)
        assertNotEquals(before.generation, access.snapshot()!!.generation)
        assertFalse(access.write(before) { putString("caregiver_shared_measurements", "old-channel") })
        assertTrue(access.write(access.snapshot()!!) { putString("caregiver_shared_measurements", "current-channel") })
        assertEquals("current-channel", store.prefs.getString("caregiver_shared_measurements", ""))
    }

    @Test fun sessionReplacementBlocksOldActorWithoutTouchingNewAccount() {
        access.select(link("a"), actor)
        val before = access.snapshot()!!
        EncryptedSessionStore(context).write(session("another-actor"))
        assertFalse(access.write(before) { putString("caregiver_shared_name", "Late A") })
        assertFalse(access.select(link("b"), actor))
        assertNull(access.snapshot())
    }

    @Test fun accountSwitchRejectsOldCacheWriteAndRevocation() {
        access.select(link("a"), actor)
        val before = access.snapshot()!!
        AccountStorage.activate(context, "audit-caregiver-other-${UUID.randomUUID()}")
        assertFalse(access.write(before) { putString("caregiver_shared_name", "Late A") })
        assertFalse(access.clear(before))
        assertFalse(AppStore(context).prefs.contains("caregiver_shared_name"))
    }

    @Test fun temporaryModeChangeInvalidatesPreviousCallbacks() {
        access.select(link("a"), actor)
        val before = access.snapshot()!!
        store.saveRole(AppStore.ROLE_CAMERA)
        store.saveRole(AppStore.ROLE_CAREGIVER)
        assertEquals(before.link, access.snapshot()!!.link)
        assertFalse(access.write(before) { putString("caregiver_shared_name", "Late A") })
    }

    @Test fun legacyCacheWithoutOwnershipMarkerIsInvalidatedOnceAndKeepsSelection() {
        access.select(link("a"), actor)
        store.prefs.edit().remove("caregiver_cache_schema").putString("caregiver_shared_name", "Unknown provenance").commit()
        val reopened = AppStore(context)
        assertFalse(reopened.prefs.contains("caregiver_shared_name"))
        val current = CaregiverAccess(reopened).snapshot()!!
        assertEquals("link-a", current.link)
        CaregiverAccess(reopened).write(current) { putString("caregiver_shared_name", "Verified A") }
        assertEquals("Verified A", AppStore(context).prefs.getString("caregiver_shared_name", ""))
    }

    @Test fun remoteLinkValidationRequiresExactOwnerDeviceAndActiveStatus() {
        access.select(link("a").put("device_id", "device-a"), actor)
        val scope = access.snapshot()!!
        assertTrue(scope.matches(link("a").put("device_id", "device-a")))
        assertFalse(scope.matches(link("a").put("owner_user_id", "owner-b").put("device_id", "device-a")))
        assertFalse(scope.matches(link("a").put("device_id", "device-b")))
        assertFalse(scope.matches(link("a").put("status", "revoked").put("device_id", "device-a")))
        assertFalse(access.select(link("b").put("status", "revoked"), actor))
        assertEquals(scope, access.snapshot())
    }

    private fun link(id: String) = JSONObject().put("id", "link-$id").put("owner_user_id", "owner-$id")
        .put("device_id", JSONObject.NULL).put("status", "active")
    private fun session(id: String) = AuthSession("fake-access", "fake-refresh", System.currentTimeMillis() / 1000 + 7200,
        id, "$id@example.invalid")
}
