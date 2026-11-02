package com.example.guardian_iot

import org.json.JSONObject

internal data class ProfileSyncSnapshot(
    val name: String, val version: Long, val acknowledgedVersion: Long,
    val privacyNotice: String, val consentAt: Long,
)

/** Device mode controls local navigation; it is never an account authorization claim. */
internal object ProfileSyncPolicy {
    enum class Action { PULL, PUSH, NONE }

    fun payload(fullName: String, preferences: JSONObject, userId: String? = null): JSONObject =
        JSONObject().put("full_name", fullName.take(120)).put("preferences", JSONObject(preferences.toString()))
            .also { if (userId != null) it.put("user_id", userId) }

    fun action(localName: String, localVersion: Long, acknowledgedVersion: Long, remoteExists: Boolean, remoteVersion: Long): Action {
        if (localName.isBlank()) return if (remoteExists) Action.PULL else Action.NONE
        if (!remoteExists || localVersion > acknowledgedVersion) return Action.PUSH
        return if (remoteVersion > localVersion) Action.PULL else Action.NONE
    }
}
