package com.example.guardian_iot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

class AppStore(context: Context) {
    internal val appContext = AccountStorage.capture(context)
    val prefs = appContext.getSharedPreferences("tzinda_native", Context.MODE_PRIVATE)
    val telemetry = LocalTelemetryDatabase.getInstance(appContext)
    init {
        migrate(appContext)
        CaregiverAccess(this).initialize()
        telemetry.ensureContacts(contacts().map { LocalContactRecord(it.id, it.name, it.phone) })
        if (prefs.getLong("profile_updated_at", 0L) == 0L && profile().text("fullName").isNotBlank()) {
            prefs.edit().putLong("profile_updated_at", System.currentTimeMillis()).apply()
        }
    }
    private fun migrate(context: Context) {
        if (prefs.getBoolean("migration_done", false)) return
        val flutter = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val monitor = context.getSharedPreferences("guardian_monitor_settings", Context.MODE_PRIVATE)
        val contacts = flutter.getString("flutter.trusted_contacts", null)
            ?: monitor.getString("contactsJson", null)
            ?: JSONArray().apply {
                val phone = flutter.getString("flutter.contact_phone", "").orEmpty()
                if (PhoneNumbers.normalize(phone) != null) put(JSONObject().put("name", flutter.getString("flutter.contact_name", "")).put("phone", phone))
            }.toString()
        val profile = flutter.getString("flutter.person_profile", "{}").orEmpty()
        val consent = runCatching { JSONObject(profile).optBoolean("locationConsent", false) }.getOrDefault(false)
        check(prefs.edit().putString("contacts", contacts).putString("profile", profile)
            .putBoolean("dark_mode", flutter.getBoolean("flutter.dark_mode", false))
            .putBoolean("include_location", consent)
            .putBoolean("monitor_enabled", monitor.getBoolean("monitor_enabled", false))
            .putBoolean("calls_enabled", true).putBoolean("migration_done", true).commit())
    }
    fun contacts(): List<TrustedContact> = runCatching {
        val array = JSONArray(prefs.getString("contacts", "[]"))
        var addedIds = false
        val rows = (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val phone = PhoneNumbers.normalize(item.optString("phone")) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { runCatching { java.util.UUID.fromString(it) }.isSuccess }
                ?: java.util.UUID.randomUUID().toString().also { addedIds = true }
            TrustedContact(item.text("name"), phone, id)
        }.distinctBy { PhoneNumbers.key(it.phone) }.take(5)
        if (addedIds) {
            val migrated = JSONArray().apply { rows.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("phone", it.phone)) } }
            prefs.edit().putString("contacts", migrated.toString()).commit()
        }
        rows
    }.getOrDefault(emptyList())
    fun saveContacts(rows: List<TrustedContact>): List<TrustedContact> {
        val previousIds = contacts().associateBy { PhoneNumbers.key(it.phone) }
        val list = PhoneNumbers.validateRows(rows).map { row ->
            row.copy(id = previousIds[PhoneNumbers.key(row.phone)]?.id ?: row.id)
        }
        val json = JSONArray().apply { list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("phone", it.phone)) } }
        check(prefs.edit().putString("contacts", json.toString()).commit()) { "No se pudieron guardar los contactos." }
        telemetry.replaceContacts(list.map { LocalContactRecord(it.id, it.name, it.phone) })
        SyncScheduler.enqueue(appContext)
        return list
    }
    fun profile(): JSONObject = runCatching { JSONObject(prefs.getString("profile", "{}").orEmpty()) }.getOrDefault(JSONObject())
    /** Installation mode, retained independently from profiles.role on other phones. */
    fun role(): String = prefs.getString("user_role", "").orEmpty()
    fun accountRole(): String = prefs.getString("account_role", "").orEmpty()
    fun arduinoBaseUrl(): String = prefs.getString("arduino_base_url", DEFAULT_ARDUINO_BASE_URL).orEmpty()
    internal fun arduinoConnectionSettings(): ArduinoConnectionSettings {
        val snapshot = prefs.all
        return ArduinoConnectionSettings(
            baseUrl = snapshot[ARDUINO_BASE_URL_KEY] as? String ?: DEFAULT_ARDUINO_BASE_URL,
            certificateFingerprint = snapshot[ARDUINO_CERTIFICATE_FINGERPRINT_KEY] as? String ?: "",
            token = UnoQLocalAuth.tokenFromSnapshot(appContext, snapshot),
        )
    }
    fun braceletConnectionMode(): String = prefs.getString("bracelet_connection_mode", BRACELET_MODE_BLE).orEmpty()
    fun saveBraceletConnectionMode(value: String) {
        require(value in setOf(BRACELET_MODE_BLE, BRACELET_MODE_WIFI)) { "Selecciona Bluetooth o Wi-Fi para la pulsera." }
        check(prefs.edit().putString("bracelet_connection_mode", value).commit()) { "No se pudo guardar el método de conexión." }
    }
    fun saveArduinoConnectionSettings(value: String, fingerprint: String, token: String) {
        val cleaned = value.trim().trimEnd('/')
        val uri = runCatching { URI(cleaned) }.getOrNull()
        require(uri != null && uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path in setOf("", "/")) {
            "Escribe una dirección HTTPS válida del UNO Q, por ejemplo https://192.168.18.35:8081."
        }
        val normalizedFingerprint = ArduinoHttps.normalizeFingerprint(fingerprint)
        require(UnoQLocalAuth.isValidToken(token)) { "El token debe tener al menos 32 bytes y no contener espacios." }
        val editor = prefs.edit()
            .putString(ARDUINO_BASE_URL_KEY, cleaned)
            .putString(ARDUINO_CERTIFICATE_FINGERPRINT_KEY, normalizedFingerprint)
        UnoQLocalAuth.putEncryptedToken(editor, appContext, token)
        check(editor.commit()) { "No se pudo guardar la configuración segura del UNO Q." }
    }
    fun saveRole(role: String, beginTutorial: Boolean = false) {
        require(role in setOf(ROLE_WEARER, ROLE_CAREGIVER, ROLE_CAMERA)) { "Selecciona un rol válido." }
        val changed = role() != role
        val editor = prefs.edit().putString("user_role", role).putLong("role_updated_at", System.currentTimeMillis())
        if (beginTutorial) editor.putInt(FIRST_TUTORIAL_STEP, 0)
        check(editor.commit()) { "No se pudo guardar el modo de este teléfono." }
        if (changed) CaregiverAccess(this).invalidateCache()
        LocationSharing(this).reconcile()
        SyncScheduler.enqueue(appContext)
    }
    fun saveProfile(profile: JSONObject) = synchronized(lock) {
        require(profile.optString("fullName").isNotBlank()) { "Escribe el nombre completo." }
        require(profile.optBoolean("dataConsent")) { "Autoriza el almacenamiento de los datos del perfil." }
        check(prefs.edit().putString("profile", profile.toString()).putLong("profile_updated_at", maxOf(System.currentTimeMillis(), profileUpdatedAt() + 1))
            .putBoolean("include_location", profile.optBoolean("locationConsent")).commit()) { "No se pudo guardar el perfil." }
        LocationSharing(this).reconcile()
        SyncScheduler.enqueue(appContext)
    }
    fun recipients(): List<TrustedContact> {
        val profile = profile()
        val phone = PhoneNumbers.normalize(profile.optString("emergencyContactPhone"))
        val emergency = phone?.let { TrustedContact(profile.text("emergencyContactName"), it) }
        return PhoneNumbers.recipients(contacts(), emergency)
    }
    fun events(): JSONArray = runCatching { JSONArray(prefs.getString("events", "[]")) }.getOrDefault(JSONArray())
    fun eventHistory(limit: Int = 100, offset: Int = 0): List<JSONObject> {
        val stored = telemetry.recentAppEvents(limit, offset)
        if (stored.isNotEmpty() || offset > 0) return stored
        val legacy = events()
        return (offset until minOf(legacy.length(), offset + limit)).mapNotNull { legacy.optJSONObject(it) }
    }
    fun eventHistoryForIncident(incidentId: String): List<JSONObject> {
        if (incidentId.isBlank()) return emptyList()
        return telemetry.appEventsForIncident(incidentId).ifEmpty {
            val legacy = events()
            (0 until legacy.length()).mapNotNull { legacy.optJSONObject(it) }
                .filter { it.optString("incident") == incidentId }
        }
    }

    fun eventHistoryCount(): Int = maxOf(telemetry.appEventCount(), events().length())
    fun possibleFallCountSince(capturedAt: Long): Int {
        val stored = telemetry.possibleFallCountSince(capturedAt)
        if (stored > 0) return stored
        val legacy = events()
        return (0 until legacy.length()).mapNotNull { legacy.optJSONObject(it) }
            .filter { it.optLong("time") >= capturedAt && it.optString("message").contains("caída", ignoreCase = true) }
            .distinctBy { it.optString("incident").ifBlank { it.optLong("time").toString() } }
            .size
    }
    fun event(message: String, incident: String = "", queueFallEvent: Boolean = true) {
        synchronized(lock) {
            val old = events()
            val eventId = java.util.UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val next = JSONArray().put(JSONObject().put("id", eventId).put("time", now).put("message", message).put("incident", incident))
            for (i in 0 until minOf(old.length(), 99)) next.put(old.get(i))
            prefs.edit().putString("events", next.toString()).putString("status", message).commit()
            runCatching { telemetry.recordEvent(message, incident, now, eventId) }
            val incidentId = incident.takeIf { runCatching { java.util.UUID.fromString(it) }.isSuccess }
            val eventInfo = if (queueFallEvent) classifyFallEvent(message, eventId, incidentId, now) else null
            if (eventInfo != null) runCatching { telemetry.queueFallEvent(eventInfo) }
            if (incidentId != null) classifyNotificationAttempt(message, eventId, incidentId, now)?.let { attempt ->
                runCatching { telemetry.queueNotificationAttempt(attempt) }
            }
            if (eventInfo != null || incidentId != null && isNotificationEvent(message)) SyncScheduler.enqueue(appContext)
        }
    }

    fun localDeviceId(): String {
        prefs.getString("local_device_id", null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        if (prefs.edit().putString("local_device_id", id).commit()) return id
        return prefs.getString("local_device_id", id) ?: id
    }

    fun arduinoDeviceId(): String = stableDeviceId("arduino_device_id")
    fun fallSensorDeviceId(): String = stableDeviceId("fall_sensor_device_id")
    fun wearableDeviceId(): String = stableDeviceId("wearable_device_id")
    fun cameraDeviceId(): String = stableDeviceId("local_camera_device_id")
    fun cameraEventDeviceId(): String = prefs.getString("paired_camera_device_id", null)
        ?.takeIf { prefs.getString("user_role", "") == ROLE_CAMERA && runCatching { java.util.UUID.fromString(it) }.isSuccess }
        ?: cameraDeviceId()

    private fun stableDeviceId(key: String): String {
        prefs.getString(key, null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        if (prefs.edit().putString(key, id).commit()) return id
        return prefs.getString(key, id) ?: id
    }

    fun registeredCameras(): List<RegisteredCamera> = runCatching {
        val array = JSONArray(prefs.getString("registered_cameras", "[]"))
        (0 until array.length()).mapNotNull { index ->
            val row = array.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("id")
            if (runCatching { java.util.UUID.fromString(id) }.isFailure) return@mapNotNull null
            RegisteredCamera(id, row.optString("name").ifBlank { "Cámara" })
        }
    }.getOrDefault(emptyList())

    fun addRegisteredCamera(name: String): RegisteredCamera {
        val cleaned = name.trim().take(120)
        require(cleaned.isNotBlank()) { "Escribe un nombre para la cámara." }
        return upsertRegisteredCamera(java.util.UUID.randomUUID().toString(), cleaned)
    }

    fun upsertRegisteredCamera(id: String, name: String, enqueueSync: Boolean = true): RegisteredCamera {
        require(runCatching { java.util.UUID.fromString(id) }.isSuccess) { "Identificador de cámara inválido." }
        val cleaned = name.trim().take(120)
        require(cleaned.isNotBlank()) { "Escribe un nombre para la cámara." }
        val rows = registeredCameras()
        require(rows.any { it.id == id } || rows.size < 20) { "Puedes registrar hasta 20 cámaras." }
        val camera = RegisteredCamera(id, cleaned)
        val json = JSONArray().apply {
            rows.filterNot { it.id == id }.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
            put(JSONObject().put("id", camera.id).put("name", camera.name))
        }
        val saved = prefs.edit().putString("registered_cameras", json.toString())
            .putLong("camera_registry_revision", System.currentTimeMillis()).commit()
        if (!saved) {
            prefs.edit().putString("registered_cameras", json.toString())
                .putLong("camera_registry_revision", System.currentTimeMillis()).apply()
        }
        if (enqueueSync) {
            SyncScheduler.enqueue(appContext)
        }
        return camera
    }

    fun removeRegisteredCamera(id: String, enqueueSync: Boolean = true) {
        val rows = registeredCameras().filterNot { it.id == id }
        val json = JSONArray().apply {
            rows.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
        }
        val saved = prefs.edit().putString("registered_cameras", json.toString())
            .putLong("camera_registry_revision", System.currentTimeMillis()).commit()
        if (!saved) {
            prefs.edit().putString("registered_cameras", json.toString())
                .putLong("camera_registry_revision", System.currentTimeMillis()).apply()
        }
        prefs.edit().remove("camera_ble_peers").remove("camera_wifi_peers")
            .remove("camera_wifi_host").remove("camera_wifi_port").remove("camera_wifi_secret")
            .remove("camera_wifi_peer_id").remove("camera_wifi_peer_name").apply()
        if (enqueueSync) {
            SyncScheduler.enqueue(appContext)
        }
    }

    fun syncModules(): Map<String, SyncStateSummary> {
        val userId = EncryptedSessionStore(appContext).read()?.userId
        val medicationOwner = if (role() == ROLE_CAREGIVER) prefs.getString("caregiver_owner_id", "").orEmpty()
            else userId ?: prefs.getString("medication_local_data_claimed_by", "").orEmpty().ifBlank { "local:${localDeviceId()}" }
        val profilePending = profileUpdatedAt() > lastProfileSyncAt()
        val deviceState = prefs.getString("device_sync_state", "pending")
        return linkedMapOf(
            "Telemetría y contactos" to telemetry.syncStateSummary(),
            "Privacidad de ubicación" to SyncStateSummary(LocationSharing.pendingClearCount(this), 0),
            "Medicamentos y tomas" to MedicationDatabase.getInstance(appContext).syncState(medicationOwner),
            "Cuidados y borrados" to CareFeatureStore(appContext).syncState(),
            "Perfil" to SyncStateSummary(if (profilePending) 1 else 0, 0),
            "Registro de dispositivos" to SyncStateSummary(if (userId != null && deviceState == "pending") 1 else 0,
                if (userId != null && deviceState == "error") 1 else 0),
        )
    }

    fun syncState(): SyncStateSummary = SyncOverview.total(syncModules().values)
    fun retryFailedSyncItems() {
        telemetry.retryFailedSyncItems()
        MedicationDatabase.getInstance(appContext).retryFailed()
        prefs.edit().putInt("sync_global_failures", 0).putString("sync_status", "Sincronización pendiente").apply()
        SyncScheduler.enqueue(appContext)
    }

    internal fun profileSyncSnapshot(): ProfileSyncSnapshot = synchronized(lock) {
        ProfileSyncSnapshot(profile().text("fullName").trim(), profileUpdatedAt(), lastProfileSyncAt(),
            prefs.getString("privacy_notice_version", "").orEmpty(), prefs.getLong("privacy_consent_at", 0L))
    }

    fun profileUpdatedAt(): Long = prefs.getLong("profile_updated_at", 0L)
    fun lastProfileSyncAt(): Long = prefs.getLong("profile_sync_at", 0L)
    fun markProfileSynced(uploadedVersion: Long) = synchronized(lock) {
        // A late response acknowledges only the version actually sent.
        prefs.edit().putLong("profile_sync_at", maxOf(lastProfileSyncAt(), uploadedVersion)).apply()
    }

    fun applyRemoteProfile(fullName: String, accountRole: String?, updatedAtMillis: Long, expectedLocalVersion: Long = profileUpdatedAt()) = synchronized(lock) {
        val editor = prefs.edit().putString("account_role", accountRole.orEmpty())
        // Do not replace the local mode, even on first login or when remote role is null.
        if (profileUpdatedAt() == expectedLocalVersion) {
            val profile = profile().put("fullName", fullName)
            editor.putString("profile", profile.toString()).putLong("profile_updated_at", updatedAtMillis)
                .putLong("profile_sync_at", updatedAtMillis)
        }
        check(editor.commit()) { "No se pudo guardar el perfil remoto." }
    }

    /**
     * Cachés derivadas de remoto: vínculo de cuidador, datos compartidos y códigos de
     * emparejamiento pendientes. Se descartan al cerrar sesión o cuando Supabase confirma
     * que el vínculo ya no existe, para no mostrar información de perfiles anteriores.
     */
    fun clearRemoteDerivedCaches() {
        check(CaregiverAccess(this).clear()) { "La cuenta cambió antes de descartar la caché compartida." }
    }

    fun prepareContactsForUser(userId: String) {
        val canonical = contacts().map { contact ->
            val stableId = java.util.UUID.nameUUIDFromBytes((userId + ":" + PhoneNumbers.key(contact.phone)).toByteArray(Charsets.UTF_8)).toString()
            contact.copy(id = stableId)
        }
        if (canonical.map { it.id } == contacts().map { it.id }) return
        val json = JSONArray().apply { canonical.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("phone", it.phone)) } }
        check(prefs.edit().putString("contacts", json.toString()).commit()) { "No se pudieron preparar los contactos para sincronizarlos." }
        telemetry.replaceContacts(canonical.map { LocalContactRecord(it.id, it.name, it.phone) })
    }

    fun applyRemoteContacts(rows: List<LocalContactRecord>) {
        val json = JSONArray().apply { rows.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("phone", it.phone)) } }
        check(prefs.edit().putString("contacts", json.toString()).commit()) { "No se pudieron guardar los contactos sincronizados." }
        telemetry.replaceContactsFromRemote(rows)
    }

    fun clearAccountData() {
        prefs.edit().remove("profile").remove("contacts").remove("events").remove("user_role")
            .remove("profile_updated_at").remove("profile_sync_at").remove("role_updated_at").remove("account_role").commit()
        telemetry.clearAllData()
    }

    /** Switches the active namespace without deleting either account's files or pending data.
     * Callers must reopen their stores after switching; existing instances remain pinned.
     */
    fun bindLocalDataToUser(userId: String): Boolean {
        if (userId.isBlank()) return false
        val changed = AccountStorage.activate(appContext, userId)
        return changed || !AccountStorage.isCurrent(appContext)
    }

    fun ownerUserId(): String? = prefs.getString("local_data_owner_id", "").orEmpty().takeIf(String::isNotBlank)


    private fun classifyFallEvent(message: String, id: String, incidentId: String?, now: Long): LocalFallEvent? {
        return when {
            message.startsWith("Posible caída") && message.contains("pulsera", ignoreCase = true) -> LocalFallEvent(id, incidentId, arduinoDeviceId(), now, "arduino", "possible_fall", "pending", "La pulsera detectó un golpe compatible con una caída.")
            message.startsWith("Posible caída") && message.contains("cámara", ignoreCase = true) -> LocalFallEvent(id, incidentId, cameraEventDeviceId(), now, "camera_ai", "possible_fall", "pending", "La cámara vinculada detectó una posible caída.")
            message.startsWith("Posible caída") -> LocalFallEvent(id, incidentId, localDeviceId(), now, "phone_accelerometer", "possible_fall", "pending", "Posible caída detectada por el sensor de movimiento de este teléfono.")
            message.startsWith("Detección con cámara IA señaló") -> LocalFallEvent(id, incidentId, cameraEventDeviceId(), now, "camera_ai", "possible_fall", "pending", "La cámara vinculada señaló una postura de riesgo.")
            message.startsWith("Alerta cancelada") && incidentId != null -> LocalFallEvent(id, incidentId, localDeviceId(), now, "other", "cancelled", "cancelled", "Alerta cancelada localmente por la persona.")
            message.startsWith("Alerta manual iniciada") && incidentId != null -> LocalFallEvent(id, incidentId, localDeviceId(), now, "manual", "manual", "confirmed", "Alerta iniciada manualmente en este teléfono.")
            else -> null
        }
    }

    private fun isNotificationEvent(message: String): Boolean =
        message.startsWith("SMS solicitado") || message.startsWith("No se pudo solicitar SMS") ||
            message.startsWith("SMS enviado a la red") || message.startsWith("SMS entregado al destinatario") ||
            message.startsWith("SMS no enviado") || message.startsWith("SMS rechazado") ||
            message.startsWith("Entrega no confirmada") || message.startsWith("Reporte de entrega recibido") ||
            message.startsWith("Llamada solicitada") || message.startsWith("Llamada no solicitada") ||
            message.startsWith("No se pudo llamar") || message.startsWith("Android no confirmó todos los SMS")

    private fun classifyNotificationAttempt(message: String, id: String, incidentId: String, now: Long): LocalNotificationAttempt? {
        val channel: String
        val status: String
        val error: String?
        when {
            message.startsWith("SMS solicitado") -> { channel = "sms"; status = "requested"; error = null }
            message.startsWith("SMS enviado a la red") -> { channel = "sms"; status = "sent_to_network"; error = null }
            message.startsWith("SMS entregado al destinatario") -> { channel = "sms"; status = "delivered"; error = null }
            message.startsWith("SMS no enviado") || message.startsWith("SMS rechazado") || message.startsWith("No se pudo solicitar SMS") -> { channel = "sms"; status = "failed"; error = "sms_request_failed" }
            message.startsWith("Entrega no confirmada") || message.startsWith("Reporte de entrega recibido") || message.startsWith("Android no confirmó todos los SMS") -> { channel = "sms"; status = "not_confirmed"; error = "sms_delivery_unconfirmed" }
            message.startsWith("Llamada solicitada") -> { channel = "call"; status = "requested"; error = null }
            message.startsWith("Llamada no solicitada") || message.startsWith("No se pudo llamar") -> { channel = "call"; status = "failed"; error = "call_request_failed" }
            else -> return null
        }
        return LocalNotificationAttempt(id, incidentId, localDeviceId(), channel, status, now,
            if (status == "delivered" || status == "sent_to_network") now else null, error)
    }
    fun acceptCallback(key: String): Boolean = synchronized(lock) {
        val seen = prefs.getStringSet("sms_callbacks", emptySet()).orEmpty().toMutableSet()
        if (!seen.add(key)) return@synchronized false
        // Only current/recent incidents matter; bound persisted callback IDs.
        if (seen.size > 500) seen.removeAll(seen.filterNot { it.startsWith(prefs.getString("dispatch_id", "__" ).orEmpty()) }.toSet())
        prefs.edit().putStringSet("sms_callbacks", seen).commit()
        true
    }
    fun locationText(): String {
        if (!prefs.getBoolean("include_location", false)) return "Ubicación no compartida."
        val time = prefs.getLong("location_time", 0)
        val age = System.currentTimeMillis() - time
        if (time == 0L || age !in 0..300_000) return "Ubicación no disponible."
        return "Ubicación aproximada: https://maps.google.com/?q=${prefs.getString("latitude", "")},${prefs.getString("longitude", "")}"
    }
    companion object {
        const val ROLE_WEARER = "wearer"
        const val ROLE_CAREGIVER = "caregiver"
        const val ROLE_CAMERA = "camera"
        const val BRACELET_MODE_BLE = "ble"
        const val BRACELET_MODE_WIFI = "wifi"
        const val DEFAULT_ARDUINO_BASE_URL = "https://192.168.18.35:8081"
        private const val ARDUINO_BASE_URL_KEY = "arduino_base_url"
        private const val ARDUINO_CERTIFICATE_FINGERPRINT_KEY = "arduino_certificate_fingerprint"
        private const val LOCAL_DATA_OWNER_KEY = "local_data_owner_id"
        private val lock = Any()
    }
}

internal data class ArduinoConnectionSettings(
    val baseUrl: String,
    val certificateFingerprint: String,
    val token: String?,
)

data class RegisteredCamera(val id: String, val name: String)

/** Flutter profiles may contain JSON null for optional fields. */
fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key, "")
