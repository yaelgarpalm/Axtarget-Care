package com.example.guardian_iot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArduinoHttpsTest {
    @Test fun normalizesOpenSslSha256Fingerprint() {
        val raw = "SHA256=" + (0..31).joinToString(":") { "%02x".format(it) }
        assertEquals((0..31).joinToString("") { "%02X".format(it) }, ArduinoHttps.normalizeFingerprint(raw))
    }

    @Test fun rejectsTruncatedAndNonHexFingerprints() {
        assertThrows(IllegalArgumentException::class.java) { ArduinoHttps.normalizeFingerprint("AB:CD") }
        assertThrows(IllegalArgumentException::class.java) { ArduinoHttps.normalizeFingerprint("Z".repeat(64)) }
    }

    @Test fun matchesOnlyTheExactCertificateBytes() {
        val certificate = byteArrayOf(0, 1, 2, 3, 4, 5)
        val fingerprint = ArduinoHttps.certificateFingerprint(certificate)
        assertTrue(ArduinoHttps.matchesCertificate(certificate, fingerprint))
        assertFalse(ArduinoHttps.matchesCertificate(certificate + 6, fingerprint))
        assertFalse(ArduinoHttps.matchesCertificate(certificate, "0".repeat(64)))
    }

    @Test fun refusesHttpBeforeOpeningASocketAndDisablesHttpsRedirects() {
        assertThrows(IllegalArgumentException::class.java) {
            ArduinoHttps.openPinnedConnection("http://127.0.0.1:8081", "A".repeat(64))
        }
        val connection = ArduinoHttps.openPinnedConnection("https://127.0.0.1:1", "A".repeat(64))
        try {
            assertFalse(connection.instanceFollowRedirects)
        } finally {
            connection.disconnect()
        }
    }
}
