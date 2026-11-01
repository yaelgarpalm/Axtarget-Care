package com.example.guardian_iot

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.Future

object SyncScheduler {
    private const val JOB_ID = 712045
    private const val PERIODIC_JOB_ID = 712046
    private const val PERIODIC_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private val immediateExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var immediateSyncInFlight = false

    fun enqueue(context: Context, immediate: Boolean = true) {
        val app = context.applicationContext
        val sessions = EncryptedSessionStore(app)
        val backend = SupabaseBackend(sessions)
        if (!backend.isConfigured || sessions.read() == null) {
            android.util.Log.w(
                "SyncScheduler",
                "Sync no encolado: configurado=${backend.isConfigured}, sesion=${sessions.read() != null}",
            )
            return
        }
        val scheduler = app.getSystemService(JobScheduler::class.java)
        if (scheduler != null) {
            val job = JobInfo.Builder(JOB_ID, ComponentName(app, SupabaseSyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            scheduler.schedule(job)
            // Respaldo periódico: revive la sincronización aunque una corrida anterior
            // haya agotado los reintentos (jobFinished(retry=false)).
            if (scheduler.getPendingJob(PERIODIC_JOB_ID) == null) {
                scheduler.schedule(
                    JobInfo.Builder(PERIODIC_JOB_ID, ComponentName(app, SupabaseSyncJobService::class.java))
                        .setPeriodic(PERIODIC_INTERVAL_MS)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPersisted(true)
                        .build(),
                )
            }
        }
        if (immediate && !immediateSyncInFlight) {
            immediateSyncInFlight = true
            immediateExecutor.execute {
                try {
                    SupabaseSyncRunner(app).run()
                } catch (t: Throwable) {
                    android.util.Log.w("SyncScheduler", "Immediate sync failed: ${t.message}")
                } finally {
                    immediateSyncInFlight = false
                }
            }
        }
    }
}

class SupabaseSyncJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var active: Future<*>? = null

    override fun onStartJob(parameters: JobParameters): Boolean = startSync(parameters)

    /**
     * Without this override JobService logs “onNetworkChanged() not implemented” every time
     * connectivity changes. If the previous run already finished, start it again so pending
     * records are uploaded as soon as the network is back.
     */
    override fun onNetworkChanged(parameters: JobParameters) {
        if (active?.isDone != false) startSync(parameters)
    }

    private fun startSync(parameters: JobParameters): Boolean {
        val backend = SupabaseBackend(EncryptedSessionStore(applicationContext))
        if (!backend.isConfigured || EncryptedSessionStore(applicationContext).read() == null) return false
        active = executor.submit {
            val retry = runCatching { SupabaseSyncRunner(applicationContext).run() }.getOrElse {
                getSharedPreferences("tzinda_native", MODE_PRIVATE).edit().putString(
                    "sync_status",
                    "No se pudo sincronizar; los datos locales siguen guardados.",
                ).apply()
                true
            }
            jobFinished(parameters, retry)
        }
        return true
    }

    override fun onStopJob(parameters: JobParameters): Boolean {
        active?.cancel(true)
        active = null
        return true
    }

    override fun onDestroy() {
        active?.cancel(true)
        executor.shutdownNow()
        super.onDestroy()
    }
}

private class SupabaseSyncRunner(context: Context) {
    private val app = context.applicationContext
    private val store = AppStore(app)
    private val database = store.telemetry
    private val sessions = EncryptedSessionStore(app)
    private val backend = SupabaseBackend(sessions)

