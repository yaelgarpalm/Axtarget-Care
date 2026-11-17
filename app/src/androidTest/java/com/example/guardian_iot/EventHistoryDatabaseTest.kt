package com.example.guardian_iot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Checks that durable event history is paged from SQLite beyond the 100-item UI summary. */
@RunWith(AndroidJUnit4::class)
class EventHistoryDatabaseTest {
    @Test fun pagesAllEventsBeyondTheFormerPreferencesLimit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".audit"))
        val database = LocalTelemetryDatabase.getInstance(context)
        val marker = "history-${UUID.randomUUID()}"
        val future = System.currentTimeMillis() + 20L * 365 * 24 * 60 * 60 * 1000
        repeat(125) { index -> database.recordEvent("$marker-$index", "", future + index) }

        val firstPage = database.recentAppEvents(limit = 100, offset = 0)
        val secondPage = database.recentAppEvents(limit = 100, offset = 100)
        val rows = firstPage + secondPage
        val matching = rows.filter { it.optString("message").startsWith(marker) }

        assertEquals(100, firstPage.size)
        assertTrue(secondPage.size in 1..100)
        assertEquals(125, matching.size)
        assertEquals(125, matching.map { it.optString("message") }.distinct().size)
        assertTrue(database.appEventCount() >= 125)
    }

    @Test fun dailyFallCountDeduplicatesIncidentsWithoutLoadingTheFullHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = LocalTelemetryDatabase.getInstance(context)
        val marker = "fall-${UUID.randomUUID()}"
        val captured = System.currentTimeMillis() + 20L * 365 * 24 * 60 * 60 * 1000
        database.recordEvent("$marker posible caída", marker, captured)
        database.recordEvent("$marker Alerta cancelada tras posible caída", marker, captured + 1)
        database.recordEvent("$marker caída independiente", "", captured + 2)
        database.recordEvent("$marker medicamento guardado", "", captured + 3)

        assertEquals(2, database.possibleFallCountSince(captured))
    }
}
