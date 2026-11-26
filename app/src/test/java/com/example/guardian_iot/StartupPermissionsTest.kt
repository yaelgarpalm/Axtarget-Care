package com.example.guardian_iot

import android.Manifest.permission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupPermissionsTest {
    @Test
    fun protectedPersonGetsLocationFirstAndCanConfigureCallingAndTheBracelet() {
        assertEquals(
            listOf(StartupPermissionGroup.LOCATION, StartupPermissionGroup.NOTIFICATIONS,
                StartupPermissionGroup.CAMERA, StartupPermissionGroup.NEARBY, StartupPermissionGroup.CONTACTS,
                StartupPermissionGroup.SMS, StartupPermissionGroup.PHONE),
            groups(TzindaRole.WEARER, 36),
        )
    }

    @Test
    fun caregiverAndCameraGetOnlyTheirRelevantPermissions() {
        assertEquals(
            listOf(StartupPermissionGroup.LOCATION, StartupPermissionGroup.NOTIFICATIONS, StartupPermissionGroup.CAMERA),
            groups(TzindaRole.CAREGIVER, 36),
        )
        assertEquals(listOf(StartupPermissionGroup.CAMERA), groups(TzindaRole.CAMERA, 32))
    }

    @Test
    fun oldDevicesAndUnavailableHardwareDoNotCreateRequests() {
        assertEquals(
            listOf(StartupPermissionGroup.LOCATION, StartupPermissionGroup.CONTACTS),
            startupPermissionGroups(TzindaRole.WEARER, 30, supportsCamera = false, usesNearby = true,
                canRequestSms = false, supportsCalling = false, supportsSim = false),
        )
    }

    @Test
    fun approximateLocationSatisfiesSetupButNearbyNeedsBothGrants() {
        assertTrue(StartupPermissionGroup.LOCATION.isGranted { it == permission.ACCESS_COARSE_LOCATION })
        assertFalse(StartupPermissionGroup.NEARBY.isGranted { it == permission.BLUETOOTH_CONNECT })
    }

    private fun groups(role: TzindaRole, sdk: Int) =
        startupPermissionGroups(role, sdk, supportsCamera = true, usesNearby = true, canRequestSms = true,
            supportsCalling = true, supportsSim = true)
}
