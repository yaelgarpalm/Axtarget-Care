package com.example.guardian_iot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.*

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
    fun unassignedCount(): Int = synchronized(lock) {
        rowsAll().count { it.optString("ownerUserId").isBlank() } + pendingDeleteRecords().count { it.optString("ownerUserId").isBlank() }
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
