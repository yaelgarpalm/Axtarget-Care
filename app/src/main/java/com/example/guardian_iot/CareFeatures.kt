package com.example.guardian_iot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.*

internal data class UnassignedCareEntry(
    val id: String,
    val kind: String,
    val deletion: Boolean,
    val summary: String,
    val changedAt: Long,
)

/** Offline-first records for shared care notes, safe zones and user-authored routines. */
class CareFeatureStore(context: Context) {
    private val app = AccountStorage.capture(context)
    private val prefs = app.getSharedPreferences("tzinda_care_features", Context.MODE_PRIVATE)
    private val accountPrefs = app.getSharedPreferences("tzinda_native", Context.MODE_PRIVATE)
    companion object { private val lock = Any() }

    private fun localOwner(): String = prefs.getString("local_owner_scope", "").orEmpty().ifBlank {
        "local:${UUID.randomUUID()}".also { check(prefs.edit().putString("local_owner_scope", it).commit()) }
    }
    fun currentOwner(): String? = synchronized(lock) {
        when (accountPrefs.getString("user_role", "")) {
            AppStore.ROLE_CAMERA -> null
            AppStore.ROLE_CAREGIVER -> if (accountPrefs.getString("caregiver_link_id", "").isNullOrBlank()) null
                else accountPrefs.getString("caregiver_owner_id", "").orEmpty().takeIf(String::isNotBlank)
            else -> accountPrefs.getString("local_data_owner_id", "").orEmpty().ifBlank { localOwner() }
        }
    }
    fun rows(kind: String): List<JSONObject> = synchronized(lock) {
        val owner = currentOwner() ?: return emptyList()
        rowsAll().filter { it.optString("kind") == kind && it.optString("ownerUserId") == owner }
            .sortedByDescending { it.optLong("updatedAt") }
    }
    fun upsert(kind: String, values: JSONObject): JSONObject = synchronized(lock) {
        val owner = currentOwner() ?: error("Selecciona una persona vinculada antes de guardar cuidados.")
        val id = values.optString("id").takeIf { runCatching { UUID.fromString(it) }.isSuccess } ?: UUID.randomUUID().toString()
        val current = rowsAll()
        val existing = current.firstOrNull { it.optString("id") == id }
        check(existing == null || existing.optString("ownerUserId").isNotBlank()) { "Sincroniza para confirmar el propietario del registro antiguo." }
        val priorDeletion = pendingDeleteRecords().firstOrNull { it.optString("id") == id }?.optLong("deletedAt") ?: 0L
        val requestedOwner = values.optString("ownerUserId").ifBlank { existing?.optString("ownerUserId").orEmpty().ifBlank { owner } }
        check(requestedOwner == owner && (existing == null || existing.optString("ownerUserId").ifBlank { requestedOwner } == owner)) {
            "El registro pertenece a otra persona. Vuelve a abrir sus cuidados."
        }
        val row = JSONObject(values.toString()).put("id", id).put("kind", kind).put("ownerUserId", owner)
            .put("actorUserId", accountPrefs.getString("local_data_owner_id", "").orEmpty().ifBlank { localOwner() })
            .put("updatedAt", maxOf(System.currentTimeMillis(), (existing?.optLong("updatedAt") ?: 0L) + 1, priorDeletion + 1)).put("syncState", "pending")
        val next = JSONArray()
        current.filterNot { it.optString("id") == id }.forEach { next.put(it) }
        next.put(row)
        val deletes = JSONArray().apply { pendingDeleteRecords().filterNot { it.optString("id") == id }.forEach { put(it) } }
        check(prefs.edit().putString("records", next.toString()).putString("deleted", deletes.toString()).commit())
        row
    }
    fun rowsAll(): List<JSONObject> = synchronized(lock) {
        val all = runCatching { JSONArray(prefs.getString("records", "[]")) }.getOrDefault(JSONArray())
        (0 until all.length()).mapNotNull { all.optJSONObject(it) }
    }
    fun delete(kind: String, id: String, ownerId: String? = null) = synchronized(lock) {
        val existing = rowsAll().firstOrNull { it.optString("id") == id && it.optString("kind") == kind }
        val owner = existing?.optString("ownerUserId")?.takeIf(String::isNotBlank) ?: ownerId.orEmpty()
        check(owner.isNotBlank() && owner == currentOwner()) { "No se pudo confirmar a quién pertenece el registro." }
        val deletes = pendingDeleteRecords()
        val oldVersion = deletes.firstOrNull { it.optString("id") == id }?.optLong("deletedAt") ?: 0L
        val deleted = JSONArray().apply {
            deletes.filterNot { it.optString("id") == id }.forEach { put(it) }
            put(JSONObject().put("id", id).put("kind", kind).put("ownerUserId", owner)
                .put("deletedAt", maxOf(System.currentTimeMillis(), oldVersion + 1, (existing?.optLong("updatedAt") ?: 0L) + 1)))
        }
        val next = JSONArray().apply { rowsAll().filterNot { it.optString("id") == id && it.optString("kind") == kind }.forEach { put(it) } }
        check(prefs.edit().putString("records", next.toString()).putString("deleted", deleted.toString()).commit())
    }
    fun pendingDeleteRecords(): List<JSONObject> = synchronized(lock) {
        val deleted = runCatching { JSONArray(prefs.getString("deleted", "[]")) }.getOrDefault(JSONArray())
        (0 until deleted.length()).mapNotNull { index ->
            deleted.optJSONObject(index) ?: deleted.optString(index).takeIf(String::isNotBlank)?.let {
                JSONObject().put("id", it).put("ownerUserId", "").put("deletedAt", 0)
            }
        }
    }
    fun pendingDeletes(): List<String> = pendingDeleteRecords().map { it.optString("id") }
    fun markDeletedSynced(id: String, ownerId: String, deletedAt: Long) = synchronized(lock) {
        val next = JSONArray().apply { pendingDeleteRecords().filterNot {
            it.optString("id") == id && it.optString("ownerUserId") == ownerId && it.optLong("deletedAt") == deletedAt
        }.forEach { put(it) } }
        check(prefs.edit().putString("deleted", next.toString()).commit())
    }
    /** A complete owner snapshot can confirm that a retried delete already succeeded remotely. */
    internal fun acknowledgeDeletesAbsentFromSnapshot(remote: List<JSONObject>, ownerId: String): Int = synchronized(lock) {
        check(ownerId.isNotBlank() && remote.all {
            it.optString("owner_user_id") == ownerId && runCatching { UUID.fromString(it.optString("id")) }.isSuccess
        }) { "No se puede confirmar un borrado con un snapshot inválido o de otra persona." }
        val remoteIds = remote.map { it.optString("id") }.toSet()
        val pending = pendingDeleteRecords()
        val resolved = pending.filter {
            it.optString("ownerUserId") == ownerId && it.optString("kind") != "alert_followup" &&
                it.optString("id") !in remoteIds
        }
        if (resolved.isEmpty()) return@synchronized 0
        val resolvedVersions = resolved.map {
            Triple(it.optString("id"), it.optString("ownerUserId"), it.optLong("deletedAt"))
        }.toSet()
        val next = JSONArray().apply { pending.filterNot {
            Triple(it.optString("id"), it.optString("ownerUserId"), it.optLong("deletedAt")) in resolvedVersions
        }.forEach { put(it) } }
        check(prefs.edit().putString("deleted", next.toString()).commit())
        resolved.size
    }
    fun markSynced(id: String, uploadedAt: Long) = synchronized(lock) {
        val next = JSONArray()
        rowsAll().forEach { next.put(if (it.optString("id") == id && it.optLong("updatedAt") == uploadedAt) it.put("syncState", "synced") else it) }
        check(prefs.edit().putString("records", next.toString()).commit())
    }
    fun claimLocalData(userId: String) = synchronized(lock) {
        check(accountPrefs.getString("local_data_owner_id", "") == userId)
        val local = localOwner()
        fun adopt(row: JSONObject): JSONObject {
            if (row.optString("ownerUserId") == local) row.put("ownerUserId", userId)
            if (row.optString("actorUserId") == local) row.put("actorUserId", userId)
            return row
        }
        val rows = JSONArray().apply { rowsAll().forEach { put(adopt(it)) } }
        val deletes = JSONArray().apply { pendingDeleteRecords().forEach { put(adopt(it)) } }
        check(prefs.edit().putString("records", rows.toString()).putString("deleted", deletes.toString()).commit())
    }
    /** Only server-confirmed ID/owner pairs can assign ownership to legacy entries. */
    fun reconcileOwners(remote: List<JSONObject>, ownerId: String) = synchronized(lock) {
        check(remote.all { it.optString("owner_user_id") == ownerId && it.optString("id").isNotBlank() })
        val ids = remote.map { it.optString("id") }.toSet()
        fun reconcile(row: JSONObject): JSONObject {
            if (row.optString("ownerUserId").isBlank() && row.optString("id") in ids) row.put("ownerUserId", ownerId)
            return row
        }
        val rows = JSONArray().apply { rowsAll().forEach { put(reconcile(it)) } }
        val deletes = JSONArray().apply { pendingDeleteRecords().forEach { put(reconcile(it)) } }
        check(prefs.edit().putString("records", rows.toString()).putString("deleted", deletes.toString()).commit())
    }