    fun run(): Boolean {
        val session = runCatching { backend.currentSession() }.getOrNull() ?: run {
            store.prefs.edit().putString(
                "sync_status",
                "Sesión no disponible; abre la app e inicia sesión para reanudar la sincronización.",
            ).apply()
            return false
        }
        if (store.bindLocalDataToUser(session.userId)) {
            android.util.Log.i("SyncRunner", "La sesión es de otra cuenta; se borraron los datos locales anteriores.")
        }
        runCatching { store.prepareContactsForUser(session.userId) }
        validateCaregiverLink()
        var hasError = false

        try {
            val existing = backend.userDevices(session.userId)
            val byLogicalId = mutableMapOf<String, String>()
            val allExistingIds = mutableSetOf<String>()
            for (i in 0 until existing.length()) {
                val dev = existing.optJSONObject(i) ?: continue
                val devId = dev.optString("id")
                val logId = dev.optString("logical_id")
                val devType = dev.optString("device_type")
                val linkStatus = dev.optString("link_status")
                val devName = dev.optString("device_name").takeUnless { it.isBlank() } ?: "Cámara"
                if (devId.isNotBlank()) allExistingIds.add(devId)
                if (logId.isNotBlank() && devId.isNotBlank()) {
                    byLogicalId[logId] = devId
                }
                if (devType == "camera" && linkStatus == "linked" && logId != "camera-phone-local" && devId.isNotBlank()) {
                    store.upsertRegisteredCamera(devId, devName, enqueueSync = false)
                }
            }

            val existingPhoneId = byLogicalId["phone-primary"]
                ?: (0 until existing.length()).mapNotNull { existing.optJSONObject(it) }
                    .firstOrNull { it.optString("device_type") == "phone" }?.optString("id")
            val phoneFallbackId = existingPhoneId
                ?: if (allExistingIds.contains(store.localDeviceId())) java.util.UUID.randomUUID().toString() else store.localDeviceId()
            val phoneLogical = if (existingPhoneId != null) {
                (0 until existing.length()).mapNotNull { existing.optJSONObject(it) }
                    .firstOrNull { it.optString("id") == existingPhoneId }?.optString("logical_id")?.takeIf { it.isNotBlank() } ?: "phone-primary"
            } else "phone-primary"
            val phoneId = backend.registerOrUpdateDevice(
                existingPhoneId, phoneFallbackId, phoneLogical, "Teléfono TzindaApp", "phone", "not_applicable"
            )

            val existingFallId = byLogicalId["fall-sensor-phone"]
            val fallFallback = existingFallId
                ?: if (allExistingIds.contains(store.fallSensorDeviceId())) java.util.UUID.randomUUID().toString() else store.fallSensorDeviceId()
            val fallId = backend.registerOrUpdateDevice(
                existingFallId, fallFallback, "fall-sensor-phone", "Sensor de caídas del teléfono", "fall_sensor", "unknown"
            )

            val existingWearId = byLogicalId["wearable-sensors"]
            val wearFallback = existingWearId
                ?: if (allExistingIds.contains(store.wearableDeviceId())) java.util.UUID.randomUUID().toString() else store.wearableDeviceId()
            val wearId = backend.registerOrUpdateDevice(
                existingWearId, wearFallback, "wearable-sensors", "Pulsera y sensores biométricos", "wearable", "unknown"
            )

            val existingArdId = byLogicalId["arduino-uno-q"]
            val ardFallback = existingArdId
                ?: if (allExistingIds.contains(store.arduinoDeviceId())) java.util.UUID.randomUUID().toString() else store.arduinoDeviceId()
            val ardId = backend.registerOrUpdateDevice(
                existingArdId, ardFallback, "arduino-uno-q", "Arduino UNO Q · puente de datos", "arduino", "unknown"
            )

            val existingCamId = byLogicalId["camera-phone-local"]
            val camFallback = existingCamId
                ?: if (allExistingIds.contains(store.cameraDeviceId())) java.util.UUID.randomUUID().toString() else store.cameraDeviceId()
            val camId = backend.registerOrUpdateDevice(
                existingCamId, camFallback, "camera-phone-local", "Cámara IA de este teléfono", "camera", "not_applicable"
            )

            store.registeredCameras().forEach { camera ->
                if (!allExistingIds.contains(camera.id)) {
                    val existingReg = byLogicalId["camera-${camera.id}"]
                    val regFallback = existingReg ?: camera.id
                    backend.registerOrUpdateDevice(
                        existingReg, regFallback, "camera-${camera.id}", camera.name, "camera", "unlinked"
                    )
                }
            }

            store.prefs.edit()
                .putString("local_device_id", phoneId)
                .putString("fall_sensor_device_id", fallId)
                .putString("wearable_device_id", wearId)
                .putString("arduino_device_id", ardId)
                .putString("local_camera_device_id", camId)
                .putBoolean("device_sync_configured", true)
                .apply()
        } catch (error: Exception) {
            android.util.Log.w("SyncRunner", "Device registration warning: ${error.message}", error)
            hasError = true
            store.prefs.edit().putBoolean("device_sync_configured", true).apply()
        }

        try { syncProfile() } catch (_: Exception) { hasError = true }
        if (store.role() != AppStore.ROLE_CAMERA) {
            val caregiver = store.role() == AppStore.ROLE_CAREGIVER
            val medicationOwner = if (caregiver) store.prefs.getString("caregiver_owner_id", "").orEmpty() else session.userId
            if (medicationOwner.isNotBlank()) {
                val medications = MedicationRepository(app, backend, store)
                try {
                    if (!caregiver) medications.currentOwnerScope()
                    medications.synchronize(medicationOwner, readOnly = caregiver)
                }
                catch (f: BackendFailure) { android.util.Log.w("SyncRunner", "Medication sync failed: http=${f.status} ${f.category}: ${f.message}"); hasError = true }
                catch (e: Exception) { android.util.Log.w("SyncRunner", "Medication sync failed: ${e.message}"); hasError = true }
            }
            if (store.role() == AppStore.ROLE_WEARER) {
                try {
                    val caregivers = backend.activeCaregiversForOwner()
                    store.prefs.edit().putBoolean("wearer_has_linked_caregiver", caregivers.length() > 0).apply()
                } catch (_: Exception) {}
            }
        }
        if (store.prefs.getBoolean("device_sync_configured", false)) {
            try { syncContacts() } catch (_: Exception) { hasError = true }
            syncEvents { hasError = true }
            syncNotificationAttempts { hasError = true }
            syncMeasurements { hasError = true }
            syncLocationHistory { hasError = true }
            syncPhoneMotion(session.userId) { hasError = true }
        }

        val careFeatures = CareFeatureStore(app)
        if (store.role() == AppStore.ROLE_CAREGIVER) {
            val ownerId = store.prefs.getString("caregiver_owner_id", "").orEmpty()
            if (ownerId.isNotBlank()) {
                careFeatures.pendingDeletes().forEach { id ->
                    try { backend.deleteCareRecord(ownerId, id); careFeatures.markDeletedSynced(id) }
                    catch (e: Exception) { android.util.Log.w("CareSync", "delete care_record falló: ${syncError(e)}"); hasError = true }
                }
                careFeatures.rows("alert_followup").filter { it.optString("syncState") != "synced" }.forEach { followup ->
                    try { backend.insertAlertFollowup(ownerId, followup); careFeatures.markSynced(followup.optString("id")) }
                    catch (e: Exception) { android.util.Log.w("CareSync", "alert_followup falló: ${syncError(e)}"); hasError = true }
                }
                careFeatures.rows("zone").filter { it.optString("syncState") != "synced" }.forEach { zone ->
                    try { backend.upsertCareRecord(zone.optString("ownerUserId", ownerId).ifBlank { ownerId }, zone); careFeatures.markSynced(zone.optString("id")) }
                    catch (e: Exception) { android.util.Log.w("CareSync", "upsert zone falló: ${syncError(e)}"); hasError = true }
                }
                listOf("journal", "routine").forEach { kind ->
                    careFeatures.rows(kind).filter { it.optString("syncState") != "synced" }.forEach { record ->
                        try { backend.upsertCareRecord(record.optString("ownerUserId", ownerId).ifBlank { ownerId }, record); careFeatures.markSynced(record.optString("id")) }
                        catch (e: Exception) { android.util.Log.w("CareSync", "upsert $kind falló: ${syncError(e)}"); hasError = true }
                    }
                }
                try {
                    val shared = backend.sharedCareRecords(ownerId)
                    store.prefs.edit().putString("caregiver_shared_care_records", shared.toString()).apply()
                    store.prefs.edit().putString("caregiver_shared_alert_followups", backend.sharedAlertFollowups(ownerId).toString()).apply()
                } catch (e: Exception) { android.util.Log.w("CareSync", "lectura de care_records compartidos falló: ${syncError(e)}"); hasError = true }
            }
        } else {
            careFeatures.rows("alert_followup").filter { it.optString("syncState") != "synced" }.forEach { followup ->
                try { backend.insertAlertFollowup(session.userId, followup); careFeatures.markSynced(followup.optString("id")) }
                catch (e: Exception) { android.util.Log.w("CareSync", "alert_followup falló: ${syncError(e)}"); hasError = true }
            }
            careFeatures.pendingDeletes().forEach { id ->
                try { backend.deleteCareRecord(session.userId, id); careFeatures.markDeletedSynced(id) }
                catch (e: Exception) { android.util.Log.w("CareSync", "delete care_record falló: ${syncError(e)}"); hasError = true }
            }
            careFeatures.rowsAll().filter { it.optString("kind") != "alert_followup" && it.optString("syncState") != "synced" }.forEach { record ->
                try { backend.upsertCareRecord(record.optString("ownerUserId", session.userId).ifBlank { session.userId }, record); careFeatures.markSynced(record.optString("id")) }
                catch (e: Exception) { android.util.Log.w("CareSync", "upsert ${record.optString("kind")} falló: ${syncError(e)}"); hasError = true }
            }
        }

        val summary = database.syncStateSummary()
        val failures = if (hasError) store.prefs.getInt("sync_global_failures", 0) + 1 else 0
        store.prefs.edit().putInt("sync_global_failures", failures).apply()
        val retry = (summary.pending > 0 || hasError) && failures < MAX_GLOBAL_RETRIES
        if (!retry) {
            if (!hasError && summary.pending == 0 && summary.failed == 0) store.prefs.edit()
                    .putLong("last_sync_success_at", System.currentTimeMillis())
                    .putString("sync_status", "Sincronizado")
                    .apply()
            else store.prefs.edit().putString("sync_status", "Se agotaron los reintentos; revisa la conexión y pulsa Sincronizar ahora.").apply()
        } else if (summary.failed > 0) {
            store.prefs.edit().putString("sync_status", "Hay datos locales que requieren reintento manual.").apply()
        } else {
            store.prefs.edit().putString("sync_status", "Sincronización pendiente; se reintentará con conexión.").apply()
        }
        return retry
    }

