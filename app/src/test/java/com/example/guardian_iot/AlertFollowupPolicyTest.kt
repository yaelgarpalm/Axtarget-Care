package com.example.guardian_iot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertFollowupPolicyTest {
    @Test fun newestFollowupWinsPerEventRegardlessOfInputOrder() {
        val older = followup("fall-a", "2026-11-01T10:00:00Z", "confirmed")
        val newer = followup("fall-a", "2026-11-01T11:00:00Z", "attended")
        val other = followup("fall-b", "2026-11-01T09:00:00Z", "closed")

        val latest = AlertFollowupPolicy.latestByEvent(listOf(newer, other, older))

        assertEquals("attended", latest.getValue("fall-a").getString("status"))
        assertEquals("closed", latest.getValue("fall-b").getString("status"))
    }

    @Test fun resolvedFollowupOverridesPendingEventAndUnknownStatusStaysPending() {
        val event = JSONObject().put("id", "fall-a").put("classification", "fall")
            .put("confirmation_status", "pending")

        assertFalse(AlertFollowupPolicy.isPendingSharedAlert(event,
            mapOf("fall-a" to followup("fall-a", "2026-11-01T11:00:00Z", "dismissed"))))
        assertTrue(AlertFollowupPolicy.isPendingSharedAlert(event,
            mapOf("fall-a" to followup("fall-a", "2026-11-01T11:00:00Z", "reviewing"))))
    }

    @Test fun onlyFallCandidatesWithoutConfirmationDefaultToPending() {
        assertTrue(AlertFollowupPolicy.isPendingAlert(JSONObject().put("classification", "possible_fall")))
        assertTrue(AlertFollowupPolicy.isPendingAlert(JSONObject().put("confirmation_status", "pending")))
        assertFalse(AlertFollowupPolicy.isPendingAlert(JSONObject().put("classification", "normal")))
        assertFalse(AlertFollowupPolicy.isPendingAlert(JSONObject().put("classification", "fall").put("confirmation_status", "confirmed")))
    }

    private fun followup(eventId: String, createdAt: String, status: String) =
        JSONObject().put("fall_event_id", eventId).put("created_at", createdAt).put("status", status)
}
