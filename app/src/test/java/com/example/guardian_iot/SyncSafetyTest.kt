package com.example.guardian_iot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SyncSafetyTest {
    @Test fun globalStateIncludesNonTelemetryFailuresAndPendingDeletes() {
        val total = SyncOverview.total(listOf(SyncStateSummary(0, 0), SyncStateSummary(2, 1), SyncStateSummary(1, 0)))
        assertEquals(3, total.pending)
        assertEquals(1, total.failed)
        assertEquals("Sincronización con error", TzindaStatusCopy.sync(total.pending, total.failed, true, true).title)
    }
    @Test fun secondCollectionFailureNeverAppliesOrPrunesFirstCollection() {
        for (status in listOf(401, 403, 404, 409, 500, 0)) {
            var applied = false
            assertThrows(IllegalStateException::class.java) {
                RemoteSnapshot.download(
                    medications = { listOf("cached") },
                    intakes = { error("HTTP $status or timeout") },
                    apply = { _: List<String>, _: List<String> -> applied = true },
                )
            }
            assertFalse(applied)
        }
    }

    @Test fun malformedRowRejectsEntireCollection() {
        val rows = JSONArray().put(JSONObject().put("id", "valid")).put(JSONObject())
        assertThrows(IllegalStateException::class.java) {
            RemoteSnapshot.parse(rows) { it.optString("id").takeIf(String::isNotBlank) }
        }
    }

    @Test fun cappedPagesContinueUntilExplicitEmptyPage() {
        val cursors = mutableListOf<String?>()
        val rows = RemoteSnapshot.readAll { cursor ->
            cursors += cursor
            when (cursor) {
                null -> JSONArray().put(JSONObject().put("id", "a"))
                "a" -> JSONArray().put(JSONObject().put("id", "b"))
                else -> JSONArray()
            }
        }
        assertEquals(listOf(null, "a", "b"), cursors)
        assertEquals(2, rows.length())
    }

    @Test fun repeatedPageCannotMasqueradeAsCompleteSnapshot() {
        assertThrows(IllegalStateException::class.java) {
            RemoteSnapshot.readAll { JSONArray().put(JSONObject().put("id", "a")) }
        }
    }

    @Test fun validEmptySnapshotIsApplied() {
        var applied = false
        RemoteSnapshot.download({ emptyList<String>() }, { emptyList<String>() }) { a, b ->
            assertTrue(a.isEmpty() && b.isEmpty()); applied = true
        }
        assertTrue(applied)
    }

    @Test fun deviceFailureCannotConfirmRegistrationAndRetryCanRecover() {
        val states = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            DeviceRegistration.confirm({ states += it }) { error("network failure") }
        }
        assertEquals(listOf("pending", "error"), states)
        DeviceRegistration.confirm({ states += it }) { "remote-device-id" }
        assertEquals(listOf("pending", "error", "pending", "confirmed"), states)
    }
}
