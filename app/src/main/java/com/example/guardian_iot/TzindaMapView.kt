package com.example.guardian_iot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextUtils
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow

internal data class MapMarker(val point: MapPoint, val label: String, val color: Int)

internal enum class ExampleMap(val image: Int) {
    ZONES(R.drawable.v11_cartography_zones),
    SUPERVISION(R.drawable.v11_cartography_zones),
    EXIT(R.drawable.v11_cartography_zones),
    HELP(R.drawable.v11_cartography_routes),
    ROUTE(R.drawable.v11_cartography_routes),
    UNAVAILABLE(R.drawable.v11_cartography_zones);
}

internal class TzindaMapView(
    context: Context,
    private val service: TzindaMapService,
    private val example: ExampleMap? = null,
    private val choose: ((MapPoint) -> Unit)? = null,
) : View(context) {
    private data class Tile(val zoom: Int, val x: Int, val y: Int)
    private data class VisibleTile(val key: Tile, val area: RectF)

    private val density = resources.displayMetrics.density
    private val drawing = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val labelPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10 * this@TzindaMapView.density
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val line = Path()
    private val cartography = example?.let { BitmapFactory.decodeResource(resources, it.image) }
    private val tiles = LruCache<Tile, Bitmap>(64)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetching = mutableMapOf<Tile, Job>()
    private val retryAfter = mutableMapOf<Tile, Long>()
    private val loadTiles = Runnable { requestVisibleTiles() }
    private var zoom = 13.0
    private var center: MapPoint? = null
    private var initialCenter: MapPoint? = null
    private var initialZoom = 13.0
    private var maxInitialZoom = 18.0
    private var explored = false
    private var markers = emptyList<MapMarker>()
    private var zone: MapZone? = null
    private var route = emptyList<MapPoint>()
    private var exampleOffsetX = 0f
    private var exampleOffsetY = 0f
    private var exampleScale = 1f
    private var alternateExampleRoute = false
    private var moved = false
    var exampleRadius = 5000
        set(value) { field = value; invalidate() }

    private val pan = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(a: MotionEvent?, b: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!pinch.isInProgress && b.pointerCount == 1) {
                moved = true
                if (example != null) {
                    exampleOffsetX -= dx
                    exampleOffsetY -= dy
                } else {
                    explored = true
                    center = pointAt(width / 2f + dx, height / 2f + dy)
                }
                viewportChanged()
            }
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (moved || choose == null) return false
            val point = if (example == null) pointAt(e.x, e.y) else examplePointAt(e.x, e.y)
            point?.let(choose)
            performClick()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomBy(1.0)
            return true
        }
    })
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            moved = true
            zoomBy(log2(detector.scaleFactor.toDouble()))
            return true
        }
    })

    init {
        contentDescription = if (choose == null) "Mapa de OpenStreetMap" else "Mapa. Toca para elegir el centro de la zona."
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun display(
        point: MapPoint?,
        markers: List<MapMarker>,
        zone: MapZone? = null,
        route: List<MapPoint> = emptyList(),
        maxInitialZoom: Double = 18.0,
    ) {
        this.markers = markers
        this.zone = zone
        this.route = route
        this.maxInitialZoom = maxInitialZoom
        explored = false
        initialCenter = point
        center = point
        fit()
        viewportChanged()
    }

    fun updatePosition(marker: MapMarker?, focus: MapPoint?) {
        markers = listOfNotNull(marker)
        initialCenter = focus
        if (!explored && center != focus) {
            val firstPosition = center == null
            center = focus
            if (firstPosition) fit()
            viewportChanged()
        } else {
            invalidate()
        }
    }

    fun perimeter(zone: MapZone?) {
        this.zone = zone
        invalidate()
    }

    fun showAlternative(selected: Boolean) {
        alternateExampleRoute = selected
        invalidate()
    }

    private fun fit() {
        val point = initialCenter ?: return
        val visibleWidth = if (width > 0) width else (390 * density).toInt()
        val zoneZoom = zone?.let { MapProjection.zoomForRadius(it.point, it.radius.toDouble(), visibleWidth * .35) }
        val points = route + markers.map(MapMarker::point)
        val pathZoom = if (points.isNotEmpty()) {
            val halfWidth = points.maxOf { kotlin.math.abs(MapProjection.wrappedDelta(MapProjection.x(it), MapProjection.x(point))) }
            val halfHeight = points.maxOf { kotlin.math.abs(MapProjection.y(it) - MapProjection.y(point)) }
            val worldX = visibleWidth * .35 / halfWidth.coerceAtLeast(.000001)
            val worldY = height.coerceAtLeast(visibleWidth / 2) * .34 / halfHeight.coerceAtLeast(.000001)
            log2(min(worldX, worldY) / 256).coerceIn(2.0, 18.0)
        } else 18.0
        initialZoom = minOf(zoneZoom ?: if (points.size > 1) 18.0 else 15.0, pathZoom, maxInitialZoom)
        zoom = initialZoom
    }

    fun zoomBy(amount: Double) {
        if (example != null) exampleScale = (exampleScale * 2.0.pow(amount)).toFloat().coerceIn(.65f, 4f)
        else {
            explored = true
            zoom = (zoom + amount).coerceIn(2.0, 19.0)
        }
        viewportChanged()
    }

    fun centerMap() {
        explored = false
        center = initialCenter
        fit()
        exampleOffsetX = 0f
        exampleOffsetY = 0f
        exampleScale = 1f
        viewportChanged()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (example == ExampleMap.UNAVAILABLE || (center == null && example == null)) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) moved = false
        pinch.onTouchEvent(event)
        pan.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (!explored) fit()
        viewportChanged()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewportChanged()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(loadTiles)
        scope.coroutineContext.cancelChildren()
        fetching.clear()
        super.onDetachedFromWindow()
    }

    private fun viewportChanged() {
        invalidate()
        removeCallbacks(loadTiles)
        if (example == null && center != null) postDelayed(loadTiles, 140)
    }

    private fun visibleTiles(): List<VisibleTile> {
        val point = center ?: return emptyList()
        if (width == 0 || height == 0) return emptyList()
        val gridZoom = floor(zoom).toInt().coerceIn(2, 18)
        val gridSize = 1 shl gridZoom
        val size = MapProjection.world(zoom) / gridSize
        val left = MapProjection.x(point) * gridSize - width / (2 * size)
        val top = MapProjection.y(point) * gridSize - height / (2 * size)
        val xRange = floor(left).toInt()..floor(left + width / size).toInt()
        val yRange = floor(top).toInt().coerceAtLeast(0)..floor(top + height / size).toInt().coerceAtMost(gridSize - 1)
        return yRange.flatMap { y ->
            xRange.map { x ->
                VisibleTile(Tile(gridZoom, Math.floorMod(x, gridSize), y), RectF(
                    ((x - left) * size).toFloat(), ((y - top) * size).toFloat(),
                    ((x + 1 - left) * size).toFloat(), ((y + 1 - top) * size).toFloat(),
                ))
            }
        }
    }

    private fun requestVisibleTiles() {
        if (!isAttachedToWindow || example != null) return
        val keys = visibleTiles().sortedBy { tile ->
            kotlin.math.abs(tile.area.centerX() - width / 2f) + kotlin.math.abs(tile.area.centerY() - height / 2f)
        }.map(VisibleTile::key).toSet()
        fetching.keys.filterNot(keys::contains).forEach { fetching.remove(it)?.cancel() }
        val now = SystemClock.elapsedRealtime()
        retryAfter.entries.removeAll { it.value <= now }
        val next = keys.filter { tiles.get(it) == null && it !in fetching && it !in retryAfter }.take((4 - fetching.size).coerceAtLeast(0))
        next.forEach { tile ->
            fetching[tile] = scope.launch {
                val request = coroutineContext[Job]
                val bitmap = try { service.tile(tile.zoom, tile.x, tile.y) }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { null }
                post {
                    if (fetching[tile] != request) return@post
                    fetching.remove(tile)
                    if (bitmap == null) retryAfter[tile] = SystemClock.elapsedRealtime() + 60_000 else tiles.put(tile, bitmap)
                    if (isAttachedToWindow) {
                        invalidate()
                        requestVisibleTiles()
                    }
                }
            }
        }
    }

    private fun pointAt(x: Float, y: Float): MapPoint? = center?.let { point ->
        val pixels = MapProjection.world(zoom)
        MapProjection.unproject(MapProjection.x(point) + (x - width / 2f) / pixels,
            MapProjection.y(point) + (y - height / 2f) / pixels)
    }

    private fun onScreen(point: MapPoint): PointF {
        val origin = center ?: return PointF(width / 2f, height / 2f)
        val pixels = MapProjection.world(zoom)
        return PointF(
            (width / 2f + MapProjection.wrappedDelta(MapProjection.x(point), MapProjection.x(origin)) * pixels).toFloat(),
            (height / 2f + (MapProjection.y(point) - MapProjection.y(origin)) * pixels).toFloat(),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFFE9EEEB.toInt())
        if (example != null) {
            drawExample(canvas)
            return
        }
        val grid = visibleTiles()
        var renderedTile = false
        grid.forEach { tile ->
            tiles.get(tile.key)?.let { bitmap ->
                drawing.color = Color.WHITE
                canvas.drawBitmap(bitmap, null, tile.area, drawing)
                renderedTile = true
            }
        }
        if (!renderedTile) {
            labelPaint.color = 0xFF526063.toInt()
            labelPaint.textAlign = Paint.Align.CENTER
            val message = if (center == null) "Busca un lugar o usa tu ubicación" else if (retryAfter.isEmpty()) "Cargando mapa…" else "Cartografía sin conexión"
            canvas.drawText(message, width / 2f, height * .83f, labelPaint)
        }
        zone?.let {
            val radius = it.radius / MapProjection.metersPerPixel(it.point, zoom)
            drawCircle(canvas, onScreen(it.point), radius.toFloat())
        }
        drawRoute(canvas, route.map(::onScreen))
        markers.forEach { drawMarker(canvas, onScreen(it.point), it.label, it.color) }
    }

    private val exampleDirections = listOf(
        PointF(92f, 458f), PointF(89f, 441f), PointF(101f, 435f), PointF(104f, 447f),
        PointF(176f, 432f), PointF(201f, 437f), PointF(194f, 403f), PointF(259f, 211f),
        PointF(287f, 219f), PointF(281f, 248f),
    )

    private fun exampleOnScreen(x: Float, y: Float): PointF {
        val scale = width / 390f
        return PointF(width / 2f + (x * scale - width / 2f) * exampleScale + exampleOffsetX,
            height / 2f + ((y - 43) * scale - height / 2f) * exampleScale + exampleOffsetY)
    }

    private fun examplePointAt(x: Float, y: Float): MapPoint {
        val reference = exampleOnScreen(195f, 335f)
        val degreesPerPixel = .00033 / (width / 390f * exampleScale).coerceAtLeast(.1f)
        return MapPoint(19.346 - (y - reference.y) * degreesPerPixel, -99.192 + (x - reference.x) * degreesPerPixel)
    }

    private fun drawExample(canvas: Canvas) {
        val top = exampleOnScreen(0f, 0f)
        val bottom = exampleOnScreen(390f, 864f)
        drawing.color = Color.WHITE
        cartography?.let { canvas.drawBitmap(it, null, RectF(top.x, top.y, bottom.x, bottom.y), drawing) }
        when (example) {
            ExampleMap.ZONES, ExampleMap.SUPERVISION -> {
                drawCircle(canvas, exampleOnScreen(195f, 335f), 139 * width / 390f * exampleScale * exampleRadius / 5000)
                drawMarker(canvas, exampleOnScreen(200f, 333f), "Tú", BLUE)
            }
            ExampleMap.EXIT -> {
                drawCircle(canvas, exampleOnScreen(195f, 335f), 139 * width / 390f * exampleScale)
                val home = exampleOnScreen(195f, 335f)
                drawing.color = Color.WHITE
                canvas.drawCircle(home.x, home.y, 5.5f * density, drawing)
                drawing.color = BLUE
                canvas.drawCircle(home.x, home.y, 4f * density, drawing)
                drawMarker(canvas, exampleOnScreen(206f, 187f), "Elena", RED)
            }
            ExampleMap.HELP -> drawMarker(canvas, exampleOnScreen(282f, 248f), "Elena", RED)
            ExampleMap.ROUTE -> {
                val points = if (alternateExampleRoute) listOf(PointF(108f, 437f), PointF(130f, 418f), PointF(173f, 388f),
                    PointF(229f, 368f), PointF(279f, 303f), PointF(282f, 248f)) else exampleDirections
                drawRoute(canvas, points.map { exampleOnScreen(it.x, it.y) })
                drawMarker(canvas, exampleOnScreen(108f, 437f), "Tú", BLUE)
                drawMarker(canvas, exampleOnScreen(282f, 248f), "Elena", RED)
            }
            ExampleMap.UNAVAILABLE -> canvas.drawColor(0xA8F4F5F9.toInt())
            null -> Unit
        }
    }

    private fun drawCircle(canvas: Canvas, point: PointF, radius: Float) {
        drawing.color = 0x290047AB
        canvas.drawCircle(point.x, point.y, radius, drawing)
        drawing.style = Paint.Style.STROKE
        drawing.strokeWidth = 2.4f * density
        drawing.color = BLUE
        canvas.drawCircle(point.x, point.y, radius, drawing)
        drawing.style = Paint.Style.FILL
    }

    private fun drawRoute(canvas: Canvas, points: List<PointF>) {
        if (points.size < 2) return
        line.rewind()
        line.moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { line.lineTo(it.x, it.y) }
        drawing.style = Paint.Style.STROKE
        drawing.strokeCap = Paint.Cap.ROUND
        drawing.strokeJoin = Paint.Join.ROUND
        drawing.color = Color.WHITE
        drawing.strokeWidth = 10 * density
        canvas.drawPath(line, drawing)
        drawing.color = BLUE
        drawing.strokeWidth = 5.5f * density
        canvas.drawPath(line, drawing)
        drawing.style = Paint.Style.FILL
    }

    private fun drawMarker(canvas: Canvas, point: PointF, label: String, color: Int) {
        val x = point.x
        val y = point.y
        drawing.color = (color and 0x00FFFFFF) or 0x29000000
        canvas.drawCircle(x, y, 20 * density, drawing)
        drawing.color = Color.WHITE
        canvas.drawCircle(x, y, 15 * density, drawing)
        drawing.color = color
        canvas.drawCircle(x, y, 12.5f * density, drawing)
        drawing.color = Color.WHITE
        canvas.drawCircle(x, y - 3.5f * density, 2.8f * density, drawing)
        canvas.drawOval(x - 5 * density, y + 1f * density, x + 5 * density, y + 6f * density, drawing)
        if (label.isBlank()) return
        canvas.drawRoundRect(x - 38 * density, y + 22 * density, x + 38 * density, y + 45 * density, 12 * density, 12 * density, drawing)
        labelPaint.color = color
        labelPaint.textAlign = Paint.Align.CENTER
        val visible = TextUtils.ellipsize(label, labelPaint, 66 * density, TextUtils.TruncateAt.END)
        canvas.drawText(visible.toString(), x, y + 37 * density, labelPaint)
    }

    companion object {
        const val BLUE = 0xFF0047AB.toInt()
        const val RED = 0xFFCC1940.toInt()
    }
}
