package com.example.guardian_iot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProfileSyncPolicyTest {
    @Test fun localDirtyProfileWinsEvenIfRemoteClockIsAhead() {
        assertEquals(ProfileSyncPolicy.Action.PUSH, ProfileSyncPolicy.action("Local", 20, 10, true, 999))
    }
    @Test fun unchangedLocalProfileDownloadsNewRemoteName() {
        assertEquals(ProfileSyncPolicy.Action.PULL, ProfileSyncPolicy.action("Local", 20, 20, true, 30))
    }
    @Test fun missingRemoteProfileIsRecreatedButBlankLocalNameIsNotUploaded() {
        assertEquals(ProfileSyncPolicy.Action.PUSH, ProfileSyncPolicy.action("Local", 20, 20, false, 0))
        assertEquals(ProfileSyncPolicy.Action.NONE, ProfileSyncPolicy.action("", 20, 0, false, 0))
    }
    @Test fun unchangedProfileDoesNotWriteOnEveryDeviceModeChange() {
        assertEquals(ProfileSyncPolicy.Action.NONE, ProfileSyncPolicy.action("Local", 20, 20, true, 20))
    }
    @Test fun profileWritesNeverIncludeDeviceModeOrAccountRole() {
        for (user in listOf(null, "account-a")) {
            val preferences = JSONObject().put("existing_setting", true)
            val payload = ProfileSyncPolicy.payload("Person", preferences, user)
            assertFalse(payload.has("role"))
            assertFalse(payload.has("user_role"))
            assertTrue(payload.getJSONObject("preferences").getBoolean("existing_setting"))
            assertEquals(user != null, payload.has("user_id"))
        }
    }
}
