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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Lee la caja o receta con un modelo de visión de NVIDIA NIM. La foto se envía a NVIDIA solo
 * cuando la persona elige esa opción; si falla, el formulario vuelve a la lectura en el teléfono.
 */
object NvidiaMedicationLabelReader {
    private const val ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions"
    private const val MAX_EDGE = 1600
    private const val PROMPT =
        "Eres un asistente que lee cajas de medicamentos y recetas médicas. Analiza la imagen y responde SOLO " +
            "con un objeto JSON válido, sin markdown ni explicaciones, con estas claves:\n" +
            "name: nombre del medicamento tal como aparece en la imagen, incluida la sal (por ejemplo \"Losartán potásico\").\n" +
            "dose: dosis o concentración con unidad, por ejemplo \"50 mg\" o \"100 mg/5 ml\".\n" +
            "presentation: contenedor y cantidad, por ejemplo \"Caja con 30 tabletas\".\n" +
            "frequency: una de estas opciones exactas: \"Diaria\", \"Cada 2 días\", \"Cada 3 días\", \"Semanal\", " +
            "\"Cada 4 horas\", \"Cada 6 horas\", \"Cada 8 horas\", \"Cada 12 horas\", \"Cada 24 horas\". Si no aparece, cadena vacía.\n" +
            "anchor_time: hora de la primera toma en formato HH:mm de 24 horas, o cadena vacía.\n" +
            "No inventes datos que no estén en la imagen. Si la imagen no es una caja ni una receta, devuelve todas las claves vacías."

    private val executor = Executors.newSingleThreadExecutor()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    val isConfigured: Boolean get() = BuildConfig.NVIDIA_API_KEY.isNotBlank()

    fun read(context: Context, uri: Uri, onResult: (MedicationLabelGuess?, String?) -> Unit) {
        val apiKey = BuildConfig.NVIDIA_API_KEY
        if (apiKey.isBlank()) {
            onResult(null, "No hay clave de NVIDIA configurada.")
            return
        }
        val appContext = context.applicationContext
        executor.execute {
            val outcome = runCatching { fetch(appContext, uri, apiKey) }
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
        val payload = extractObject(content) ?: return null
        val guess = MedicationLabelGuess(
            name = payload.text("name"),
            presentation = payload.text("presentation"),
            dose = payload.text("dose"),
            frequency = payload.text("frequency"),
            anchorTime = payload.text("anchor_time"),
        )
        return MedicationLabelParser.refine(guess).takeUnless { it.isEmpty }
    }

    private fun fetch(context: Context, uri: Uri, apiKey: String): MedicationLabelGuess {
        val encoded = encodeImage(context, uri) ?: error("No se pudo abrir la foto.")
        val body = JSONObject().apply {
            put("model", BuildConfig.NVIDIA_VISION_MODEL)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put(
                            "content",
                            JSONArray()
                                .put(JSONObject().apply { put("type", "text"); put("text", PROMPT) })
                                .put(
                                    JSONObject().apply {
                                        put("type", "image_url")
                                        put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$encoded"))
                                    },
                                ),
                        )
                    },
                ),
            )
            put("temperature", 0)
            put("max_tokens", 1200)
            put("stream", false)
        }
        val response = post(apiKey, body.toString())
        return parseResponse(response) ?: error("La respuesta no trajo datos legibles.")
    }

    private fun post(apiKey: String, body: String): String {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 90_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream: InputStream? = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) error("RESPONSE_$code")
            text
        } finally {
            connection.disconnect()
        }
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
            text.startsWith("RESPONSE_401") || text.startsWith("RESPONSE_403") ->
                "NVIDIA rechazó la clave configurada. Se leerá la foto en el teléfono."
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