    /** Incluye el código HTTP y la categoría para que el logcat permita diagnosticar RLS vs red. */
    private fun syncError(e: Exception): String =
        (e as? BackendFailure)?.let { "http=${it.status} ${it.category}: ${it.message}" } ?: (e.message ?: e.toString())

    /**
     * El vínculo guardado en prefs puede quedar huérfano tras limpiezas remotas o cambios de
     * cuenta. Si Supabase no lo confirma, se descarta la caché derivada para no mostrar datos
     * de otra persona.
     */
    private fun validateCaregiverLink() {
        val ownerId = store.prefs.getString("caregiver_owner_id", "").orEmpty()
        if (ownerId.isBlank()) return
        val links = runCatching { backend.linkedPeople() }.getOrNull() ?: return
        val stillLinked = (0 until links.length()).any {
            links.optJSONObject(it)?.optString("owner_user_id") == ownerId
        }
        if (!stillLinked) {
            android.util.Log.i("SyncRunner", "El vínculo de cuidador con $ownerId ya no existe en Supabase; caché local descartada.")
            store.clearRemoteDerivedCaches()
        }
    }

    private fun syncProfile() {
        val local = store.profile()
        val localName = local.text("fullName").trim()
        val localRole = store.role()
        val remote = backend.fetchProfile()
        val remoteTime = remote?.optString("updated_at")?.toEpochMillis() ?: 0L
        val localTime = store.profileUpdatedAt()
        val hasUnsyncedLocalEdit = localTime > store.lastProfileSyncAt()
        val hasUnsyncedLocalRole = store.roleUpdatedAt() > store.lastRoleSyncAt()

        if (localName.isBlank()) {
            if (remote != null) {
                val roleToApply = if (hasUnsyncedLocalRole && localRole.isNotBlank()) localRole else remote.optString("role")
                store.applyRemoteProfile(remote.optString("full_name"), roleToApply, remoteTime)
                if (hasUnsyncedLocalRole && localRole.isNotBlank()) {
                    backend.updateProfile(remote.optString("full_name"), localRole, JSONObject())
                    store.markProfileSynced()
                }
            }
            return
        }

        if (remote != null && remoteTime > localTime && !hasUnsyncedLocalEdit) {
            val mergedRole = if (hasUnsyncedLocalRole) localRole else remote.optString("role")
            store.applyRemoteProfile(remote.optString("full_name"), mergedRole, remoteTime)
            if (hasUnsyncedLocalRole) {
                backend.updateProfile(remote.optString("full_name"), mergedRole, JSONObject())
                store.markProfileSynced()
            }
            return
        }

        val preferences = JSONObject()
            .put("privacy_notice_version", store.prefs.getString("privacy_notice_version", ""))
            .put("privacy_consent_at", store.prefs.getLong("privacy_consent_at", 0L))
        val role = store.role().takeIf { it.isNotBlank() }
        // Sin fila remota el PATCH actualiza 0 filas en silencio; el upsert la recrea.
        if (remote == null) backend.upsertProfile(localName, role, preferences)
        else backend.updateProfile(localName, role, preferences)
        store.markProfileSynced()
    }