    /** Restores an authoritative remote snapshot without deleting or replacing local pending work. */
    internal fun mergeRemoteSnapshot(remote: List<JSONObject>, ownerId: String) = synchronized(lock) {
        check(ownerId.isNotBlank()) { "Falta el propietario del snapshot remoto." }
        val remoteRecords = remote.map { row ->
            val id = row.optString("id")
            val kind = row.optString("record_kind")
            val declaredOwner = row.optString("owner_user_id")
            val payload = row.optJSONObject("record")
            check(id.isNotBlank() && kind.isNotBlank() && declaredOwner == ownerId && payload != null) {
                "El snapshot remoto contiene una fila incompleta o de otra persona."
            }
            check(runCatching { UUID.fromString(id) }.isSuccess) { "ID remoto de cuidado inválido." }
            val payloadId = payload.optString("id").takeUnless { it == "null" }.orEmpty()
            val payloadKind = payload.optString("kind").takeUnless { it == "null" }.orEmpty()
            val payloadOwner = payload.optString("ownerUserId").takeUnless { it == "null" }.orEmpty()
            check(payloadId.isBlank() || payloadId == id) { "ID de registro remoto inconsistente." }
            check(payloadKind.isBlank() || payloadKind == kind) { "Tipo de registro remoto inconsistente." }
            check(payloadOwner.isBlank() || payloadOwner == ownerId) {
                "El contenido remoto declara otro propietario."
            }
            val timestamp = runCatching { java.time.Instant.parse(row.optString("updated_at")).toEpochMilli() }.getOrDefault(0L)
            val record = JSONObject(payload.toString())
                .put("id", id).put("kind", kind).put("ownerUserId", ownerId)
                .put("updatedAt", maxOf(payload.optLong("updatedAt"), timestamp))
                .put("syncState", "synced")
            check(record.optLong("updatedAt") > 0L) { "El snapshot remoto no tiene una versión válida." }
            id to record
        }
        check(remoteRecords.map { it.first }.distinct().size == remoteRecords.size) { "El snapshot remoto repite IDs." }

        val current = rowsAll()
        val localById = current.associateBy { it.optString("id") }
        remoteRecords.forEach { (id, _) ->
            val localOwner = localById[id]?.optString("ownerUserId").orEmpty()
            check(localOwner.isBlank() || localOwner == ownerId) { "El ID remoto ya pertenece a otra persona en este teléfono." }
        }
        val merged = current.toMutableList()
        remoteRecords.forEach { (id, remoteRecord) ->
            val index = merged.indexOfFirst { it.optString("id") == id }
            if (index < 0) {
                merged += remoteRecord
            } else {
                val local = merged[index]
                if (local.optString("syncState") == "synced" && remoteRecord.optLong("updatedAt") > local.optLong("updatedAt")) {
                    if (remoteRecord.optString("actorUserId").isBlank() && local.optString("actorUserId").isNotBlank())
                        remoteRecord.put("actorUserId", local.optString("actorUserId"))
                    merged[index] = remoteRecord
                }
            }
        }
        val saved = JSONArray().apply { merged.forEach(::put) }
        check(prefs.edit().putString("records", saved.toString()).commit())
    }

