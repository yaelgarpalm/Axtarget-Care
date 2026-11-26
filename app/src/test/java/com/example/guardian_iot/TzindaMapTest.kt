package com.example.guardian_iot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TzindaMapTest {
    @Test
    fun locationExpiresAndRejectsInvalidCoordinates() {
        val point = requireNotNull(MapPoint.parse("19.43260, -99.13320"))
        val now = 1_000_000L
        assertTrue(MapFix(point, now - 299_999).isRecent(now))
        assertFalse(MapFix(point, now - 300_000).isRecent(now))
        assertFalse(MapFix(point, now + 30_001).isRecent(now))
        assertNull(MapPoint.parse("91, -99"))
        assertNull(MapPoint.parse("NaN, 0"))
        assertNull(MapPoint.parse("0, -181"))
    }

    @Test
    fun aLateOrMuchLessAccurateFixDoesNotImmediatelyMoveTheMap() {
        val now = 1_000_000L
        val point = MapPoint(19.4326, -99.1332)
        val previous = MapFix(point, now - 2_000, 12.0)
        assertFalse(previous.copy(capturedAt = now - 3_000, accuracyM = 5.0).isPreferredTo(previous, now))
        assertFalse(previous.copy(capturedAt = now, accuracyM = 900.0).isPreferredTo(previous, now))
        assertTrue(previous.copy(capturedAt = now, accuracyM = 10.0).isPreferredTo(previous, now))
        assertTrue(previous.copy(capturedAt = now, accuracyM = 900.0).isPreferredTo(
            previous.copy(capturedAt = now - 20_000), now,
        ))
    }

    @Test
    fun safeZoneStatusDistinguishesARecentUpdateFromACachedPosition() {
        val now = 1_000_000L
        val position = MapFix(MapPoint(19.4326, -99.1332), now - 120_000, 8.0)
        assertEquals("Última ubicación · Hace 2 min", position.zoneStatus(now))
        assertEquals("Ubicación actualizada", position.copy(capturedAt = now - 3_000).zoneStatus(now))
    }

    @Test
    fun mapProjectionWrapsAtTheAntimeridian() {
        val original = MapPoint(19.4326, 179.95)
        val projected = MapProjection.unproject(MapProjection.x(original) - 1, MapProjection.y(original))
        assertEquals(original.latitude, projected.latitude, 1e-6)
        assertEquals(original.longitude, projected.longitude, 1e-6)
        assertEquals(0.02, MapProjection.wrappedDelta(0.01, 0.99), 1e-6)
    }

    @Test
    fun editingLegacyZonesPreservesTheirIdentityAndSupportedRadius() {
        val legacy = JSONObject()
            .put("id", "original-id")
            .put("name", "Casa")
            .put("latitude", 19.4326)
            .put("longitude", -99.1332)
            .put("radius", 17_500)
            .put("enabled", false)
            .put("sharedWithCaregiver", true)
        val original = MapZone.from(legacy)
        assertNotNull(original)
        val updated = requireNotNull(original).copy(name = "Mi casa").record("owner")
        assertEquals("original-id", updated.optString("id"))
        assertEquals("owner", updated.optString("ownerUserId"))
        assertEquals(17_500, updated.optInt("radius"))
        assertFalse(updated.optBoolean("enabled"))
        assertTrue(updated.optBoolean("sharedWithCaregiver"))
    }

    @Test
    fun routeGeometryReadsLongitudeFirstAndRequiresADrawablePath() {
        val result = TzindaMapService.parseRoutes(
            """
            { "code":"Ok", "routes":[
                { "distance":4400,"duration":540,
                  "geometry":{"coordinates":[[-99.1,19.4],[-99.12,19.42]]},
                  "legs":[{"steps":[
                      {"name":"Avenida Central", "maneuver":{"type":"turn","modifier":"right"}},
                      {"maneuver":{"type":"arrive"}}
                  ]}] },
                { "distance":900,"duration":45, "geometry":{"coordinates":[[-99.1,19.4],[0,91]]} }
            ] }
            """.trimIndent()
        )
        val route = result.single()
        assertEquals(MapPoint(19.4, -99.1), route.path.first())
        assertEquals("9 min", route.timeLabel())
        assertEquals("4,4 km", route.distanceLabel())
        assertEquals(listOf("Gira a la derecha por Avenida Central", "Llegaste al destino"), route.instructions)
    }

    @Test
    fun googleMapsDirectionsPreviewUsesTheDestinationAndSelectedTravelMode() {
        val destination = MapPoint(19.346, -99.192)
        val base = "https://www.google.com/maps/dir/?api=1&destination=19.346000%2C-99.192000"
        assertEquals("$base&travelmode=driving", googleMapsDirectionsUrl(destination, MapTravelMode.CAR))
        assertEquals("$base&travelmode=walking", googleMapsDirectionsUrl(destination, MapTravelMode.WALK))
    }
}
