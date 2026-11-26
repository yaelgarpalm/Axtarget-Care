package com.example.guardian_iot

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat

/** Local, high-priority confirmation on the camera phone after it detects a possible fall. */
object CameraAlertNotifier {
    private const val CHANNEL_ID = "camera_fall_detected"
    private const val NOTIFICATION_ID = 2410

    fun show(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID,
                "Detecciones de cámara",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { enableVibration(true) })
        }
        val intent = PendingIntent.getActivity(
            context,
            2410,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("AxTarget Care · posible caída detectada")
            .setContentText("El aviso quedó guardado y se enviará a la persona protegida al sincronizarse.")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build())
    }
}