    /** Restores append-only alert history using the same validated merge as care_records. */
    internal fun mergeRemoteFollowups(remote: List<JSONObject>, ownerId: String) = synchronized(lock) {
        val normalized = remote.map { row ->
            val id = row.optString("id").takeUnless { it == "null" }.orEmpty()
            val declaredOwner = row.optString("owner_user_id").takeUnless { it == "null" }.orEmpty()
            val eventId = row.optString("fall_event_id").takeUnless { it == "null" }.orEmpty()
            val actorId = row.optString("actor_user_id").takeUnless { it == "null" }.orEmpty()
            val status = row.optString("status").takeUnless { it == "null" }.orEmpty()
            val createdAt = row.optString("created_at").takeUnless { it == "null" }.orEmpty()
            val timestamp = runCatching { java.time.Instant.parse(createdAt).toEpochMilli() }.getOrDefault(0L)
            check(declaredOwner == ownerId && id.isNotBlank() && eventId.isNotBlank() && actorId.isNotBlank() &&
                status in setOf("confirmed", "attended", "dismissed", "closed") && timestamp > 0L) {
                "El snapshot remoto contiene un seguimiento incompleto o de otra persona."
            }
            check(runCatching { UUID.fromString(id) }.isSuccess && runCatching { UUID.fromString(eventId) }.isSuccess &&
                runCatching { UUID.fromString(actorId) }.isSuccess) { "El snapshot contiene identificadores inválidos." }
            val payload = JSONObject().put("id", id).put("kind", "alert_followup").put("ownerUserId", ownerId)
                .put("actorUserId", actorId).put("fallEventId", eventId).put("status", status)
                .put("reason", row.optString("reason").takeUnless { it == "null" }.orEmpty())
                .put("occurredAt", timestamp)
            JSONObject().put("id", id).put("owner_user_id", ownerId).put("record_kind", "alert_followup")
                .put("record", payload).put("updated_at", createdAt)
        }
        check(normalized.map { it.optString("id") }.distinct().size == normalized.size) {
            "El snapshot remoto repite IDs de seguimientos."
        }
        mergeRemoteSnapshot(normalized, ownerId)
    }

