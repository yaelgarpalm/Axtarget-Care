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
class ProfileIsolationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun setup() {
        check(context.packageName.endsWith(".audit"))
        context.getSharedPreferences("tzinda_native", 0).edit().clear().putBoolean("migration_done", true).commit()
    }
    @After fun clear() { context.getSharedPreferences("tzinda_native", 0).edit().clear().commit() }

    @Test fun cameraModeSurvivesRemoteWearerRoleAndAppStoreRecreation() {
        val store = AppStore(context)
        store.saveRole(AppStore.ROLE_CAMERA)
        store.applyRemoteProfile("Account A", AppStore.ROLE_WEARER, 100)
        val reopened = AppStore(context)
        assertEquals(AppStore.ROLE_CAMERA, reopened.role())
        assertEquals(AppStore.ROLE_WEARER, reopened.accountRole())
        assertEquals("Account A", reopened.profile().getString("fullName"))
        assertEquals(0, reopened.syncModules().getValue("Perfil").pending)
    }

    @Test fun nullRemoteRoleNeverClearsChosenDeviceMode() {
        val store = AppStore(context)
        store.saveRole(AppStore.ROLE_CAREGIVER)
        store.applyRemoteProfile("Account A", null, 100)
        assertEquals(AppStore.ROLE_CAREGIVER, store.role())
        assertEquals("", store.accountRole())
    }

    @Test fun remoteRoleDoesNotChooseModeOnNewPhone() {
        val store = AppStore(context)
        store.applyRemoteProfile("Account A", AppStore.ROLE_CAMERA, 100)
        assertEquals("", store.role())
        assertEquals(AppStore.ROLE_CAMERA, store.accountRole())
    }

    @Test fun oldUploadAndDownloadCannotAcknowledgeOrOverwriteNewLocalEdit() {
        val store = AppStore(context)
        store.saveProfile(JSONObject().put("fullName", "First").put("dataConsent", true))
        val sent = store.profileUpdatedAt()
        AppStore(context).saveProfile(JSONObject().put("fullName", "Second").put("dataConsent", true))
        store.markProfileSynced(sent)
        store.applyRemoteProfile("Old server value", AppStore.ROLE_WEARER, sent, sent)
        assertEquals("Second", store.profile().getString("fullName"))
        assertTrue(store.profileUpdatedAt() > store.lastProfileSyncAt())
    }
}