    private fun syncContacts() {
        database.pendingContacts().forEach { contact ->
            try {
                if (contact.deleted) backend.deleteContact(contact.id)
                else backend.upsertContact(contact.id, contact.name, contact.phone, JSONObject(contact.preferencesJson))
                database.markContactSynced(contact.id, contact.deleted)
            } catch (_: BackendFailure) {
                database.markContactFailure(contact.id)
                throw BackendFailure(BackendFailure.Category.TEMPORARY)
            }
        }

        if (database.pendingContacts().isNotEmpty()) return
        val remote = backend.fetchTrustedContacts()
        val contacts = buildList {
            for (i in 0 until remote.length()) {
                val row = remote.optJSONObject(i) ?: continue
                val phone = PhoneNumbers.normalize(row.optString("phone")) ?: continue
                val id = row.optString("id")
                if (runCatching { java.util.UUID.fromString(id) }.isFailure) continue
                add(
                    LocalContactRecord(
                        id = id,
                        name = row.optString("name").take(120),
                        phone = phone,
                        preferencesJson = row.optJSONObject("preferences")?.toString() ?: "{}",
                        updatedAtMillis = row.optString("updated_at").toEpochMillis().takeIf { it > 0L } ?: System.currentTimeMillis(),
                    ),
                )
            }
        }
        store.applyRemoteContacts(contacts)
    }

