package com.example.guardian_iot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Small, axis-free trend line in the spirit of Apple Health.
 *
 * It only draws values already persisted or authorized by the app; it never invents samples.
 * The line is smoothed with a Catmull-Rom to Bezier conversion and sits over a faint vertical
 * gradient, with a single emphasized point for the latest reading.
 */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var lineColor: Int = Color.parseColor("#0E48D6")
        set(value) {
            field = value
            strokePaint.color = value
            dotPaint.color = value
            invalidate()
        }

    var fillColor: Int = Color.argb(34, 14, 72, 214)
        set(value) {
            field = value
            invalidate()
        }

    var dotRingColor: Int = Color.WHITE
        set(value) {
            field = value
            dotRingPaint.color = value
            invalidate()
        }

    private var points = FloatArray(0)

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = lineColor
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = lineColor }
    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = dotRingColor }

    fun setPoints(values: List<Float>) {
        points = values.filter { it.isFinite() }.toFloatArray()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        if (points.isEmpty()) return
        if (points.size == 1) {
            drawLatestPoint(canvas, width / 2f, height / 2f)
            return
        }

        val insetX = dp(3f)
        val insetY = dp(4f)
        val usableWidth = (width - insetX * 2f).coerceAtLeast(1f)
        val usableHeight = (height - insetY * 2f).coerceAtLeast(1f)
        val min = points.min()
        val max = points.max()
        val flat = max - min < 0.0001f
        val range = (max - min).takeIf { it > 0.0001f } ?: 1f

        fun xAt(index: Int): Float =
            insetX + usableWidth * (index.toFloat() / (points.size - 1).toFloat())

        fun yAt(value: Float): Float =
            if (flat) insetY + usableHeight / 2f
            else insetY + usableHeight - ((value - min) / range) * usableHeight

        val line = Path().apply {
            moveTo(xAt(0), yAt(points[0]))
            for (index in 0 until points.size - 1) {
                val p0 = points[(index - 1).coerceAtLeast(0)]
                val p1 = points[index]
                val p2 = points[index + 1]
                val p3 = points[(index + 2).coerceAtMost(points.size - 1)]
                val x1 = xAt(index)
                val y1 = yAt(p1)
                val x2 = xAt(index + 1)
                val y2 = yAt(p2)
                val c1x = x1 + (x2 - xAt((index - 1).coerceAtLeast(0))) / 6f
                val c1y = y1 + (y2 - yAt(p0)) / 6f
                val c2x = x2 - (xAt((index + 2).coerceAtMost(points.size - 1)) - x1) / 6f
                val c2y = y2 - (yAt(p3) - y1) / 6f
                cubicTo(c1x, c1y, c2x, c2y, x2, y2)
            }
        }

        val area = Path(line).apply {
            lineTo(xAt(points.size - 1), height.toFloat())
            lineTo(xAt(0), height.toFloat())
            close()
        }
        fillPaint.shader = LinearGradient(
            0f,
            insetY,
            0f,
            height.toFloat(),
            fillColor,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(area, fillPaint)
        canvas.drawPath(line, strokePaint)
        drawLatestPoint(canvas, xAt(points.size - 1), yAt(points.last()))
    }

    private fun drawLatestPoint(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, dp(5.2f), dotRingPaint)
        canvas.drawCircle(x, y, dp(3.2f), dotPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