    fun unassignedCount(): Int = synchronized(lock) {
        rowsAll().count { it.optString("ownerUserId").isBlank() } + pendingDeleteRecords().count { it.optString("ownerUserId").isBlank() }
    }
    internal fun unassignedEntries(): List<UnassignedCareEntry> = synchronized(lock) {
        val records = rowsAll().filter { it.optString("ownerUserId").isBlank() }.map { row ->
            UnassignedCareEntry(row.optString("id"), row.optString("kind"), false,
                row.optString("name").ifBlank { row.optString("description").ifBlank { row.optString("type").ifBlank { "Registro local" } } },
                row.optLong("updatedAt"))
        }
        val deletions = pendingDeleteRecords().filter { it.optString("ownerUserId").isBlank() }.map { row ->
            UnassignedCareEntry(row.optString("id"), row.optString("kind"), true,
                "Eliminación pendiente${row.optString("kind").takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}",
                row.optLong("deletedAt"))
        }
        (records + deletions).sortedWith(compareBy<UnassignedCareEntry> { it.deletion }.thenByDescending { it.changedAt })
    }

    /** Claims one item only after an explicit user choice in wearer mode; never guesses ownership. */
    internal fun claimUnassignedEntry(entry: UnassignedCareEntry, ownerId: String, actorId: String): Boolean = synchronized(lock) {
        if (runCatching { UUID.fromString(entry.id) }.isFailure) return false
        check(ownerId.isNotBlank() && ownerId == actorId && currentOwner() == ownerId &&
            accountPrefs.getString("local_data_owner_id", "") == ownerId &&
            accountPrefs.getString("user_role", "") == AppStore.ROLE_WEARER) {
            "Los registros antiguos solo se pueden asignar explícitamente desde la cuenta propia en modo persona protegida."
        }
        if (entry.deletion) {
            var found = false
            val next = JSONArray().apply {
                pendingDeleteRecords().forEach { row ->
                    if (row.optString("id") == entry.id && row.optString("ownerUserId").isBlank() &&
                        (entry.kind.isBlank() || row.optString("kind").isBlank() || row.optString("kind") == entry.kind)) {
                        row.put("ownerUserId", ownerId).put("actorUserId", actorId)
                        if (entry.kind.isNotBlank()) row.put("kind", entry.kind)
                        found = true
                    }
                    put(row)
                }
            }
            if (!found) return false
            check(prefs.edit().putString("deleted", next.toString()).commit())
            return true
        }
        var found = false
        val next = JSONArray().apply {
            rowsAll().forEach { row ->
                if (row.optString("id") == entry.id && row.optString("kind") == entry.kind && row.optString("ownerUserId").isBlank()) {
                    row.put("ownerUserId", ownerId).put("actorUserId", actorId)
                        .put("updatedAt", maxOf(System.currentTimeMillis(), row.optLong("updatedAt") + 1))
                        .put("syncState", "pending")
                    found = true
                }
                put(row)
            }
        }
        if (!found) return false
        check(prefs.edit().putString("records", next.toString()).commit())
        true
    }
    fun clearAll() = synchronized(lock) {
        prefs.edit().remove("records").remove("deleted").remove("zone_runtime").commit()
    }
    fun syncState(): SyncStateSummary = synchronized(lock) {
        val pending = rowsAll().count { it.optString("ownerUserId").isNotBlank() && it.optString("syncState") != "synced" } +
            pendingDeleteRecords().count { it.optString("ownerUserId").isNotBlank() }
        SyncStateSummary(pending, unassignedCount())
    }