    private inline fun syncEvents(onError: () -> Unit) {
        val ardId = store.arduinoDeviceId()
        val phoneId = store.localDeviceId()
        database.pendingFallEvents().forEach { event ->
            try {
                val targetDeviceId = if (event.source == "arduino") ardId else phoneId
                val pending = PendingFallEvent(
                    event.id, event.incidentId, targetDeviceId, event.occurredAtMillis, event.source,
                    event.classification, event.confirmationStatus, event.summary,
                )
                val cameraAccountEvent = store.role() == AppStore.ROLE_CAMERA && event.source == "camera_ai"
                if (cameraAccountEvent) backend.insertPairedCameraFallEvent(pending) else backend.insertFallEvent(pending)
                val incident = event.incidentId
                val cameraIncidentAlias = store.role() == AppStore.ROLE_WEARER && event.source == "camera_ai"
                if (incident != null && !cameraAccountEvent && !cameraIncidentAlias) backend.upsertEmergencyAlert(
                    incident, event.deviceId, database.firstFallEventId(incident) ?: event.id,
                    database.latestAlertState(incident),
                )
                database.markFallEventSynced(event.id)
            } catch (_: Exception) {
                database.markFallEventFailure(event.id)
                onError()
            }
        }
    }

    private inline fun syncNotificationAttempts(onError: () -> Unit) {
        database.pendingNotificationAttempts().forEach { attempt ->
            try {
                val eventId = database.firstFallEventId(attempt.incidentId)
                    ?: throw BackendFailure(BackendFailure.Category.VALIDATION)
                val alertStatus = if (attempt.status in setOf("requested", "accepted_by_os", "sent_to_network", "delivered"))
                    "actions_requested" else "not_confirmed"
                backend.upsertEmergencyAlert(attempt.incidentId, attempt.deviceId, eventId, alertStatus)
                backend.insertNotificationAttempt(
                    attempt.id, attempt.deviceId, attempt.incidentId, attempt.channel, attempt.status,
                    attempt.attemptedAtMillis, attempt.confirmedAtMillis, attempt.errorCode,
                )
                database.markAttemptSynced(attempt.id)
            } catch (_: Exception) {
                database.markAttemptFailure(attempt.id)
                onError()
            }
        }
    }

