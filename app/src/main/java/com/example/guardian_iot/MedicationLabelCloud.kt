package com.example.guardian_iot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Lee la caja o receta con un modelo de visión de NVIDIA NIM. La foto se envía a NVIDIA solo
 * cuando la persona elige esa opción; si falla, el formulario vuelve a la lectura en el teléfono.
 */
object NvidiaMedicationLabelReader {
    private const val MAX_EDGE = 1600
    private const val MAX_IMAGE_BYTES = 3 * 1024 * 1024

    private val executor = Executors.newSingleThreadExecutor()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun isAvailable(context: Context): Boolean {
        val sessions = EncryptedSessionStore(context)
        return SupabaseBackend(sessions).isConfigured && sessions.read() != null
    }

    fun read(context: Context, uri: Uri, onResult: (MedicationLabelGuess?, String?) -> Unit) {
        val backend = SupabaseBackend(EncryptedSessionStore(context))
        if (!isAvailable(context)) {
            onResult(null, "Inicia sesión para leer en la nube. Puedes leer la foto en este teléfono.")
            return
        }
        val appContext = context.applicationContext
        executor.execute {
            val outcome = runCatching { fetch(appContext, uri, backend) }
            main.post {
                outcome.fold(
                    onSuccess = { guess -> onResult(guess, null) },
                    onFailure = { error -> onResult(null, messageFor(error)) },
                )
            }
        }
    }

    internal fun parseResponse(raw: String): MedicationLabelGuess? {
        val content = runCatching {
            JSONObject(raw).optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.text("content")
                .orEmpty()
        }.getOrNull().orEmpty()
        val payload = runCatching { JSONObject(raw).optJSONObject("result") }.getOrNull()
            ?: extractObject(content) ?: return null
        val fields = listOf("name", "dose", "presentation", "frequency", "anchor_time")
        if (fields.any { key -> payload.has(key) && (payload.opt(key) !is String || payload.optString(key).length > 256) }) return null
        val guess = MedicationLabelGuess(
            name = payload.text("name"),
            presentation = payload.text("presentation"),
            dose = payload.text("dose"),
            frequency = payload.text("frequency"),
            anchorTime = payload.text("anchor_time"),
        )
        return MedicationLabelParser.refine(guess).takeUnless { it.isEmpty }
    }

    private fun fetch(context: Context, uri: Uri, backend: SupabaseBackend): MedicationLabelGuess {
        val encoded = encodeImage(context, uri) ?: error("No se pudo abrir la foto.")
        val response = backend.readMedicationLabel(encoded)
        return parseResponse(response.toString()) ?: error("La respuesta no trajo datos legibles.")
    }

    private fun encodeImage(context: Context, uri: Uri): String? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_EDGE) sample *= 2
        var bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull()
        val degrees = when (rotation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        if (degrees != 0) {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        if (out.size() > MAX_IMAGE_BYTES) error("La foto es demasiado grande para leerla en la nube.")
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun extractObject(content: String): JSONObject? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(content.substring(start, end + 1)) }.getOrNull()
    }

    private fun JSONObject.text(key: String): String {
        val value = opt(key)
        return if (value == null || value == JSONObject.NULL) "" else value.toString().trim()
    }

    private fun messageFor(error: Throwable): String {
        val text = error.message.orEmpty()
        return when {
            error is BackendFailure && error.category == BackendFailure.Category.SESSION ->
                "Inicia sesión para leer en la nube. Se leerá la foto en el teléfono."
            error is BackendFailure && error.status == 429 ->
                "Se alcanzó el límite de lecturas en la nube. Se leerá la foto en el teléfono."
            text.startsWith("RESPONSE_") ->
                "NVIDIA no pudo leer la foto. Se intentará en el teléfono."
            error is UnknownHostException || error is ConnectException ->
                "Sin conexión con NVIDIA. Se leerá la foto en el teléfono."
            error is SocketTimeoutException ->
                "NVIDIA tardó demasiado. Se leerá la foto en el teléfono."
            else -> "No se pudo leer con NVIDIA. Se intentará en el teléfono."
        }
    }
}
