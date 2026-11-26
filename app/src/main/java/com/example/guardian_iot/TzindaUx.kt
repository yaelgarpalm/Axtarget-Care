package com.example.guardian_iot

/**
 * Product rules that are independent from Android views.
 *
 * Keeping role navigation and status wording in one place avoids the old screen-by-screen
 * drift where the same link, device or sync state was described in different ways.
 */
object TzindaTabs {
    const val HOME = 0
    const val ACTIVITY = 1
    const val CONTACTS = 2
    const val SETTINGS = 3
    const val PROFILE = 4
    const val CAMERA = 5
    const val ROLE = 6
    const val DEVICE = 7
    const val MEDICATIONS = 8
    const val CARE = 9
    const val SUPERVISION = 10
    const val MEDICATION_RECORDS = 11
    const val PULSE = 12
    const val OXYGEN = 13
    const val ALERT_HISTORY = 14
    const val ALERT_DETAILS = 15
    const val HELP_REQUEST = 16
    const val ALERT_PRACTICE = 17
    const val BRACELET_ALERT = 18
    const val BRACELET_PAIR = 19
    const val BRACELET_DIAGNOSTIC = 20
    const val SAFE_ZONES = 21
    const val ADD_CONTACT = 22
    const val EMERGENCY_CARD = 23
    const val REPORT = 24
    const val CARE_SETTINGS = 26
}

enum class TzindaRole { WEARER, CAREGIVER, CAMERA }

enum class TzindaNavKey { HOME, ACTIVITY, CONTACTS, MEDICATIONS, SETTINGS, CAMERA, EVENTS, LINK, SUPERVISION }

data class TzindaNavItem(
    val key: TzindaNavKey,
    val label: String,
    val iconRes: Int,
    val targetTab: Int,
)

data class TzindaCopy(val title: String, val detail: String, val tone: TzindaTone)

enum class TzindaTone { POSITIVE, INFO, WARNING, CRITICAL, NEUTRAL }

object TzindaNavigationPolicy {
    fun role(raw: String): TzindaRole = when (raw) {
        AppStore.ROLE_CAREGIVER -> TzindaRole.CAREGIVER
        AppStore.ROLE_CAMERA -> TzindaRole.CAMERA
        else -> TzindaRole.WEARER
    }

    /**
     * Four primary destinations per account type, exactly as the redesigned bottom navigation
     * (Inicio, Alertas, Salud, Supervisión). Ajustes is a secondary screen reached from the
     * profile, like the mockups' top-right person button.
     */
    fun items(role: TzindaRole): List<TzindaNavItem> = when (role) {
        TzindaRole.CAREGIVER -> listOf(
            TzindaNavItem(TzindaNavKey.HOME, "Inicio", R.drawable.v5_home, TzindaTabs.HOME),
            TzindaNavItem(TzindaNavKey.ACTIVITY, "Alertas", R.drawable.v5_bell, TzindaTabs.ACTIVITY),
            TzindaNavItem(TzindaNavKey.MEDICATIONS, "Salud", R.drawable.v5_pulse, TzindaTabs.MEDICATIONS),
            TzindaNavItem(TzindaNavKey.SUPERVISION, "Supervisión", R.drawable.v5_supervision, TzindaTabs.SUPERVISION),
        )
        TzindaRole.CAMERA -> listOf(
            TzindaNavItem(TzindaNavKey.CAMERA, "Inicio", R.drawable.v5_home, TzindaTabs.HOME),
            TzindaNavItem(TzindaNavKey.EVENTS, "Alertas", R.drawable.v5_bell, TzindaTabs.ACTIVITY),
            TzindaNavItem(TzindaNavKey.LINK, "Vincular", R.drawable.v5_link, TzindaTabs.CONTACTS),
            TzindaNavItem(TzindaNavKey.SETTINGS, "Ajustes", R.drawable.v5_settings, TzindaTabs.SETTINGS),
        )
        TzindaRole.WEARER -> listOf(
            TzindaNavItem(TzindaNavKey.HOME, "Inicio", R.drawable.v5_home, TzindaTabs.HOME),
            TzindaNavItem(TzindaNavKey.ACTIVITY, "Alertas", R.drawable.v5_bell, TzindaTabs.ACTIVITY),
            TzindaNavItem(TzindaNavKey.MEDICATIONS, "Salud", R.drawable.v5_pulse, TzindaTabs.MEDICATIONS),
            TzindaNavItem(TzindaNavKey.SUPERVISION, "Supervisión", R.drawable.v5_supervision, TzindaTabs.SUPERVISION),
        )
    }

