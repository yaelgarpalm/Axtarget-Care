package com.example.guardian_iot

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.location.LocationManagerCompat

/** Foreground map locations stay in memory and use the phone's existing location permission. */
internal class TzindaMapLocation(context: Context, private val storedFix: () -> MapFix?) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(LocationManager::class.java)
    private var lastFix: MapFix? = null

    fun unavailableMessage(): String? = when {
        !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) &&
            !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) -> "Permite la ubicación del teléfono"
        manager == null || !LocationManagerCompat.isLocationEnabled(manager) -> "Activa la ubicación del teléfono"
        else -> null
    }

    fun latest(): MapFix? {
        if (unavailableMessage() != null) return null
        val previous = lastFix?.takeIf { it.isRecent() }
        val saved = storedFix()
        return if (saved?.isPreferredTo(previous) == true) saved else previous
    }

    @SuppressLint("MissingPermission")
    fun observe(onChange: () -> Unit): (() -> Unit)? {
        val locationManager = manager ?: return null
        if (unavailableMessage() != null) return null
        val precise = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val providers = locationManager.getProviders(true).filter {
            it == LocationManager.FUSED_PROVIDER || it == LocationManager.NETWORK_PROVIDER ||
                precise && it == LocationManager.GPS_PROVIDER
        }
        var active = true
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!active) return
                val next = location.toMapFix()?.takeIf { it.isRecent() } ?: return
                if (next.isPreferredTo(lastFix)) lastFix = next
                onChange()
            }

            override fun onProviderEnabled(provider: String) { if (active) onChange() }
            override fun onProviderDisabled(provider: String) { if (active) onChange() }
            @Deprecated("Platform callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        val cached = (providers + LocationManager.PASSIVE_PROVIDER).mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()?.toMapFix()
        }.sortedBy(MapFix::capturedAt)
        cached.forEach { if (it.isPreferredTo(lastFix)) lastFix = it }
        val listeners = providers.count { provider ->
            runCatching {
                locationManager.requestLocationUpdates(provider, 2_000L, 0f, listener, Looper.getMainLooper())
            }.isSuccess
        }
        if (listeners == 0) return null
        return {
            active = false
            runCatching { locationManager.removeUpdates(listener) }
        }
    }

    private fun hasPermission(permission: String) =
        app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun Location.toMapFix(): MapFix? = MapPoint.of(latitude, longitude)?.let { point ->
        MapFix(point, time, accuracy.toDouble().takeIf { hasAccuracy() && it.isFinite() && it >= 0 })
    }
}