    private inline fun syncMeasurements(onError: () -> Unit) {
        val ardId = store.arduinoDeviceId()
        val phoneId = store.localDeviceId()
        database.pendingMeasurements().forEach { reading ->
            try {
                val targetDeviceId = if (reading.source == LocalTelemetryDatabase.SOURCE_ARDUINO) ardId else phoneId
                backend.insertMeasurement(
                    reading.id, targetDeviceId, reading.type, reading.value, reading.unit,
                    reading.capturedAtMillis, reading.source, reading.isValid,
                )
                database.markMeasurementSynced(reading.id)
            } catch (_: Exception) {
                database.markMeasurementFailure(reading.id)
                onError()
            }
        }
    }

    /** Uploads the queued GPS trail and refreshes the live position the caregiver watches. */
    private inline fun syncLocationHistory(onError: () -> Unit) {
        val phoneId = store.localDeviceId()
        database.pendingLocations().forEach { record ->
            try {
                backend.insertLocationHistory(record, phoneId)
                database.markLocationSynced(record.id)
            } catch (failure: BackendFailure) {
                if (failure.status == 404) {
                    android.util.Log.w("SyncRunner", "location_history no existe en Supabase; aplica la migración 20261026120100.")
                }
                database.markLocationFailure(record.id)
                onError()
            } catch (_: Exception) {
                database.markLocationFailure(record.id)
                onError()
            }
        }
        if (!store.prefs.getBoolean("include_location", false)) return
        val latest = database.latestLocation() ?: return
        if (latest.capturedAtMillis <= store.prefs.getLong("sync_location_last_upsert_at", 0L)) return
        try {
            backend.upsertCaregiverLocation(phoneId, latest.latitude, latest.longitude,
                latest.accuracyM?.toFloat(), latest.capturedAtMillis)
            store.prefs.edit().putLong("sync_location_last_upsert_at", latest.capturedAtMillis).apply()
        } catch (_: Exception) {
            onError()
        }
    }

    private inline fun syncPhoneMotion(userId: String, onError: () -> Unit) {
        val firstSyncWindowStart = System.currentTimeMillis() - FIRST_PHONE_MOTION_WINDOW_MS
        val lastUploadedAt = store.prefs.getLong("phone_motion_last_uploaded_at_$userId", firstSyncWindowStart)
        val summary = database.phoneMotionSummary(lastUploadedAt) ?: return
        try {
            backend.upsertPhoneMotionStatus(
                store.localDeviceId(),
                summary.sampleCount,
                summary.averageDynamicG,
                summary.peakDynamicG,
                summary.capturedAtMillis,
            )
            store.prefs.edit().putLong("phone_motion_last_uploaded_at_$userId", summary.capturedAtMillis).apply()
        } catch (_: BackendFailure) {
            onError()
        }
    }

    private fun String.toEpochMillis(): Long = runCatching { Instant.parse(this).toEpochMilli() }
        .recoverCatching { java.time.OffsetDateTime.parse(this).toInstant().toEpochMilli() }
        .getOrDefault(0L)

    companion object {
        private const val MAX_GLOBAL_RETRIES = 8
        private const val FIRST_PHONE_MOTION_WINDOW_MS = 60_000L
    }
}
