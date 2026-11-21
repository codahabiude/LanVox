package com.example.wifip2pchat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Custom radar/scanner view that draws concentric circles and a sweeping green line.
 * Displays device blips as green dots on the radar.
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val gridColor = ContextCompat.getColor(context, R.color.radar_grid)
    private val sweepColor = ContextCompat.getColor(context, R.color.radar_sweep)
    private val blipColor = ContextCompat.getColor(context, R.color.radar_blip)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridColor
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = sweepColor
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val sweepGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = sweepColor
        style = Paint.Style.STROKE
        strokeWidth = 6f
        alpha = 60
    }

    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = blipColor
        style = Paint.Style.FILL
    }

    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridColor
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private var sweepAngle = 0f
    private val blips = mutableListOf<Pair<Float, Float>>() // normalized (0..1, 0..1)

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 3000
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            sweepAngle = it.animatedValue as Float
            invalidate()
        }
    }

    fun startSweeping() {
        if (!animator.isRunning) animator.start()
    }

    fun stopSweeping() {
        animator.cancel()
        sweepAngle = 0f
        invalidate()
    }

    fun setBlipCount(count: Int) {
        blips.clear()
        for (i in 0 until count) {
            val angle = Math.toRadians((i * 137.5).toDouble()) // golden angle distribution
            val radius = 0.3f + (i * 0.15f) % 0.6f
            blips.add(Pair(
                (0.5f + radius * Math.cos(angle).toFloat()).coerceIn(0.1f, 0.9f),
                (0.5f + radius * Math.sin(angle).toFloat()).coerceIn(0.1f, 0.9f)
            ))
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val maxR = Math.min(cx, cy) - 8f

        // Concentric circles (4 rings)
        for (i in 1..4) {
            val r = maxR * i / 4f
            canvas.drawCircle(cx, cy, r, gridPaint)
        }

        // Crosshairs
        canvas.drawLine(cx, cy - maxR, cx, cy + maxR, crosshairPaint)
        canvas.drawLine(cx - maxR, cy, cx + maxR, cy, crosshairPaint)

        // Sweep line
        val sweepRad = Math.toRadians(sweepAngle.toDouble())
        val endX = cx + maxR * Math.cos(sweepRad).toFloat()
        val endY = cy + maxR * Math.sin(sweepRad).toFloat()
        canvas.drawLine(cx, cy, endX, endY, sweepGlowPaint)
        canvas.drawLine(cx, cy, endX, endY, sweepPaint)

        // Sweep trail (arc fade)
        val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val rect = RectF(cx - maxR, cy - maxR, cx + maxR, cy + maxR)
        for (i in 1..8) {
            val trailAngle = sweepAngle - i * 5f
            trailPaint.color = sweepColor
            trailPaint.alpha = 80 - i * 10
            canvas.drawArc(rect, trailAngle, 5f, false, trailPaint)
        }

        // Blips
        blips.forEach { (nx, ny) ->
            val bx = nx * width
            val by = ny * height
            canvas.drawCircle(bx, by, 5f, blipPaint)
            // Glow
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = blipColor
                alpha = 40
                style = Paint.Style.FILL
            }
            canvas.drawCircle(bx, by, 12f, glowPaint)
        }

        // Center dot (YOU)
        canvas.drawCircle(cx, cy, 4f, centerDotPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }
}
