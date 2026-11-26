package com.example.guardian_iot

import android.Manifest.permission

internal enum class StartupPermissionGroup(val purpose: String, vararg val permissions: String) {
    LOCATION("Ubicación para Zonas seguras y rutas", permission.ACCESS_COARSE_LOCATION, permission.ACCESS_FINE_LOCATION),
    NOTIFICATIONS("Notificaciones de avisos y recordatorios", permission.POST_NOTIFICATIONS),
    CAMERA("Cámara para la detección o la lectura de códigos", permission.CAMERA),
    NEARBY("Dispositivos cercanos para vincular la pulsera", permission.BLUETOOTH_SCAN, permission.BLUETOOTH_CONNECT),
    CONTACTS("Contactos para elegir tu red de confianza", permission.READ_CONTACTS),
    SMS("SMS para avisos de ayuda", permission.SEND_SMS),
    PHONE("Llamadas y SIM para avisos de ayuda", permission.CALL_PHONE, permission.READ_PHONE_STATE),
    CALLS("Llamadas para avisos de ayuda", permission.CALL_PHONE),
    PHONE_STATE("Información de SIM para los avisos", permission.READ_PHONE_STATE);

    fun isGranted(granted: (String) -> Boolean): Boolean =
        if (this == LOCATION) permissions.any(granted) else permissions.all(granted)
}

internal fun startupPermissionGroups(
    role: TzindaRole,
    sdk: Int,
    supportsCamera: Boolean,
    usesNearby: Boolean,
    canRequestSms: Boolean,
    supportsCalling: Boolean,
    supportsSim: Boolean,
): List<StartupPermissionGroup> = buildList {
    if (role != TzindaRole.CAMERA) add(StartupPermissionGroup.LOCATION)
    if (sdk >= 33) add(StartupPermissionGroup.NOTIFICATIONS)
    if (supportsCamera) add(StartupPermissionGroup.CAMERA)
    if (role == TzindaRole.WEARER) {
        if (sdk >= 31 && usesNearby) add(StartupPermissionGroup.NEARBY)
        add(StartupPermissionGroup.CONTACTS)
        if (canRequestSms) add(StartupPermissionGroup.SMS)
        when {
            supportsCalling && supportsSim -> add(StartupPermissionGroup.PHONE)
            supportsCalling -> add(StartupPermissionGroup.CALLS)
            supportsSim -> add(StartupPermissionGroup.PHONE_STATE)
        }
    }
}
