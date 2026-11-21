package com.example.wifip2pchat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Holographic phased-array radar scanner view with conical sweep gradient,
 * orbital telemetry rings, perimeter ticks, and glowing peer blips.
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val gridColor = ContextCompat.getColor(context, R.color.radar_grid)
    private val sweepColor = ContextCompat.getColor(context, R.color.radar_sweep)
    private val blipColor = ContextCompat.getColor(context, R.color.radar_blip)

    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = sweepColor
        alpha = 90
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridColor
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
    }

    private val dashedRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridColor
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = sweepColor
        alpha = 110
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val sweepConePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = sweepColor
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
    }

    private val sweepGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = sweepColor
        style = Paint.Style.STROKE
        strokeWidth = 7f
        alpha = 70
        strokeCap = Paint.Cap.ROUND
    }

    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = blipColor
        style = Paint.Style.FILL
    }

    private val blipHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = blipColor
        alpha = 48
        style = Paint.Style.FILL
    }

    private val blipRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = blipColor
        alpha = 160
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val centerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = sweepColor
        alpha = 70
        style = Paint.Style.FILL
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridColor
        alpha = 90
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private var sweepAngle = 0f
    private var sweepShader: SweepGradient? = null
    private val sweepMatrix = Matrix()
    private val blips = mutableListOf<Pair<Float, Float>>()

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 2600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
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
            val angle = Math.toRadians(i * 137.5)
            val radius = 0.28f + (i * 0.14f) % 0.56f
            blips.add(
                Pair(
                    (0.5f + radius * cos(angle).toFloat()).coerceIn(0.14f, 0.86f),
                    (0.5f + radius * sin(angle).toFloat()).coerceIn(0.14f, 0.86f)
                )
            )
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val transparent = Color.argb(0, Color.red(sweepColor), Color.green(sweepColor), Color.blue(sweepColor))
        val soft = Color.argb(28, Color.red(sweepColor), Color.green(sweepColor), Color.blue(sweepColor))
        val bright = Color.argb(115, Color.red(sweepColor), Color.green(sweepColor), Color.blue(sweepColor))
        sweepShader = SweepGradient(
            cx,
            cy,
            intArrayOf(transparent, transparent, soft, bright),
            floatArrayOf(0f, 0.72f, 0.88f, 1.0f)
        )
        sweepConePaint.shader = sweepShader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(cx, cy) - 8f
        if (maxR <= 0f) return

        // Conical sweep gradient cone
        sweepShader?.let { shader ->
            sweepMatrix.setRotate(sweepAngle, cx, cy)
            shader.setLocalMatrix(sweepMatrix)
            canvas.drawCircle(cx, cy, maxR, sweepConePaint)
        }

        // Orbital telemetry rings (mix of solid & dashed)
        canvas.drawCircle(cx, cy, maxR * 0.25f, gridPaint)
        canvas.drawCircle(cx, cy, maxR * 0.50f, dashedRingPaint)
        canvas.drawCircle(cx, cy, maxR * 0.75f, gridPaint)
        canvas.drawCircle(cx, cy, maxR, outerRingPaint)

        // Perimeter cardinal & 30-degree ticks
        for (deg in 0 until 360 step 30) {
            val rad = Math.toRadians(deg.toDouble())
            val tickLen = if (deg % 90 == 0) 7f else 4f
            val innerR = maxR - tickLen
            canvas.drawLine(
                cx + innerR * cos(rad).toFloat(),
                cy + innerR * sin(rad).toFloat(),
                cx + maxR * cos(rad).toFloat(),
                cy + maxR * sin(rad).toFloat(),
                tickPaint
            )
        }

        // Crosshairs
        canvas.drawLine(cx, cy - maxR, cx, cy + maxR, crosshairPaint)
        canvas.drawLine(cx - maxR, cy, cx + maxR, cy, crosshairPaint)

        // Leading sweep beam
        val sweepRad = Math.toRadians(sweepAngle.toDouble())
        val endX = cx + maxR * cos(sweepRad).toFloat()
        val endY = cy + maxR * sin(sweepRad).toFloat()
        canvas.drawLine(cx, cy, endX, endY, sweepGlowPaint)
        canvas.drawLine(cx, cy, endX, endY, sweepPaint)

        // Discovered peer blips with target rings
        blips.forEach { (nx, ny) ->
            val bx = nx * width
            val by = ny * height
            canvas.drawCircle(bx, by, 13f, blipHaloPaint)
            canvas.drawCircle(bx, by, 8f, blipRingPaint)
            canvas.drawCircle(bx, by, 4.2f, blipPaint)
        }

        // Center node
        canvas.drawCircle(cx, cy, 8f, centerGlowPaint)
        canvas.drawCircle(cx, cy, 3.8f, centerDotPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }
}
