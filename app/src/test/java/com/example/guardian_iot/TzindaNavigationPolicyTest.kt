package com.example.guardian_iot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TzindaNavigationPolicyTest {
    @Test fun secondaryRoutesReturnThroughTheActualNavigationPath() {
        val history = TzindaNavigationHistory()
        history.record(TzindaTabs.HOME, TzindaTabs.SETTINGS, destinationIsSecondary = true)
        history.record(TzindaTabs.SETTINGS, TzindaTabs.PROFILE, destinationIsSecondary = true)
        assertEquals(TzindaTabs.SETTINGS, history.back())
        assertEquals(TzindaTabs.HOME, history.back())
        assertEquals(null, history.back())
    }

    @Test fun selectingPrimaryDestinationClearsSecondaryHistoryAndRestoresSavedRoutes() {
        val history = TzindaNavigationHistory()
        history.record(TzindaTabs.HOME, TzindaTabs.DEVICE, destinationIsSecondary = true)
        val restored = TzindaNavigationHistory().apply { restore(history.snapshot()) }
        assertEquals(TzindaTabs.HOME, restored.back())
        restored.record(TzindaTabs.HOME, TzindaTabs.SETTINGS, destinationIsSecondary = true)
        restored.record(TzindaTabs.SETTINGS, TzindaTabs.ACTIVITY, destinationIsSecondary = false)
        assertEquals(null, restored.back())
    }

    @Test fun caregiverPrimaryDestinationsAreHomeAlertsHealthAndSupervision() {
        val items = TzindaNavigationPolicy.items(TzindaRole.CAREGIVER)
        assertEquals(listOf("Inicio", "Alertas", "Salud", "Supervisión"), items.map { it.label })
        assertEquals(listOf(TzindaTabs.HOME, TzindaTabs.ACTIVITY, TzindaTabs.MEDICATIONS, TzindaTabs.SUPERVISION), items.map { it.targetTab })
    }

    @Test fun wearerNavigationStaysInsideWearerScreens() {
        val items = TzindaNavigationPolicy.items(TzindaRole.WEARER)
        assertEquals(listOf("Inicio", "Alertas", "Salud", "Supervisión"), items.map { it.label })
        assertTrue(items.none { it.targetTab == TzindaTabs.CAMERA || it.targetTab == TzindaTabs.CONTACTS })
    }

    @Test fun cameraPrimaryDestinationsStayInsideCameraRole() {
        val items = TzindaNavigationPolicy.items(TzindaRole.CAMERA)
        assertEquals(listOf("Inicio", "Alertas", "Vincular", "Ajustes"), items.map { it.label })
        assertEquals(TzindaTabs.HOME, items.first().targetTab)
        assertTrue(items.none { it.targetTab == TzindaTabs.CAMERA })
    }

    @Test fun secondaryScreensKeepNavigationAvailableWithoutSelectingAFalsePrimaryDestination() {
        assertEquals(2, TzindaNavigationPolicy.selectedIndex(TzindaRole.WEARER, TzindaTabs.MEDICATIONS))
        assertEquals(-1, TzindaNavigationPolicy.selectedIndex(TzindaRole.CAREGIVER, TzindaTabs.CONTACTS))
        assertEquals(2, TzindaNavigationPolicy.selectedIndex(TzindaRole.CAREGIVER, TzindaTabs.MEDICATIONS))
        assertEquals(0, TzindaNavigationPolicy.selectedIndex(TzindaRole.CAMERA, TzindaTabs.CAMERA))
    }

    @Test fun everyRoleHasExactlyFourDistinctPrimaryDestinations() {
        TzindaRole.values().forEach { role ->
            val items = TzindaNavigationPolicy.items(role)
            assertEquals(4, items.size)
            assertEquals(4, items.map { it.key }.distinct().size)
            assertTrue(items.all { it.label.isNotBlank() })
            assertTrue(items.all { it.targetTab >= 0 })
        }
    }

    @Test fun linkCopyDistinguishesNoLinkFromActiveButStaleLink() {
        assertEquals("Sin vínculo activo", TzindaStatusCopy.link(false, "").title)
        val stale = TzindaStatusCopy.link(true, "Elena", now = 10_000_000L, linkedAtMillis = 0L)
        assertEquals(TzindaTone.POSITIVE, stale.tone)
        assertTrue(stale.title.contains("Elena"))
        assertTrue(TzindaStatusCopy.link(true, "Elena", 9_000_000L, 10_000_000L).detail.contains("hace 16 min"))
    }

    @Test fun deviceCopyNeverClaimsConnectionFromRegistrationAlone() {
        val registered = TzindaStatusCopy.device(DeviceConnectionState.REGISTERED, null, now = 10_000L)
        assertEquals(TzindaTone.NEUTRAL, registered.tone)
        assertTrue(registered.title.contains("sin conexión confirmada"))
        val stale = TzindaStatusCopy.device(DeviceConnectionState.NO_RECENT_DATA, 0L, now = 100_000_000L)
        assertEquals(TzindaTone.WARNING, stale.tone)
        assertTrue(stale.detail.contains("sin datos"))
    }

    @Test fun syncCopySeparatesOfflineLocalPendingAndFailedStates() {
        assertEquals("Modo local", TzindaStatusCopy.sync(0, 0, online = true, signedIn = false).title)
        assertEquals("Sin Internet", TzindaStatusCopy.sync(3, 0, online = false, signedIn = true).title)
        assertEquals("Sincronización con error", TzindaStatusCopy.sync(3, 1, online = true, signedIn = true).title)
        assertEquals("Pendiente de sincronizar", TzindaStatusCopy.sync(3, 0, online = true, signedIn = true).title)
        assertEquals("Sin pendientes locales", TzindaStatusCopy.sync(0, 0, online = true, signedIn = true).title)
    }

    @Test fun countsUseNaturalSingularAndPlural() {
        assertEquals("1 alerta", TzindaText.count(1, "alerta"))
        assertEquals("3 alertas", TzindaText.count(3, "alerta"))
        assertEquals("1 evento", TzindaText.count(1, "evento"))
        assertEquals("0 eventos", TzindaText.count(0, "evento"))
    }

    @Test fun freshnessIsHumanReadableAndNeverInventsData() {
        assertEquals("hace menos de un minuto", TzindaStatusCopy.freshness(59_000L))
        assertEquals("hace 5 min", TzindaStatusCopy.freshness(5 * 60_000L))
        assertEquals("hace 3 h", TzindaStatusCopy.freshness(3 * 3_600_000L))
        assertEquals("hace más de un día", TzindaStatusCopy.freshness(2 * 86_400_000L))
    }

    @Test fun technicalCodesAreTranslatedForPeople() {
        assertEquals("Cámara", TzindaStatusCopy.source("camera_ai"))
        assertEquals("Sensores del teléfono", TzindaStatusCopy.source("Phone accelerometer"))
        assertEquals("Sistema", TzindaStatusCopy.source("other"))
        assertEquals("Posible caída", TzindaStatusCopy.classification("possible_fall"))
        assertEquals("Alerta cancelada", TzindaStatusCopy.classification("cancelled"))
        assertEquals("Sin confirmar", TzindaStatusCopy.confirmation("pending"))
        assertEquals("Cancelada", TzindaStatusCopy.confirmation("cancelled"))
        assertEquals("Tomada · confirmada", TzindaStatusCopy.medicationStatus("taken"))
    }

    @Test fun backendTimestampsWithNumericOffsetsAreParsed() {
        val utc = TzindaStatusCopy.timestampMillis("2026-10-26T01:43:32.164Z")
        assertTrue(utc > 0L)
        assertEquals(utc, TzindaStatusCopy.timestampMillis("2026-10-26T01:43:32.164+00:00"))
        assertEquals(utc + 6 * 3_600_000L, TzindaStatusCopy.timestampMillis("2026-10-26T01:43:32.164-06:00"))
        assertEquals(0L, TzindaStatusCopy.timestampMillis(""))
        assertEquals(0L, TzindaStatusCopy.timestampMillis("null"))
        assertEquals(0L, TzindaStatusCopy.timestampMillis("fecha desconocida"))
    }

    @Test fun vitalsCopyDoesNotShowPlaceholdersAsRealMeasurements() {
        val noReading = TzindaStatusCopy.vitals(false, 0L, connected = false)
        assertEquals("Sin lecturas", noReading.title)
        assertTrue(noReading.detail.contains("No se muestran valores de ejemplo"))
    }
}
