package com.example.guardian_iot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnoQBlePolicyTest {
    @Test fun rejectsUnbondedPeripheralsAndAllowsOnlyAnExistingBond() {
        assertFalse(UnoQBlePolicy.mayConnect(isBonded = false))
        assertTrue(UnoQBlePolicy.mayConnect(isBonded = true))
    }
}