    /** The highlighted primary destination, or -1 for a secondary screen opened from one of them. */
    fun selectedIndex(role: TzindaRole, tab: Int): Int = when (tab) {
        TzindaTabs.HOME -> 0
        TzindaTabs.ACTIVITY -> 1
        TzindaTabs.MEDICATIONS -> 2
        TzindaTabs.SUPERVISION -> 3
        TzindaTabs.SETTINGS -> if (role == TzindaRole.CAMERA) 3 else -1
        TzindaTabs.CONTACTS -> if (role == TzindaRole.CAMERA) 2 else -1
        TzindaTabs.CAMERA -> if (role == TzindaRole.CAMERA) 0 else -1
        else -> -1
    }

    fun screenName(role: TzindaRole, tab: Int): String = when (tab) {
        TzindaTabs.HOME -> when (role) {
            TzindaRole.CAREGIVER -> "Inicio"
            TzindaRole.CAMERA -> "Cámara"
            TzindaRole.WEARER -> "Inicio"
        }
        TzindaTabs.ACTIVITY -> when (role) {
            TzindaRole.CAREGIVER -> "Alertas y actividad"
            TzindaRole.CAMERA -> "Eventos de cámara"
            TzindaRole.WEARER -> "Alertas"
        }
        TzindaTabs.CONTACTS -> when (role) {
            TzindaRole.CAREGIVER -> "Persona vinculada"
            TzindaRole.CAMERA -> "Vincular cámara"
            TzindaRole.WEARER -> "Contactos"
        }
        TzindaTabs.SETTINGS -> "Ajustes"
        TzindaTabs.PROFILE -> "Mi perfil"
        TzindaTabs.CAMERA -> "Cámara"
        TzindaTabs.DEVICE -> "Dispositivos"
        TzindaTabs.MEDICATIONS -> "Salud"
        TzindaTabs.CARE -> "Cuidados y rutinas"
        TzindaTabs.SUPERVISION -> "Supervisión"
        TzindaTabs.ROLE -> "Modo de este teléfono"
        TzindaTabs.MEDICATION_RECORDS -> "Medicamentos"
        TzindaTabs.PULSE -> "Frecuencia cardíaca"
        TzindaTabs.OXYGEN -> "Oxígeno en sangre"
        TzindaTabs.ALERT_HISTORY -> "Historial de alertas"
        TzindaTabs.ALERT_DETAILS -> "Detalle de la alerta"
        TzindaTabs.HELP_REQUEST -> "Solicitud de ayuda"
        TzindaTabs.ALERT_PRACTICE -> "Simulacro SOS"
        TzindaTabs.BRACELET_ALERT -> "Pulsera desconectada"
        TzindaTabs.BRACELET_PAIR -> "Vincular pulsera"
        TzindaTabs.BRACELET_DIAGNOSTIC -> "Diagnóstico de pulsera"
        TzindaTabs.SAFE_ZONES -> "Zonas seguras"
        TzindaTabs.ADD_CONTACT -> "Añadir contacto"
        TzindaTabs.EMERGENCY_CARD -> "Ficha de emergencia"
        TzindaTabs.REPORT -> "Preparar reporte"
        TzindaTabs.CARE_SETTINGS -> "Ajustes de cuidados"
        else -> "Inicio"
    }

    fun roleLabel(role: TzindaRole): String = when (role) {
        TzindaRole.WEARER -> "Persona protegida"
        TzindaRole.CAREGIVER -> "Cuidador o familiar"
        TzindaRole.CAMERA -> "Cámara independiente"
    }
}

/** Small account-local screen history for secondary destinations and system/back-button parity. */
class TzindaNavigationHistory {
    private val previousTabs = mutableListOf<Int>()

    fun record(from: Int, to: Int, destinationIsSecondary: Boolean) {
        if (from == to) return
        if (!destinationIsSecondary) {
            previousTabs.clear()
            return
        }
        previousTabs += from
        if (previousTabs.size > MAX_DEPTH) previousTabs.removeAt(0)
    }

    fun back(): Int? = if (previousTabs.isEmpty()) null else previousTabs.removeAt(previousTabs.lastIndex)
    fun peek(): Int? = previousTabs.lastOrNull()
    fun clear() = previousTabs.clear()
    fun snapshot(): IntArray = previousTabs.toIntArray()