    fun zones(): List<JSONObject> = synchronized(lock) {
        val runtime = JSONObject(prefs.getString("zone_runtime", "{}").orEmpty())
        rows("zone").map { row ->
            runtime.optJSONObject(row.optString("id"))?.let { state ->
                state.keys().forEach { key -> row.put(key, state.get(key)) }
            }
            row
        }
    }

    private fun saveZoneRuntime(id: String, state: JSONObject) = synchronized(lock) {
        val runtime = JSONObject(prefs.getString("zone_runtime", "{}").orEmpty())
        runtime.put(id, state)
        check(prefs.edit().putString("zone_runtime", runtime.toString()).commit())
    }
    /** Only valid, reasonably accurate fixes produce a transition; first fix initializes state. */
    fun onLocation(latitude: Double, longitude: Double, accuracyM: Float, capturedAt: Long): List<JSONObject> {
        if (accountPrefs.getString("user_role", "") in setOf(AppStore.ROLE_CAREGIVER, AppStore.ROLE_CAMERA)) return emptyList()
        if (!GeoFencePolicy.validFix(latitude, longitude, accuracyM, capturedAt)) return emptyList()
        val events = mutableListOf<JSONObject>()
        zones().filter { it.optBoolean("enabled", true) }.forEach { zone ->
            val distance = GeoFencePolicy.distanceMeters(latitude, longitude, zone.optDouble("latitude"), zone.optDouble("longitude"))
            val previous = zone.optString("lastInside").takeIf { it == "true" || it == "false" }?.toBoolean()
            val next = GeoFencePolicy.classify(distance, zone.optDouble("radius", 150.0), accuracyM.toDouble(), previous) ?: return@forEach
            if (previous != null && previous != next) {
                events += upsert("journal", JSONObject().put("type", "zona_${if (next) "entrada" else "salida"}")
                    .put("description", "${if (next) "Entrada a" else "Salida de"} ${zone.optString("name")}")
                    .put("occurredAt", capturedAt).put("source", "location")
                    .put("sharedWithCaregiver", zone.optBoolean("sharedWithCaregiver", false))
                    .put("ownerUserId", zone.optString("ownerUserId")))
            }
            val lastPersisted = zone.optLong("lastLocationAt")
            if (previous == next && lastPersisted > 0 && capturedAt - lastPersisted < 5 * 60_000) return@forEach
            saveZoneRuntime(zone.optString("id"), JSONObject().put("lastInside", next).put("lastLocationAt", capturedAt)
                .put("lastLatitude", latitude).put("lastLongitude", longitude))
        }
        return events
    }
}

