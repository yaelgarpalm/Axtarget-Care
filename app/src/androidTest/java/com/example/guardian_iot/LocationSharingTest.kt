package com.example.guardian_iot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocationSharingTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: AppStore
    private lateinit var sharing: LocationSharing
    private val calls = mutableListOf<String>()
    private val owner = "audit-location-owner"
    private val epoch get() = store.prefs.getLong("location_consent_epoch", 0)

    @Before fun prepare() {
        check(context.packageName.endsWith(".audit"))
        context.getSharedPreferences("tzinda_account_storage", 0).edit().clear().commit()
        context.getSharedPreferences("tzinda_native", 0).edit().clear().putBoolean("migration_done", true)
            .putString("local_data_owner_id", owner).commit()
        store = AppStore(context)
        store.telemetry.clearAllData()
        store.saveRole(AppStore.ROLE_WEARER)
        sharing = LocationSharing(store)
    }
    @After fun clean() {
        AccountStorage.activate(context, owner)
        store.telemetry.clearAllData()
        store.prefs.edit().clear().putBoolean("migration_done", true).commit()
    }

    @Test fun localZonesDoNotCreateAnUploadQueue() {
        sharing.record(19.0, -99.0, System.currentTimeMillis(), 10f)
        assertEquals(1, store.telemetry.summary().locations)
        assertTrue(store.telemetry.pendingLocations().isEmpty())
        assertTrue(flush())
        assertTrue(calls.isEmpty())
    }

    @Test fun consentOnlyPublishesFixesFromItsCurrentPeriod() {
        sharing.setConsent(true)
        sharing.record(19.0, -99.0, epoch - 1, 10f)
        sharing.record(19.1, -99.1, epoch + 1, 10f)
        assertEquals(2, store.telemetry.summary().locations)
        assertEquals(1, store.telemetry.pendingLocations().size)
        assertTrue(flush())
        assertEquals(listOf("history", "live"), calls)
        assertTrue(store.telemetry.pendingLocations().isEmpty())
    }

    @Test fun revokeSurvivesReopenAndRetryWithoutDeletingLocalReadings() {
        sharing.setConsent(true)
        sharing.record(19.0, -99.0, epoch + 1, 10f)
        val id = store.telemetry.pendingLocations().single().id
        sharing.setConsent(false)
        store.telemetry.markLocationFailure(id) // late network failure cannot revive a withheld row
        store.telemetry.markLocationSynced(id) // nor can a late acknowledgement
        store.telemetry.retryFailedSyncItems()
        sharing = LocationSharing(AppStore(context))
        assertTrue(flush())
        assertEquals(listOf("clear"), calls)
        assertNull(store.telemetry.latestLocation())
        assertEquals(1, store.telemetry.summary().locations)
    }

    @Test fun enablingAgainDoesNotReactivateThePreviousTrail() {
        sharing.setConsent(true)
        sharing.record(19.0, -99.0, epoch + 1, 10f)
        sharing.setConsent(false)
        sharing.setConsent(true)
        assertTrue(flush())
        assertEquals(listOf("clear"), calls)
        calls.clear()
        sharing.record(19.1, -99.1, epoch + 1, 10f)
        assertTrue(flush())
        assertEquals(listOf("history", "live"), calls)
        assertEquals(2, store.telemetry.summary().locations)
    }

    @Test fun revokeDuringHistoryStopsRemainingUploadsAndLivePublication() {
        sharing.setConsent(true)
        sharing.record(19.0, -99.0, epoch + 1, 10f)
        sharing.record(19.1, -99.1, epoch + 31_000, 10f)
        assertTrue(sharing.flush(
            uploadHistory = { calls.add("history"); sharing.setConsent(false) },
            uploadLive = { calls.add("live") }, clearRemote = { calls.add("clear") },
        ))
        assertEquals(listOf("history", "clear"), calls)
        assertTrue(store.telemetry.pendingLocations().isEmpty())
        assertEquals(2, store.telemetry.summary().locations)
    }

    @Test fun revokeDuringLiveUploadClearsAfterwardAndRetainsFailedCleanup() {
        sharing.setConsent(true)
        sharing.record(19.0, -99.0, epoch + 1, 10f)
        assertFalse(sharing.flush(
            uploadHistory = { calls.add("history") },
            uploadLive = { calls.add("live"); sharing.setConsent(false) },
            clearRemote = { calls.add("clear-failed"); throw java.io.IOException("offline") },
        ))
        assertEquals(listOf("history", "live", "clear-failed"), calls)
        assertEquals(1, LocationSharing.pendingClearCount(store))
        calls.clear()
        assertTrue(flush())
        assertEquals(listOf("clear"), calls)
        assertEquals(0, LocationSharing.pendingClearCount(store))
    }

    @Test fun modeChangeStopsPublishingWearerLocation() {
        sharing.setConsent(true)
        sharing.record(19.0, -99.0, epoch + 1, 10f)
        store.saveRole(AppStore.ROLE_CAMERA)
        assertTrue(flush())
        assertEquals(listOf("clear"), calls)
        assertTrue(store.telemetry.pendingLocations().isEmpty())
    }

    @Test fun legacyQueueWithoutConsentProvenanceIsRetainedLocallyOnly() {
        store.prefs.edit().remove("location_policy_initialized").putBoolean("include_location", true).commit()
        store.telemetry.recordLocation(19.0, -99.0, System.currentTimeMillis() - 60_000, 10f, shareForSync = true)
        assertTrue(flush())
        assertEquals(listOf("clear"), calls)
        assertEquals(1, store.telemetry.summary().locations)
        assertTrue(store.telemetry.pendingLocations().isEmpty())
    }

    @Test fun oldAccountCannotPublishItsQueueAfterAccountSwitch() {
        sharing.setConsent(true)
        sharing.record(19.0, -99.0, epoch + 1, 10f)
        sharing.requestLiveClear()
        AccountStorage.activate(context, "audit-location-other-${UUID.randomUUID()}")
        assertTrue(flush())
        assertTrue(calls.isEmpty())
        assertEquals(1, store.telemetry.pendingLocations().size)
        assertEquals(1, LocationSharing.pendingClearCount(store))
        assertTrue(AppStore(context).telemetry.pendingLocations().isEmpty())
    }

    @Test fun stoppedMonitoringCanSyncConsentedHistoryWithoutShowingLivePosition() {
        sharing.setConsent(true)
        sharing.record(19.0, -99.0, epoch + 1, 10f)
        assertTrue(sharing.flush({ calls.add("history") }, { calls.add("live") }, { calls.add("clear") }, { false }))
        assertEquals(listOf("history"), calls)
    }

    private fun flush() = sharing.flush({ calls.add("history") }, { calls.add("live") }, { calls.add("clear") })
}
