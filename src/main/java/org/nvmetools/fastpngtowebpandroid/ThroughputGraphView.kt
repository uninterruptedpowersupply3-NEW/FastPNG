package org.nvmetools.fastpngtowebpandroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class ThroughputGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        strokeWidth = resources.displayMetrics.density
        style = Paint.Style.STROKE
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22C55E")
        strokeWidth = resources.displayMetrics.density * 2f
        style = Paint.Style.STROKE
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1")
        textSize = resources.displayMetrics.scaledDensity * 12f
    }

    private val path = Path()
    private var points: List<Float> = emptyList()

    fun setPoints(values: List<Float>) {
        points = values
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val widthF = width.toFloat()
        val heightF = height.toFloat()
        canvas.drawRect(0f, 0f, widthF, heightF, backgroundPaint)

        if (widthF <= 0f || heightF <= 0f) {
            return
        }

        val padding = resources.displayMetrics.density * 12f
        val graphLeft = padding
        val graphTop = padding
        val graphRight = widthF - padding
        val graphBottom = heightF - padding * 1.6f
        val graphWidth = graphRight - graphLeft
        val graphHeight = graphBottom - graphTop

        for (index in 0..3) {
            val y = graphTop + graphHeight * index / 3f
            canvas.drawLine(graphLeft, y, graphRight, y, gridPaint)
        }

        if (points.isEmpty()) {
            canvas.drawText("No throughput data yet", graphLeft, graphBottom, labelPaint)
            return
        }

        val maxValue = max(points.maxOrNull() ?: 1f, 1f)
        val lastValue = points.lastOrNull() ?: 0f
        path.reset()

        points.forEachIndexed { index, value ->
            val x = if (points.size == 1) {
                graphLeft
            } else {
                graphLeft + graphWidth * index / (points.size - 1).toFloat()
            }
            val normalized = (value / maxValue).coerceIn(0f, 1f)
            val y = graphBottom - normalized * graphHeight
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        canvas.drawPath(path, linePaint)
        canvas.drawText("Speed graph", graphLeft, graphTop - resources.displayMetrics.density, labelPaint)
        canvas.drawText(
            String.format("Now %.1f img/s | Peak %.1f", lastValue, maxValue),
            graphLeft,
            heightF - padding * 0.35f,
            labelPaint,
        )
    }
}
