package com.example.guardian_iot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.os.SystemClock
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.cache.storage.FileStorage
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.Locale
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

internal data class MapPoint(val latitude: Double, val longitude: Double) {
    companion object {
        fun of(latitude: Double, longitude: Double): MapPoint? =
            if (latitude in -90.0..90.0 && longitude in -180.0..180.0) MapPoint(latitude, longitude) else null

        fun from(row: JSONObject) = of(row.optDouble("latitude"), row.optDouble("longitude"))

        fun parse(value: String): MapPoint? {
            val coordinates = value.split(',', limit = 2)
            if (coordinates.size != 2) return null
            val latitude = coordinates[0].trim().toDoubleOrNull() ?: return null
            val longitude = coordinates[1].trim().toDoubleOrNull() ?: return null
            return of(latitude, longitude)
        }
    }

    fun coordinates(): String = String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
    fun routingCoordinates(): String = String.format(Locale.US, "%.6f,%.6f", longitude, latitude)
}

internal data class MapFix(val point: MapPoint, val capturedAt: Long, val accuracyM: Double? = null) {
    fun isRecent(now: Long = System.currentTimeMillis()): Boolean =
        capturedAt > 0 && capturedAt <= now + 30_000 && now - capturedAt < 5 * 60_000

    fun isPreferredTo(previous: MapFix?, now: Long = System.currentTimeMillis()): Boolean {
        if (!isRecent(now)) return false
        if (previous == null || !previous.isRecent(now)) return true
        val newerBy = capturedAt - previous.capturedAt
        if (newerBy < 0) return false
        if (newerBy >= 10_000) return true
        val oldAccuracy = previous.accuracyM
        if (newerBy == 0L) return accuracyM != null && (oldAccuracy == null || accuracyM < oldAccuracy)
        return oldAccuracy == null || (accuracyM != null && accuracyM <= maxOf(oldAccuracy * 2, oldAccuracy + 25))
    }

    fun zoneStatus(now: Long = System.currentTimeMillis()): String {
        val age = (now - capturedAt).coerceAtLeast(0)
        return when {
            age < 15_000 -> "Ubicación actualizada"
            age < 60_000 -> "Última ubicación · Hace ${age / 1000} s"
            else -> "Última ubicación · Hace ${age / 60_000} min"
        }
    }

    fun freshness(now: Long = System.currentTimeMillis()): String {
        val minutes = ((now - capturedAt).coerceAtLeast(0) / 60_000).coerceAtLeast(1)
        return if (accuracyM == null) "Hace $minutes min" else "Hace $minutes min · Precisión ±${accuracyM.roundToInt()} m"
    }
}

internal data class MapPlace(val name: String, val detail: String, val point: MapPoint)

internal data class MapZone(
    val name: String,
    val point: MapPoint,
    val radius: Int = 150,
    val enabled: Boolean = true,
    val shared: Boolean = false,
    val id: String = "",
) {
    companion object {
        fun from(row: JSONObject): MapZone? = MapPoint.from(row)?.let {
            MapZone(row.optString("name").ifBlank { "Lugar seguro" }, it,
                row.optInt("radius", 150).coerceIn(25, 20_000), row.optBoolean("enabled", true),
                row.optBoolean("sharedWithCaregiver"), row.optString("id"))
        }
    }

    fun record(owner: String) = JSONObject().put("id", id).put("ownerUserId", owner)
        .put("name", name).put("latitude", point.latitude).put("longitude", point.longitude)
        .put("radius", radius).put("enabled", enabled).put("sharedWithCaregiver", shared)
}

internal enum class MapTravelMode(val label: String, val service: String, val googleTravelMode: String) {
    CAR("Auto", "routed-car", "driving"),
    WALK("A pie", "routed-foot", "walking")
}

internal fun googleMapsDirectionsUrl(destination: MapPoint, mode: MapTravelMode): String {
    val coordinates = String.format(Locale.US, "%.6f%%2C%.6f", destination.latitude, destination.longitude)
    return "https://www.google.com/maps/dir/?api=1&destination=$coordinates&travelmode=${mode.googleTravelMode}"
}

internal data class MapRoute(
    val distanceM: Double,
    val durationSeconds: Double,
    val path: List<MapPoint>,
    val instructions: List<String>,
) {
    fun timeLabel(): String {
        val minutes = (durationSeconds / 60).roundToInt().coerceAtLeast(1)
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    fun distanceLabel(): String = formatMapDistance(distanceM)
}

internal fun formatMapDistance(meters: Double): String = if (meters < 1000) "${meters.roundToInt()} m" else
    String.format(Locale.forLanguageTag("es-ES"), "%.1f", meters / 1000).removeSuffix(",0") + " km"

internal object MapProjection {
    private const val MAX_LATITUDE = 85.05112878
    private const val EARTH_CIRCUMFERENCE = 40_075_016.686
    fun x(point: MapPoint) = (point.longitude + 180) / 360
    fun y(point: MapPoint): Double {
        val radians = Math.toRadians(point.latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE))
        return (1 - asinh(tan(radians)) / Math.PI) / 2
    }

    fun unproject(x: Double, y: Double): MapPoint = MapPoint(
        Math.toDegrees(atan(sinh(Math.PI * (1 - 2 * y.coerceIn(0.0, 1.0))))),
        (x - kotlin.math.floor(x)) * 360 - 180,
    )

    fun world(zoom: Double) = 256 * 2.0.pow(zoom)
    fun metersPerPixel(point: MapPoint, zoom: Double) =
        EARTH_CIRCUMFERENCE * cos(Math.toRadians(point.latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE))) / world(zoom)

    fun zoomForRadius(point: MapPoint, radius: Double, pixels: Double): Double =
        log2(metersPerPixel(point, 0.0) * pixels / radius).coerceIn(2.0, 19.0)

    fun wrappedDelta(x: Double, center: Double): Double {
        val delta = x - center
        return delta - kotlin.math.floor(delta + 0.5)
    }
}

