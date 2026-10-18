package com.taptrail.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Full-screen transparent overlay view that renders an animated visual trail
 * connecting the floating button to the target UI element, along with a
 * pulsing target highlight spotlight around the element bounding box.
 */
class TrailOverlayView(context: Context) : View(context) {

    private val trailPath = Path()
    private val pathMeasure = PathMeasure()
    private var pathLength = 0f

    private var targetRect: RectF? = null
    private var targetCenterX = 0f
    private var targetCenterY = 0f
    private var isTrailActive = false

    // Particle animation progress (0f..1f)
    private var particleProgress = 0f
    private var pulseAlpha = 1f
    private var rippleRadius = 0f

    // Animators
    private var particleAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private var rippleAnimator: ValueAnimator? = null

    // Paints
    private val trailGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#4D1A73E8") // Semi-transparent glow
    }

    private val trailLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#1A73E8")
    }

    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E5FF") // Electric cyan particle
    }

    private val particleGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#6600E5FF")
    }

    private val targetBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.parseColor("#1A73E8")
        pathEffect = DashPathEffect(floatArrayOf(16f, 10f), 0f)
    }

    private val targetFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#181A73E8")
    }

    private val cornerBracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#1A73E8")
    }

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#34A853") // Green tap ripple
    }

    init {
        // Run hardware acceleration for smooth 60fps rendering
        setLayerType(LAYER_TYPE_HARDWARE, null)
        setupAnimators()
    }

    private fun setupAnimators() {
        // Flowing particles along the trail path
        particleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                particleProgress = va.animatedValue as Float
                invalidate()
            }
        }

        // Target box pulse animation
        pulseAnimator = ValueAnimator.ofFloat(0.45f, 1.0f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { va ->
                pulseAlpha = va.animatedValue as Float
                targetBoxPaint.alpha = (pulseAlpha * 255).toInt()
                cornerBracketPaint.alpha = (pulseAlpha * 255).toInt()
            }
        }

        // Tap ripple beacon animation at target center
        rippleAnimator = ValueAnimator.ofFloat(0f, 40f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { va ->
                rippleRadius = va.animatedValue as Float
                val alpha = ((1f - (rippleRadius / 40f)) * 220).toInt().coerceIn(0, 255)
                ripplePaint.alpha = alpha
            }
        }
    }

    /**
     * Set trail from button position to target center and element bounding box.
     */
    fun setTrail(
        startX: Float,
        startY: Float,
        targetBbox: RectF?,
        centerX: Float,
        centerY: Float
    ) {
        targetRect = targetBbox
        targetCenterX = centerX
        targetCenterY = centerY
        isTrailActive = true

        buildTrailPath(startX, startY, centerX, centerY)
        startAnimations()
        invalidate()
    }

    private fun buildTrailPath(x1: Float, y1: Float, x2: Float, y2: Float) {
        trailPath.reset()
        trailPath.moveTo(x1, y1)

        // Generate dynamic control points for a smooth organic S-curve / arc
        val dx = x2 - x1
        val dy = y2 - y1

        val ctrl1X = x1 + dx * 0.25f
        val ctrl1Y = y1 + dy * 0.75f - (dx * 0.15f)

        val ctrl2X = x1 + dx * 0.75f
        val ctrl2Y = y1 + dy * 0.25f + (dx * 0.15f)

        trailPath.cubicTo(ctrl1X, ctrl1Y, ctrl2X, ctrl2Y, x2, y2)

        pathMeasure.setPath(trailPath, false)
        pathLength = pathMeasure.length

        // Update trail gradient along the path
        trailLinePaint.shader = LinearGradient(
            x1, y1, x2, y2,
            Color.parseColor("#1A73E8"),
            Color.parseColor("#00E5FF"),
            Shader.TileMode.CLAMP
        )
    }

    private fun startAnimations() {
        if (particleAnimator?.isStarted != true) particleAnimator?.start()
        if (pulseAnimator?.isStarted != true) pulseAnimator?.start()
        if (rippleAnimator?.isStarted != true) rippleAnimator?.start()
    }

    fun clearTrail() {
        isTrailActive = false
        targetRect = null
        trailPath.reset()
        particleAnimator?.cancel()
        pulseAnimator?.cancel()
        rippleAnimator?.cancel()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isTrailActive) return

        // 1. Draw target element spotlight box if available
        targetRect?.let { rect ->
            // Subtle highlight fill
            canvas.drawRoundRect(rect, 16f, 16f, targetFillPaint)
            // Dashed pulsing border
            canvas.drawRoundRect(rect, 16f, 16f, targetBoxPaint)

            // Corner brackets
            val bracketLen = 22f
            // Top-left
            canvas.drawLine(rect.left, rect.top, rect.left + bracketLen, rect.top, cornerBracketPaint)
            canvas.drawLine(rect.left, rect.top, rect.left, rect.top + bracketLen, cornerBracketPaint)
            // Top-right
            canvas.drawLine(rect.right, rect.top, rect.right - bracketLen, rect.top, cornerBracketPaint)
            canvas.drawLine(rect.right, rect.top, rect.right, rect.top + bracketLen, cornerBracketPaint)
            // Bottom-left
            canvas.drawLine(rect.left, rect.bottom, rect.left + bracketLen, rect.bottom, cornerBracketPaint)
            canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - bracketLen, cornerBracketPaint)
            // Bottom-right
            canvas.drawLine(rect.right, rect.bottom, rect.right - bracketLen, rect.bottom, cornerBracketPaint)
            canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - bracketLen, cornerBracketPaint)
        }

        // 2. Draw curved trail path
        if (pathLength > 0) {
            canvas.drawPath(trailPath, trailGlowPaint)
            canvas.drawPath(trailPath, trailLinePaint)

            // 3. Draw animated flowing particles along the trail
            val pos = FloatArray(2)
            val tan = FloatArray(2)
            val numParticles = 6

            for (i in 0 until numParticles) {
                val offsetFraction = (particleProgress + (i.toFloat() / numParticles)) % 1f
                val distance = offsetFraction * pathLength
                if (pathMeasure.getPosTan(distance, pos, tan)) {
                    val particleX = pos[0]
                    val particleY = pos[1]

                    // Outer glow
                    canvas.drawCircle(particleX, particleY, 11f, particleGlowPaint)
                    // Inner electric dot
                    canvas.drawCircle(particleX, particleY, 6f, particlePaint)
                }
            }
        }

        // 4. Draw ripple beacon at target center
        if (targetCenterX > 0 && targetCenterY > 0) {
            canvas.drawCircle(targetCenterX, targetCenterY, rippleRadius, ripplePaint)
            // Center pin dot
            val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.parseColor("#34A853")
            }
            canvas.drawCircle(targetCenterX, targetCenterY, 6f, centerDotPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        clearTrail()
    }
}
