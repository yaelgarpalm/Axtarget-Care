package com.example.guardian_iot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.*

/** Offline-first records for shared care notes, safe zones and user-authored routines. */
class CareFeatureStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tzinda_care_features", Context.MODE_PRIVATE)
    private val lock = Any()
    fun rows(kind: String): List<JSONObject> = synchronized(lock) {
        val all = runCatching { JSONArray(prefs.getString("records", "[]")) }.getOrDefault(JSONArray())
        (0 until all.length()).mapNotNull { all.optJSONObject(it) }.filter { it.optString("kind") == kind }
            .sortedByDescending { it.optLong("updatedAt") }
    }
    fun upsert(kind: String, values: JSONObject): JSONObject = synchronized(lock) {
        val id = values.optString("id").takeIf { runCatching { UUID.fromString(it) }.isSuccess } ?: UUID.randomUUID().toString()
        val current = rowsAll()
        val row = JSONObject(values.toString()).put("id", id).put("kind", kind)
            .put("updatedAt", System.currentTimeMillis()).put("syncState", "pending")
        val next = JSONArray()
        current.filterNot { it.optString("id") == id }.forEach { next.put(it) }
        next.put(row)
        check(prefs.edit().putString("records", next.toString()).commit()) { "No se pudo guardar el registro local." }
        row
    }
    fun rowsAll(): List<JSONObject> {
        val all = runCatching { JSONArray(prefs.getString("records", "[]")) }.getOrDefault(JSONArray())
        return (0 until all.length()).mapNotNull { all.optJSONObject(it) }
    }
    fun delete(kind: String, id: String) = synchronized(lock) {
        val next = JSONArray()
        rowsAll().filterNot { it.optString("id") == id && it.optString("kind") == kind }.forEach { next.put(it) }
        val deleted = runCatching { JSONArray(prefs.getString("deleted", "[]")) }.getOrDefault(JSONArray())
        if ((0 until deleted.length()).none { deleted.optString(it) == id }) deleted.put(id)
        check(prefs.edit().putString("records", next.toString()).putString("deleted", deleted.toString()).commit())
    }
    fun pendingDeletes(): List<String> {
        val deleted = runCatching { JSONArray(prefs.getString("deleted", "[]")) }.getOrDefault(JSONArray())
        return (0 until deleted.length()).map { deleted.optString(it) }.filter { it.isNotBlank() }
    }
    fun markDeletedSynced(id: String) = synchronized(lock) {
        val next = JSONArray(); pendingDeletes().filterNot { it == id }.forEach { next.put(it) }
        prefs.edit().putString("deleted", next.toString()).apply()
    }
    fun markSynced(id: String) = synchronized(lock) {
        val next = JSONArray()
        rowsAll().forEach { next.put(if (it.optString("id") == id) it.put("syncState", "synced") else it) }
        prefs.edit().putString("records", next.toString()).apply()
    }
    fun clearAll() = synchronized(lock) {
        prefs.edit().remove("records").remove("deleted").commit()
    }
    fun zones(): List<JSONObject> = rows("zone")
    /** Only valid, reasonably accurate fixes produce a transition; first fix initializes state. */
    fun onLocation(latitude: Double, longitude: Double, accuracyM: Float, capturedAt: Long): List<JSONObject> {
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
            upsert("zone", JSONObject(zone.toString()).put("lastInside", next).put("lastLocationAt", capturedAt)
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