object GeoFencePolicy {
    fun validFix(lat: Double, lon: Double, accuracyM: Float, at: Long, now: Long = System.currentTimeMillis()) =
        lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0 &&
            accuracyM.isFinite() && accuracyM in 0f..50f && at > 0 && at <= now + 30_000 && now - at < 5 * 60_000
    fun classify(distance: Double, radius: Double, accuracy: Double, previous: Boolean?): Boolean? {
        if (!distance.isFinite() || !radius.isFinite() || radius !in 25.0..20_000.0 || accuracy !in 0.0..50.0) return null
        val margin = max(20.0, accuracy)
        return when {
            distance < radius - margin -> true
            distance > radius + margin -> false
            else -> previous
        }
    }
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0; val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}

enum class DeviceConnectionState { REGISTERED, CONNECTED, DISCONNECTED, SYNCING, NO_RECENT_DATA, ERROR, UNKNOWN }
object DeviceStatusPolicy {
    fun state(registered: Boolean, liveConnected: Boolean?, lastDataAt: Long?, now: Long, syncing: Boolean = false, error: Boolean = false): DeviceConnectionState {
        if (error) return DeviceConnectionState.ERROR
        if (!registered) return DeviceConnectionState.UNKNOWN
        if (syncing) return DeviceConnectionState.SYNCING
        if (liveConnected == true) return DeviceConnectionState.CONNECTED
        if (liveConnected == false) return DeviceConnectionState.DISCONNECTED
        if (lastDataAt == null || lastDataAt <= 0) return DeviceConnectionState.REGISTERED
        if (now - lastDataAt > 24 * 60 * 60 * 1000L) return DeviceConnectionState.NO_RECENT_DATA
        return DeviceConnectionState.UNKNOWN
    }
}

object RoutinePolicy {
    fun dueToday(days: Set<Int>, minuteOfDay: Int, nowMinute: Int): Boolean =
        days.isNotEmpty() && minuteOfDay in 0..1439 && nowMinute in 0..1439 &&
            days.contains(java.time.LocalDate.now().dayOfWeek.value) && minuteOfDay <= nowMinute
    fun inactivityNotice(sourceAvailable: Boolean, optedIn: Boolean, lastUserActivityAt: Long?, now: Long, thresholdMs: Long): Boolean =
        sourceAvailable && optedIn && lastUserActivityAt != null && thresholdMs > 0 && now - lastUserActivityAt >= thresholdMs
}
