package com.example.guardian_iot

import org.junit.Assert.*
import org.junit.Test

class CareFeaturesPolicyTest {
    @Test fun registeredDeviceDoesNotAppearConnectedWithoutLiveSignal() {
        assertEquals(DeviceConnectionState.REGISTERED, DeviceStatusPolicy.state(true, null, null, 10_000))
        assertEquals(DeviceConnectionState.CONNECTED, DeviceStatusPolicy.state(true, true, null, 10_000))
        assertEquals(DeviceConnectionState.DISCONNECTED, DeviceStatusPolicy.state(true, false, 9_000, 10_000))
    }

    @Test fun staleDeviceDataIsNotReportedAsDisconnected() {
        assertEquals(DeviceConnectionState.NO_RECENT_DATA, DeviceStatusPolicy.state(true, null, 1_000, 90_000_000))
        assertEquals(DeviceConnectionState.UNKNOWN, DeviceStatusPolicy.state(true, null, 89_000_000, 90_000_000))
    }

    @Test fun geofenceNeedsValidLocationAndUsesAccuracyHysteresis() {
        assertFalse(GeoFencePolicy.validFix(0.0, 0.0, 100f, 1000, now = 1000))
        assertTrue(GeoFencePolicy.validFix(19.4, -99.1, 8f, 1000, now = 1000))
        assertEquals(true, GeoFencePolicy.classify(50.0, 100.0, 10.0, null))
        assertEquals(true, GeoFencePolicy.classify(105.0, 100.0, 10.0, true))
        assertEquals(false, GeoFencePolicy.classify(130.0, 100.0, 10.0, true))
        assertNull(GeoFencePolicy.classify(50.0, 10.0, 10.0, null))
    }

    @Test fun inactivityRequiresAnAuthorizedAvailableActivitySource() {
        val now = 100_000L
        assertFalse(RoutinePolicy.inactivityNotice(false, true, 0L, now, 1_000))
        assertFalse(RoutinePolicy.inactivityNotice(true, false, 0L, now, 1_000))
        assertFalse(RoutinePolicy.inactivityNotice(true, true, null, now, 1_000))
        assertTrue(RoutinePolicy.inactivityNotice(true, true, 0L, now, 1_000))
    }
}