    fun restore(tabs: IntArray?) {
        previousTabs.clear()
        (tabs ?: intArrayOf()).takeLast(MAX_DEPTH).filter { it in 0..TzindaTabs.CARE_SETTINGS }.forEach { previousTabs.add(it) }
    }

    private companion object { const val MAX_DEPTH = 32 }
}

object TzindaText {
    fun count(value: Int, singular: String, plural: String = "${singular}s"): String {
        val safe = value.coerceAtLeast(0)
        return "$safe ${if (safe == 1) singular else plural}"
    }
}

object TzindaStatusCopy {
    fun monitoring(active: Boolean): TzindaCopy = if (active) {
        TzindaCopy("Protección activa", "La detección de caídas y los avisos locales están funcionando en este teléfono.", TzindaTone.POSITIVE)
    } else {
        TzindaCopy("Protección en pausa", "Activa el monitoreo para detectar caídas y avisar a tus contactos.", TzindaTone.NEUTRAL)
    }

    fun link(linked: Boolean, personName: String, linkedAtMillis: Long = 0L, now: Long = System.currentTimeMillis()): TzindaCopy {
        if (!linked) return TzindaCopy(
            "Sin vínculo activo",
            "Conecta una cuenta para recibir alertas y datos autorizados.",
            TzindaTone.NEUTRAL,
        )
        val name = personName.trim().ifBlank { "Persona protegida" }
        val freshness = if (linkedAtMillis > 0L) " Actualizado ${freshness(now - linkedAtMillis)}." else ""
        return TzindaCopy("Vínculo activo · $name", "Puedes consultar únicamente los datos que esa persona comparte.$freshness", TzindaTone.POSITIVE)
    }

    fun cameraLink(linked: Boolean, cameraName: String): TzindaCopy = if (linked) {
        TzindaCopy(
            "Cámara vinculada · ${cameraName.ifBlank { "Cámara AxTarget Care" }}",
            "Las detecciones se guardan aquí y se sincronizan cuando hay Internet. La cámara solo funciona cuando la abres.",
            TzindaTone.POSITIVE,
        )
    } else {
        TzindaCopy("Cámara sin vincular", "Escanea el QR de la persona protegida para compartir las detecciones autorizadas.", TzindaTone.NEUTRAL)
    }

    fun device(state: DeviceConnectionState, lastDataAt: Long?, now: Long = System.currentTimeMillis()): TzindaCopy {
        val age = if (lastDataAt != null && lastDataAt > 0L) freshness(now - lastDataAt) else "sin datos recibidos"
        return when (state) {
            DeviceConnectionState.CONNECTED -> TzindaCopy("Conectado ahora", "El equipo está enviando datos. Última señal $age.", TzindaTone.POSITIVE)
            DeviceConnectionState.SYNCING -> TzindaCopy("Sincronizando", "Hay una actualización en curso. Última señal $age.", TzindaTone.INFO)
            DeviceConnectionState.DISCONNECTED -> TzindaCopy("Desconectado", "El equipo dejó de responder. Última señal $age.", TzindaTone.WARNING)
            DeviceConnectionState.NO_RECENT_DATA -> TzindaCopy("Sin datos recientes", "Está registrado, pero su última señal fue $age.", TzindaTone.WARNING)
            DeviceConnectionState.REGISTERED -> TzindaCopy("Registrado, sin conexión confirmada", "Todavía no se ha recibido una señal válida de este equipo.", TzindaTone.NEUTRAL)
            DeviceConnectionState.ERROR -> TzindaCopy("Error de conexión", "No se pudo consultar el estado del equipo. Puedes reintentar.", TzindaTone.CRITICAL)
            DeviceConnectionState.UNKNOWN -> TzindaCopy("Estado desconocido", "No hay información suficiente para confirmar la conexión.", TzindaTone.NEUTRAL)
        }
    }

    fun sync(pending: Int, failed: Int, online: Boolean, signedIn: Boolean): TzindaCopy {
        val safePending = pending.coerceAtLeast(0)
        val safeFailed = failed.coerceAtLeast(0)
        return when {
            !signedIn -> TzindaCopy("Modo local", "Los datos permanecen en este teléfono. Inicia sesión para sincronizarlos.", TzindaTone.NEUTRAL)
            !online -> TzindaCopy("Sin Internet", "Tus datos están guardados. $safePending operación(es) esperan conexión.", TzindaTone.WARNING)
            safeFailed > 0 -> TzindaCopy("Sincronización con error", "$safeFailed operación(es) agotaron los reintentos; $safePending siguen pendientes.", TzindaTone.CRITICAL)
            safePending > 0 -> TzindaCopy("Pendiente de sincronizar", "$safePending operación(es) se enviarán cuando el servicio esté disponible.", TzindaTone.INFO)
            else -> TzindaCopy("Sin pendientes locales", "Las colas locales están vacías. Consulta la última confirmación del servidor para conocer la sincronización remota.", TzindaTone.NEUTRAL)
        }
    }

