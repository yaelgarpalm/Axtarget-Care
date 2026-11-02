package com.example.guardian_iot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class MedicationAdherenceSummary(
    val scheduledDue: Int,
    val taken: Int,
    val pending: Int,
    val omitted: Int,
) {
    val percentage: Int? get() = if (scheduledDue == 0) null else ((taken * 100.0) / scheduledDue).toInt()
}

/** Coordinates the offline SQLite source of truth with the authenticated Supabase session. */
class MedicationRepository(
    context: Context,
    private val backend: SupabaseBackend,
    private val store: AppStore = AppStore(context),
) {
    private val app = store.appContext
    val database = MedicationDatabase.getInstance(app)

    fun currentOwnerScope(): String {
        val userId = runCatching { EncryptedSessionStore(app).read()?.userId }.getOrNull()
        if (!userId.isNullOrBlank()) {
            if (store.ownerUserId()?.let { it != userId } == true) throw BackendFailure(BackendFailure.Category.SESSION)
            val localScope = localScope()
            val claimedBy = store.prefs.getString(LOCAL_DATA_CLAIM_PREF, "").orEmpty()
            if (claimedBy.isBlank()) {
                database.claimLocalData(localScope, userId)
                store.prefs.edit().putString(LOCAL_DATA_CLAIM_PREF, userId).apply()
            } else if (claimedBy == userId) database.claimLocalData(localScope, userId)
            return userId
        }
        return store.prefs.getString(LOCAL_DATA_CLAIM_PREF, "").orEmpty().ifBlank { localScope() }
    }

    fun localScope(): String = "local:${store.localDeviceId()}"

    /** Local authorization is re-evaluated on each action, including stale notification actions. */
    fun canManageOwner(ownerId: String): Boolean = synchronized(AccountStorage.lock) {
        AccountStorage.isCurrent(app) && store.role() == AppStore.ROLE_WEARER &&
            runCatching { ownerId == currentOwnerScope() }.getOrDefault(false)
    }

    fun canReadSharedOwner(ownerId: String): Boolean = synchronized(AccountStorage.lock) {
        val actor = EncryptedSessionStore(app).read()?.userId
        AccountStorage.isCurrent(app) && store.role() == AppStore.ROLE_CAREGIVER &&
            actor != null && actor == store.ownerUserId() && actor != ownerId &&
            ownerId.isNotBlank() && store.prefs.getString("caregiver_owner_id", "") == ownerId &&
            !store.prefs.getString("caregiver_link_id", "").isNullOrBlank()
    }

    fun canActOnIntake(intakeId: String): Boolean = synchronized(AccountStorage.lock) {
        val intake = database.getIntake(intakeId) ?: return false
        val medication = database.getMedication(intake.ownerUserId, intake.medicationId) ?: return false
        !medication.isDeleted && medication.isActive && (canManageOwner(intake.ownerUserId) ||
            (canReadSharedOwner(intake.ownerUserId) && medication.shareWithCaregiver))
    }

    fun createMedication(
        ownerId: String,
        name: String,
        dose: String,
        presentation: String,
        frequency: String,
        times: List<String>,
        startDate: String,
        endDate: String?,
        instructions: String,
        isActive: Boolean = true,
        shareWithCaregiver: Boolean = false,
        existing: Medication? = null,
    ): Medication = synchronized(AccountStorage.lock) {
        check(canManageOwner(ownerId)) { "Solo puedes editar los medicamentos de tu propia cuenta en modo persona protegida." }
        check(existing == null || existing.ownerUserId == ownerId && database.getMedication(ownerId, existing.id) != null) {
            "El medicamento no pertenece a esta cuenta."
        }
        MedicationSchedulePolicy.validate(name, dose, frequency, times, startDate, endDate)?.let { throw IllegalArgumentException(it) }
        if (database.medications(ownerId).any { it.id != existing?.id && !it.isDeleted && it.isActive && it.name.equals(name.trim(), ignoreCase = true) && it.scheduleTimes == times.sorted() })
            throw IllegalArgumentException("Ya hay un medicamento activo con ese nombre y horario. Revisa la lista antes de agregar otro.")
        val normalizedTimes = times.distinct().sorted()
        val now = System.currentTimeMillis()
        val item = Medication(
            id = existing?.id ?: UUID.randomUUID().toString(), ownerUserId = ownerId,
            name = name.trim(), prescribedDose = dose.trim(), presentation = presentation.trim().take(80),
            frequency = frequency.trim().take(80), scheduleTimes = normalizedTimes,
            startDate = startDate.trim(), endDate = endDate?.trim()?.takeIf(String::isNotBlank),
            instructions = instructions.trim().take(500), isActive = isActive, shareWithCaregiver = shareWithCaregiver,
            isDeleted = false, createdAtMillis = existing?.createdAtMillis ?: now, updatedAtMillis = now,
        )
        existing?.let { database.cancelFutureIntakes(it.id, now) }
        return database.saveMedication(item)
    }

    fun deleteMedication(ownerId: String, medicationId: String): Medication? = synchronized(AccountStorage.lock) {
        check(canManageOwner(ownerId)) { "No puedes eliminar medicamentos de otra persona." }
        database.deleteMedication(ownerId, medicationId)
    }

    /** Counts only already-due schedules; future reminders never reduce adherence. */
    fun adherence(ownerId: String, fromMillis: Long, nowMillis: Long = System.currentTimeMillis()): MedicationAdherenceSummary {
        val due = database.intakes(ownerId, fromMillis, nowMillis + 1, limit = 2_000)
            .filter { it.status != "cancelled" && it.scheduledAtMillis <= nowMillis }
        return MedicationAdherenceSummary(
            scheduledDue = due.size,
            taken = due.count { it.status == "taken" },
            pending = due.count { it.status in setOf("pending", "snoozed") },
            omitted = due.count { it.status in setOf("skipped", "overdue") },
        )
    }

    /** Makes the existing medication list visible to the linked caregiver in one explicit action. */
    fun shareAllWithCaregiver(ownerId: String): Int = synchronized(AccountStorage.lock) {
        check(canManageOwner(ownerId)) { "Solo el propietario puede compartir sus medicamentos." }
        if (ownerId.isBlank() || ownerId.startsWith("local:")) return 0
        val privateItems = database.medications(ownerId).filterNot { it.shareWithCaregiver || it.isDeleted }
        privateItems.forEach { database.saveMedication(it.copy(shareWithCaregiver = true)) }
        if (privateItems.isNotEmpty()) SyncScheduler.enqueue(app, immediate = true)
        return privateItems.size
    }

    fun acceptIntakeAction(intakeId: String, status: String): Boolean = synchronized(AccountStorage.lock) {
        if (!AccountStorage.isCurrent(app)) return false
        if (!canActOnIntake(intakeId)) return false
        // Trazabilidad: quien confirma la toma (dueño o cuidador vinculado) se guarda con la acción.
        val actorId = EncryptedSessionStore(app).read()?.userId
        if (actorId != null && store.ownerUserId()?.let { it != actorId } == true) return false
        val snoozeUntil = if (status == "snoozed") System.currentTimeMillis() + SNOOZE_MS else null
        val accepted = database.recordAction(intakeId, status, System.currentTimeMillis(), snoozeUntil, actorId)
        if (accepted) SyncScheduler.enqueue(app)
        return accepted
    }

    /** Last-write-wins is explicit: pending local edits win; otherwise the newer updated_at wins. */
    fun synchronize(ownerId: String, readOnly: Boolean) {
        if (ownerId.isBlank() || ownerId.startsWith("local:")) return
        val session = backend.currentSession() ?: return
        if (!readOnly && session.userId != ownerId) throw BackendFailure(BackendFailure.Category.SESSION)
        val linkId = store.prefs.getString("caregiver_link_id", "").orEmpty()
        if (readOnly) {
            if (!canReadSharedOwner(ownerId)) throw BackendFailure(BackendFailure.Category.SESSION)
            // Refresh authorization before uploading cached actions. Network failure preserves the queue.
            val links = backend.linkedPeople()
            val authorized = (0 until links.length()).any { index ->
                links.optJSONObject(index)?.let {
                    it.optString("id") == linkId && it.optString("owner_user_id") == ownerId && it.optString("status") == "active"
                } == true
            }
            if (!authorized) {
                synchronized(AccountStorage.lock) {
                    if (store.prefs.getString("caregiver_link_id", "") == linkId) store.clearRemoteDerivedCaches()
                }
                throw BackendFailure(BackendFailure.Category.SESSION)
            }
        }
        if (!readOnly) {
            // Una fila mala (FK huérfana, CHECK, etc.) no debe bloquear el resto de la cola:
            // se marca su fallo y la corrida continúa con las demás.
            var pushFailure: BackendFailure? = null
            var missingRemoteTable = false
            database.pendingMedications(ownerId).forEach { item ->
                try {
                    backend.upsertMedication(item)
                    database.markMedicationSynced(item.id, item.updatedAtMillis)
                } catch (failure: BackendFailure) {
                    if (failure.status == 404) missingRemoteTable = true
                    else {
                        database.failMedication(item.id)
                        if (pushFailure == null) pushFailure = failure
                    }
                }
            }
            database.pendingIntakes(ownerId).forEach { intake ->
                try {
                    backend.upsertMedicationIntake(intake)
                    database.markIntakeSynced(intake.id, intake.updatedAtMillis)
                } catch (failure: BackendFailure) {
                    if (failure.status == 404) missingRemoteTable = true
                    else {
                        database.failIntake(intake.id)
                        if (pushFailure == null) pushFailure = failure
                    }
                }
            }
            if (missingRemoteTable) {
                android.util.Log.w("MedicationSync", "El proyecto Supabase no expone medications/medication_intakes; aplica la migración 20261024103000.")
                throw BackendFailure(BackendFailure.Category.TEMPORARY, 404)
            }
            pushFailure?.let { throw it }
        }

        var caregiverPushFailure: BackendFailure? = null
        if (readOnly) {
            // El cuidador no crea ni edita medicamentos, pero sí puede confirmar tomas desde su
            // teléfono. Solo empuja las acciones que él mismo registró (actor = su sesión); el RLS
            // de medication_intakes exige actor_user_id = auth.uid() para el cuidador vinculado.
            // Conservamos el fallo hasta terminar la descarga; nunca se informa éxito parcial como total.
            database.pendingIntakes(ownerId).filter { it.actorUserId == session.userId &&
                database.getMedication(ownerId, it.medicationId)?.let { med -> med.shareWithCaregiver && !med.isDeleted } == true
            }.forEach { intake ->
                try {
                    backend.updateSharedMedicationIntake(intake)
                    database.markIntakeSynced(intake.id, intake.updatedAtMillis)
                } catch (failure: BackendFailure) {
                    database.failIntake(intake.id)
                    if (caregiverPushFailure == null) caregiverPushFailure = failure
                }
            }
        }

        RemoteSnapshot.download(
            medications = { RemoteSnapshot.parse(backend.medicationRecords(ownerId), ::parseMedication) },
            intakes = { RemoteSnapshot.parse(backend.medicationIntakeRecords(ownerId), ::parseIntake) },
        ) { medications, intakes ->
            check(medications.all { it.ownerUserId == ownerId } && intakes.all { it.ownerUserId == ownerId }) {
                "La respuesta no corresponde a la persona solicitada."
            }
            synchronized(AccountStorage.lock) {
                if (!AccountStorage.isCurrent(app) || (readOnly && (!canReadSharedOwner(ownerId) ||
                        store.prefs.getString("caregiver_link_id", "") != linkId))) throw BackendFailure(BackendFailure.Category.SESSION)
                database.applyRemoteSnapshot(ownerId, medications, intakes, prune = readOnly)
            }
        }
        caregiverPushFailure?.let { throw it }
        if (database.syncPendingCount(ownerId) > 0) throw BackendFailure(BackendFailure.Category.TEMPORARY)

    }

    fun applyNotificationAction(intakeId: String, status: String): Boolean = acceptIntakeAction(intakeId, status)

    private fun parseMedication(row: JSONObject): Medication? = runCatching {
        val times = row.optJSONArray("schedule_times") ?: JSONArray()
        val timeList = (0 until times.length()).map { times.optString(it).take(5) }.filter(String::isNotBlank)
        val id = row.optString("id")
        val owner = row.optString("owner_user_id")
        if (id.isBlank() || owner.isBlank() || timeList.isEmpty()) return null
        Medication(
            id = id, ownerUserId = owner, name = row.optString("name"), prescribedDose = row.optString("prescribed_dose"),
            presentation = row.optString("presentation"), frequency = row.optString("frequency"), scheduleTimes = timeList,
            startDate = row.optString("start_date"), endDate = row.optString("end_date").takeIf { !row.isNull("end_date") && it.isNotBlank() && it != "null" },
            instructions = row.optString("instructions"), isActive = row.optBoolean("is_active"),
            shareWithCaregiver = row.optBoolean("share_with_caregiver"), isDeleted = row.optBoolean("is_deleted"),
            createdAtMillis = row.optString("created_at").toEpochMillisOrNull() ?: System.currentTimeMillis(),
            updatedAtMillis = row.optString("updated_at").toEpochMillisOrNull() ?: System.currentTimeMillis(), syncState = "synced",
        )
    }.getOrNull()

    private fun parseIntake(row: JSONObject): MedicationIntake? = runCatching {
        val id = row.optString("id")
        val medicationId = row.optString("medication_id")
        val owner = row.optString("owner_user_id")
        if (id.isBlank() || medicationId.isBlank() || owner.isBlank()) return null
        MedicationIntake(
            id = id, medicationId = medicationId, ownerUserId = owner,
            scheduleInstanceId = row.optString("schedule_instance_id"),
            scheduledAtMillis = row.optString("scheduled_at").toEpochMillisOrNull() ?: return null,
            actionAtMillis = row.optString("action_at").toEpochMillisOrNull(), status = row.optString("status"),
            snoozedUntilMillis = row.optString("snoozed_until").toEpochMillisOrNull(), snoozeCount = row.optInt("snooze_count"),
            createdAtMillis = row.optString("created_at").toEpochMillisOrNull() ?: System.currentTimeMillis(),
            updatedAtMillis = row.optString("updated_at").toEpochMillisOrNull() ?: System.currentTimeMillis(), syncState = "synced",
            actorUserId = row.optString("actor_user_id").takeIf { !row.isNull("actor_user_id") && it.isNotBlank() && it != "null" },
        )
    }.getOrNull()

    companion object {
        const val SNOOZE_MS = 10 * 60 * 1000L
        private const val LOCAL_DATA_CLAIM_PREF = "medication_local_data_claimed_by"
    }
}
