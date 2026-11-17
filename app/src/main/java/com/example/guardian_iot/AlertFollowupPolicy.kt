package com.example.guardian_iot

import org.json.JSONObject

/** Pure decisions for displaying shared and local alert history. */
internal object AlertFollowupPolicy {
    fun isPendingAlert(row: JSONObject): Boolean =
        row.optString("confirmation_status") == "pending" ||
            row.optString("classification") in setOf("possible_fall", "fall") &&
            row.optString("confirmation_status").isBlank()

    /** Latest caregiver follow-up per fall event; a newer row overrides the event's own status. */
    fun latestByEvent(rows: List<JSONObject>): Map<String, JSONObject> {
        val latest = mutableMapOf<String, JSONObject>()
        rows.forEach { row ->
            val key = row.optString("fall_event_id")
            val current = latest[key]
            if (current == null || row.optString("created_at") > current.optString("created_at")) latest[key] = row
        }
        return latest
    }

    fun isPendingSharedAlert(event: JSONObject, followups: Map<String, JSONObject?>): Boolean {
        val followup = followups[event.optString("id")]
        return if (followup != null) !isResolvedFollowup(followup.optString("status")) else isPendingAlert(event)
    }

    private fun isResolvedFollowup(status: String): Boolean =
        status.trim().lowercase() in setOf("confirmed", "attended", "closed", "false_alarm", "dismissed", "cancelled", "canceled")
}