internal class TzindaMapService(context: Context) : Closeable {
    private val app = context.applicationContext
    private val cacheFolder = File(app.cacheDir, "openstreetmap")
    private val tileClient = lazy {
        HttpClient(Android) {
            engine { connectTimeout = 5_000; socketTimeout = 9_000 }
            install(HttpTimeout) { requestTimeoutMillis = 9_000 }
            install(HttpCache) { publicStorage(FileStorage(cacheFolder.apply { mkdirs() })) }
        }
    }
    private val routeClient = lazy {
        HttpClient(Android) {
            engine { connectTimeout = 6_000; socketTimeout = 12_000 }
            followRedirects = false
            install(HttpTimeout) { requestTimeoutMillis = 12_000 }
        }
    }
    private val routeRequests = Mutex()
    private var lastRouteRequest = 0L
    private val userAgent = "AxTargetCare/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID}; Android)"

    suspend fun tile(zoom: Int, x: Int, y: Int): Bitmap? = withContext(Dispatchers.IO) {
        val response = tileClient.value.get("https://tile.openstreetmap.org/$zoom/$x/$y.png") {
            header(HttpHeaders.UserAgent, userAgent)
        }
        if (response.status.value != 200) return@withContext null
        val image = response.body<ByteArray>()
        BitmapFactory.decodeByteArray(image, 0, image.size)
    }

    @Suppress("DEPRECATION")
    suspend fun search(query: String): List<MapPlace> = withContext(Dispatchers.IO) {
        MapPoint.parse(query)?.let { return@withContext listOf(MapPlace("Coordenadas", it.coordinates(), it)) }
        if (!Geocoder.isPresent()) return@withContext emptyList()
        Geocoder(app, Locale.forLanguageTag("es-MX")).getFromLocationName(query, 6).orEmpty().mapNotNull { address ->
            val point = MapPoint.of(address.latitude, address.longitude) ?: return@mapNotNull null
            val full = address.getAddressLine(0).orEmpty()
            val name = address.featureName ?: address.thoroughfare ?: address.locality ?: full
            MapPlace(name.ifBlank { "Lugar encontrado" }, full, point)
        }.distinctBy(MapPlace::point)
    }

    suspend fun routes(origin: MapPoint, destination: MapPoint, mode: MapTravelMode): List<MapRoute> = routeRequests.withLock {
        val pause = 1_000 - (SystemClock.elapsedRealtime() - lastRouteRequest)
        if (pause > 0) delay(pause)
        lastRouteRequest = SystemClock.elapsedRealtime()
        val points = "${origin.routingCoordinates()};${destination.routingCoordinates()}"
        val response = routeClient.value.get(
            "https://routing.openstreetmap.de/${mode.service}/route/v1/driving/$points" +
                "?geometries=geojson&overview=full&steps=true&alternatives=true",
        ) { header(HttpHeaders.UserAgent, userAgent) }
        if (response.status.value != 200) return@withLock emptyList()
        parseRoutes(response.bodyAsText())
    }

    override fun close() {
        if (tileClient.isInitialized()) tileClient.value.close()
        if (routeClient.isInitialized()) routeClient.value.close()
    }

    companion object {
        fun parseRoutes(text: String): List<MapRoute> {
            val response = JSONObject(text)
            if (response.optString("code") != "Ok") return emptyList()
            return response.optJSONArray("routes").objects().mapNotNull { row ->
                val distance = row.optDouble("distance")
                val duration = row.optDouble("duration")
                if (!distance.isFinite() || !duration.isFinite() || distance < 0 || duration < 0) return@mapNotNull null
                val path = row.optJSONObject("geometry")?.optJSONArray("coordinates")?.let { coordinates ->
                    (0 until coordinates.length()).mapNotNull { i ->
                        coordinates.optJSONArray(i)?.let { MapPoint.of(it.optDouble(1), it.optDouble(0)) }
                    }
                }.orEmpty()
                if (path.size < 2) return@mapNotNull null
                val steps = row.optJSONArray("legs").objects().flatMap { it.optJSONArray("steps").objects() }
                    .map(::routeInstruction).distinct()
                MapRoute(distance, duration, path, steps)
            }
        }

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)

        private fun routeInstruction(step: JSONObject): String {
            val action = step.optJSONObject("maneuver")
            val direction = action?.optString("modifier").orEmpty()
            val side = when {
                "left" in direction -> " a la izquierda"
                "right" in direction -> " a la derecha"
                else -> ""
            }
            val street = step.optString("name").takeIf(String::isNotBlank)?.let { " por $it" }.orEmpty()
            return when (action?.optString("type")) {
                "depart" -> "Inicia el recorrido$street"
                "arrive" -> "Llegaste al destino"
                "roundabout", "rotary" -> {
                    val exit = action.optInt("exit")
                    if (exit > 0) "En la glorieta, toma la salida $exit$street" else "Continúa por la glorieta"
                }
                "turn", "end of road" -> if (direction == "uturn") "Da vuelta en U$street" else "Gira$side$street"
                "fork", "merge", "on ramp", "off ramp" -> "Mantente$side$street"
                else -> "Continúa$street"
            }
        }
    }
}
