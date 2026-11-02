package com.example.guardian_iot

import android.content.SharedPreferences
import org.json.JSONObject
import java.util.UUID

internal data class CaregiverScope(
    val account: String,
    val actor: String,
    val link: String,
    val owner: String,
    val device: String?,
    val generation: String,
) {
    fun matches(row: JSONObject): Boolean = row.optString("id") == link && row.optString("owner_user_id") == owner &&
        row.optString("status") == "active" && row.optString("device_id").takeUnless { it.isBlank() || it == "null" } == device
}

/** Captures the account AND selected relationship for every asynchronous cache write. */
internal class CaregiverAccess(private val store: AppStore) {
    private val prefs get() = store.prefs

    fun initialize() = synchronized(AccountStorage.lock) {
        if (prefs.getInt("caregiver_cache_schema", 0) == 1) return
        invalidateCache()
    }

    fun invalidateCache() = synchronized(AccountStorage.lock) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("caregiver_shared_") || it in setOf("caregiver_location_device_id", "caregiver_device_id") }
            .forEach { editor.remove(it) }
        check(editor.putInt("caregiver_cache_schema", 1).putString("caregiver_selection_generation", UUID.randomUUID().toString()).commit())
    }

    fun snapshot(): CaregiverScope? = synchronized(AccountStorage.lock) {
        val actor = EncryptedSessionStore(store.appContext).read()?.userId ?: return null
        if (!AccountStorage.isCurrent(store.appContext) || store.role() != AppStore.ROLE_CAREGIVER || actor != store.ownerUserId()) return null
        val link = prefs.getString("caregiver_link_id", "").orEmpty()
        val owner = prefs.getString("caregiver_owner_id", "").orEmpty()
        if (link.isBlank() || owner.isBlank() || owner == actor) return null
        CaregiverScope(AccountStorage.key(store.appContext), actor, link, owner,
            prefs.getString("caregiver_link_device_id", "").orEmpty().takeUnless { it.isBlank() || it == "null" },
            prefs.getString("caregiver_selection_generation", "").orEmpty())
    }

    fun isCurrent(scope: CaregiverScope): Boolean = snapshot() == scope

    fun write(scope: CaregiverScope, update: SharedPreferences.Editor.() -> Unit): Boolean = synchronized(AccountStorage.lock) {
        if (!isCurrent(scope)) return false
        prefs.edit().apply(update).putString("caregiver_cache_owner_id", scope.owner)
            .putString("caregiver_cache_link_id", scope.link).commit()
    }

    fun select(row: JSONObject, expectedActor: String): Boolean = synchronized(AccountStorage.lock) {
        if (!AccountStorage.isCurrent(store.appContext) || EncryptedSessionStore(store.appContext).read()?.userId != expectedActor ||
            expectedActor != store.ownerUserId() || store.role() != AppStore.ROLE_CAREGIVER) return false
        val link = row.optString("id")
        val owner = row.optString("owner_user_id")
        if (link.isBlank() || owner.isBlank() || owner == expectedActor ||
            row.has("status") && row.optString("status") != "active") return false
        val editor = clearedEditor()
        check(editor.putString("caregiver_link_id", link).putString("caregiver_owner_id", owner)
            .putString("caregiver_link_device_id", row.optString("device_id").takeUnless { it == "null" }.orEmpty()).commit())
        true
    }

    fun clear(scope: CaregiverScope? = null): Boolean = synchronized(AccountStorage.lock) {
        if (!AccountStorage.isCurrent(store.appContext) || scope != null && !isCurrent(scope)) return false
        clearedEditor().commit()
    }

    private fun clearedEditor(): SharedPreferences.Editor = prefs.edit().apply {
        prefs.all.keys.filter { it.startsWith("caregiver_") || it.startsWith("pending_pairing_") }.forEach { remove(it) }
        putInt("caregiver_cache_schema", 1)
        putString("caregiver_selection_generation", UUID.randomUUID().toString())
    }
}