    fun vitals(hasReading: Boolean, capturedAtMillis: Long, connected: Boolean, now: Long = System.currentTimeMillis()): TzindaCopy {
        if (!hasReading) return if (connected) {
            TzindaCopy("Pulsera conectada", "Coloca el dedo en el sensor y espera unos segundos; aún no hay una lectura válida.", TzindaTone.INFO)
        } else {
            TzindaCopy("Sin lecturas", "Conecta la pulsera UNO Q o espera una lectura válida. No se muestran valores de ejemplo.", TzindaTone.NEUTRAL)
        }
        return TzindaCopy(
            "Última lectura ${freshness(now - capturedAtMillis)}",
            "Datos informativos de la pulsera; no son un diagnóstico médico.",
            if (now - capturedAtMillis <= 10 * 60_000L) TzindaTone.POSITIVE else TzindaTone.WARNING,
        )
    }

    fun freshness(ageMs: Long): String {
        if (ageMs < 0) return "en el futuro"
        if (ageMs < 60_000L) return "hace menos de un minuto"
        if (ageMs < 3_600_000L) return "hace ${ageMs / 60_000L} min"
        if (ageMs < 86_400_000L) return "hace ${ageMs / 3_600_000L} h"
        return "hace más de un día"
    }

    /**
     * Parses timestamps returned by the backend and the local database without assuming a single
     * format. Supabase may serialize UTC instants as "Z" or as a numeric offset such as "+00:00".
     * Returns 0 when the value is absent or cannot be interpreted, so callers can say "sin datos"
     * instead of showing a misleading date.
     */
    fun timestampMillis(raw: String): Long {
        val value = raw.trim().takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) } ?: return 0L
        val instant = runCatching { java.time.Instant.parse(value) }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(value).toInstant() }.getOrNull()
            ?: runCatching {
                java.time.LocalDateTime.parse(value).atZone(java.time.ZoneId.systemDefault()).toInstant()
            }.getOrNull()
        return instant?.toEpochMilli() ?: 0L
    }

    fun confirmation(raw: String): String = when (raw.trim().lowercase()) {
        "pending" -> "Sin confirmar"
        "confirmed" -> "Confirmada"
        "false_alarm", "dismissed" -> "Falsa alarma"
        "cancelled", "canceled" -> "Cancelada"
        "attended" -> "Atendida"
        "closed" -> "Cerrada"
        else -> raw.replace('_', ' ').replaceFirstChar { it.uppercase() }.ifBlank { "Sin estado" }
    }

    fun source(raw: String): String = when (raw.trim().lowercase().replace('_', ' ')) {
        "camera ai", "camera" -> "Cámara"
        "phone sensor", "phone motion", "phone accelerometer", "accelerometer" -> "Sensores del teléfono"
        "wearable", "arduino", "uno q" -> "Pulsera"
        "manual", "user action" -> "Registro manual"
        "other", "system" -> "Sistema"
        else -> raw.replace('_', ' ').replaceFirstChar { it.uppercase() }.ifBlank { "Origen no indicado" }
    }

    fun classification(raw: String): String = when (raw.trim().lowercase().replace('_', ' ')) {
        "possible fall", "fall" -> "Posible caída"
        "normal motion" -> "Movimiento normal"
        "camera session" -> "Sesión de cámara"
        "cancelled", "canceled" -> "Alerta cancelada"
        else -> raw.replace('_', ' ').replaceFirstChar { it.uppercase() }.ifBlank { "Evento" }
    }

    fun medicationStatus(raw: String): String = when (raw) {
        "pending" -> "Pendiente de confirmar"
        "taken" -> "Tomada · confirmada"
        "skipped" -> "No tomada · confirmada"
        "overdue" -> "Vencida · sin confirmar"
        "snoozed" -> "Pospuesta"
        else -> raw.replace('_', ' ').replaceFirstChar { it.uppercase() }.ifBlank { "Sin estado" }
    }
}
