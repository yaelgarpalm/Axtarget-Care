package com.example.guardian_iot

/** A peripheral is eligible only after Android has an encrypted bond for it. */
internal object UnoQBlePolicy {
    fun mayConnect(isBonded: Boolean): Boolean = isBonded
}
