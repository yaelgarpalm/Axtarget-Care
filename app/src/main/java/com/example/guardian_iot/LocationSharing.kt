package com.example.guardian_iot

/** Separates local GPS use from consented publication. Revocation never erases local readings. */
internal class LocationSharing(private val store: AppStore) {
    private val prefs get() = store.prefs
    private val database get() = store.telemetry

    fun setConsent(enabled: Boolean) = synchronized(lock) {
        check(AccountStorage.isCurrent(store.appContext)) { "La cuenta cambió." }
        check(prefs.edit().putBoolean("include_location", enabled).commit())
        reconcile()
    }

    /** Also detects consent changed by profile edits and mode changes. */
    fun reconcile(): Boolean = synchronized(lock) {
        if (!AccountStorage.isCurrent(store.appContext)) return false
        val enabled = prefs.getBoolean("include_location", false) && store.role() == AppStore.ROLE_WEARER
        val initialized = prefs.getBoolean("location_policy_initialized", false)
        val previous = prefs.getBoolean("location_policy_enabled", false)
        if (!initialized || previous != enabled) {
            val hadQueue = database.latestLocation() != null
            database.withholdLocations()
            val clearDevices = pendingClearDevices().toMutableSet()
            if (previous || hadQueue) clearDevices.add(store.localDeviceId())
            check(prefs.edit().putBoolean("location_policy_initialized", true)
                .putBoolean("location_policy_enabled", enabled)
                .putLong("location_consent_epoch", maxOf(System.currentTimeMillis(), prefs.getLong("location_consent_epoch", 0) + 1))
                .putStringSet(CLEAR_DEVICES, clearDevices).remove("sync_location_last_upsert_at").commit())
        }
        enabled
    }

    fun record(latitude: Double, longitude: Double, capturedAt: Long, accuracyM: Float?) = synchronized(lock) {
        if (!AccountStorage.isCurrent(store.appContext)) return
        if (!latitude.isFinite() || latitude !in -90.0..90.0 || !longitude.isFinite() || longitude !in -180.0..180.0 || capturedAt <= 0) return
        val publish = reconcile() && capturedAt >= prefs.getLong("location_consent_epoch", Long.MAX_VALUE)
        database.recordLocation(latitude, longitude, capturedAt, accuracyM, shareForSync = publish)
    }

    private fun permits(record: LocalLocationRecord, epoch: Long): Boolean = synchronized(lock) {
        reconcile() && epoch == prefs.getLong("location_consent_epoch", 0) && record.capturedAtMillis >= epoch
    }

    /** Background only. All live/history publishers and revocations share this serialization. */
    fun flush(
        uploadHistory: (LocalLocationRecord) -> Unit,
        uploadLive: (LocalLocationRecord) -> Unit,
        clearRemote: (String) -> Unit,
        canPublishLive: () -> Boolean = { true },
    ): Boolean = synchronized(uploadLock) {
        if (!AccountStorage.isCurrent(store.appContext)) return true
        reconcile()
        if (!clearPending(clearRemote)) return false
        if (!reconcile()) return true
        val epoch = prefs.getLong("location_consent_epoch", 0)
        var success = true
        for (record in database.pendingLocations()) {
            if (!permits(record, epoch)) break
            try {
                uploadHistory(record)
                if (permits(record, epoch)) database.markLocationSynced(record.id)
            } catch (_: Exception) {
                if (permits(record, epoch)) database.markLocationFailure(record.id)
                success = false
            }
        }
        val latest = database.latestLocation()
        if (latest != null && permits(latest, epoch) && canPublishLive() && latest.capturedAtMillis > prefs.getLong("sync_location_last_upsert_at", 0)) {
            try {
                uploadLive(latest)
                if (permits(latest, epoch)) prefs.edit().putLong("sync_location_last_upsert_at", latest.capturedAtMillis).commit()
            } catch (_: Exception) { success = false }
        }
        // If consent changed during an HTTP request, remove that last live position after it returns.
        reconcile()
        clearPending(clearRemote) && success
    }

    fun requestLiveClear() = synchronized(lock) {
        check(prefs.edit().putStringSet(CLEAR_DEVICES, pendingClearDevices() + store.localDeviceId())
            .remove("sync_location_last_upsert_at").commit())
    }

    fun clearPending(clearRemote: (String) -> Unit): Boolean = synchronized(uploadLock) {
        if (!AccountStorage.isCurrent(store.appContext)) return false
        reconcile()
        var success = true
        for (device in pendingClearDevices()) {
            if (!AccountStorage.isCurrent(store.appContext)) return false
            try {
                clearRemote(device)
                synchronized(lock) {
                    check(prefs.edit().putStringSet(CLEAR_DEVICES, pendingClearDevices() - device).commit())
                }
            } catch (_: Exception) { success = false }
        }
        success
    }

    private fun pendingClearDevices(): Set<String> = prefs.getStringSet(CLEAR_DEVICES, emptySet()).orEmpty().toSet()

    companion object {
        private val lock = Any()
        private val uploadLock = Any()
        private const val CLEAR_DEVICES = "location_pending_clear_devices"
        fun pendingClearCount(store: AppStore): Int = store.prefs.getStringSet(CLEAR_DEVICES, emptySet()).orEmpty().size
    }
}
