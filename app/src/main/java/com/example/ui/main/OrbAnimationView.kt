package com.example.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class OrbAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentState: OrbState = OrbState.IDLE
    private var currentAmplitude: Float = 0f

    // Animations
    private var pulseScale = 1.0f
    private var glowAlpha = 180
    private var rotationAngle = 0f
    private var waveOffset = 0f
    private var thinkingAngle = 0f
    private var particleAngle = 0f

    private val pulseAnimator = ValueAnimator.ofFloat(1.0f, 1.15f, 1.0f).apply {
        duration = 1500
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            pulseScale = it.animatedValue as Float
            glowAlpha = (120 + 100 * (pulseScale - 1.0f) / 0.15f).toInt().coerceIn(100, 240)
            invalidate()
        }
    }

    private val rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 6000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotationAngle = it.animatedValue as Float
            waveOffset = (waveOffset + 0.1f) % (2f * PI.toFloat())
            particleAngle = (particleAngle + 0.05f) % (2f * PI.toFloat())
            invalidate()
        }
    }

    private val thinkingAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            thinkingAngle = it.animatedValue as Float
            if (currentState == OrbState.THINKING) {
                invalidate()
            }
        }
    }

    // Paints
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val thinkingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 6f
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val ringRect = RectF()
    private val thinkingRect = RectF()

    init {
        pulseAnimator.start()
        rotationAnimator.start()
        thinkingAnimator.start()
    }

    fun setState(state: OrbState) {
        if (currentState != state) {
            currentState = state
            when (state) {
                OrbState.IDLE -> {
                    pulseAnimator.duration = 1500
                    rotationAnimator.duration = 8000
                }
                OrbState.LISTENING -> {
                    pulseAnimator.duration = 1000
                    rotationAnimator.duration = 4000
                }
                OrbState.SPEAKING -> {
                    pulseAnimator.duration = 600
                    rotationAnimator.duration = 2500
                }
                OrbState.THINKING -> {
                    pulseAnimator.duration = 900
                    rotationAnimator.duration = 3000
                }
                OrbState.ACTIVE -> {
                    pulseAnimator.duration = 800
                    rotationAnimator.duration = 3500
                }
            }
            invalidate()
        }
    }

    fun setAmplitude(rms: Float) {
        currentAmplitude = rms.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = minOf(cx, cy) * 0.52f
        val reactiveRadius = baseRadius * (pulseScale + currentAmplitude * 0.25f)

        // Determine theme colors by state
        val (colorStart, colorEnd) = when (currentState) {
            OrbState.IDLE -> Pair(Color.parseColor("#B71C1C"), Color.parseColor("#880E4F"))
            OrbState.LISTENING -> Pair(Color.parseColor("#FF1744"), Color.parseColor("#D500F9"))
            OrbState.SPEAKING -> Pair(Color.parseColor("#E040FB"), Color.parseColor("#FF1744"))
            OrbState.THINKING -> Pair(Color.parseColor("#40C4FF"), Color.parseColor("#00B0FF"))
            OrbState.ACTIVE -> Pair(Color.parseColor("#FF1744"), Color.parseColor("#D500F9"))
        }

        // 1. Radial Glow Layer
        val glowRadius = reactiveRadius * 1.6f
        glowPaint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(
                Color.argb(glowAlpha, Color.red(colorStart), Color.green(colorStart), Color.blue(colorStart)),
                Color.argb((glowAlpha * 0.4f).toInt(), Color.red(colorEnd), Color.green(colorEnd), Color.blue(colorEnd)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0.0f, 0.7f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)

        // 2. Core Orb with 3D sphere depth
        corePaint.shader = RadialGradient(
            cx - reactiveRadius * 0.25f,
            cy - reactiveRadius * 0.25f,
            reactiveRadius * 1.15f,
            intArrayOf(Color.WHITE, colorStart, colorEnd, Color.BLACK),
            floatArrayOf(0.0f, 0.35f, 0.85f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, reactiveRadius, corePaint)

        // 3. Rotating Rings (3 Dashed Rings)
        canvas.save()
        canvas.rotate(rotationAngle, cx, cy)
        for (i in 1..3) {
            val ringRadius = reactiveRadius + i * 16f
            ringPaint.strokeWidth = (4f - i * 0.8f)
            ringPaint.color = Color.argb(
                (140 - i * 30),
                Color.red(colorStart),
                Color.green(colorStart),
                Color.blue(colorStart)
            )
            val dashStep = (18f + i * 8f)
            ringPaint.pathEffect = DashPathEffect(floatArrayOf(dashStep, dashStep * 0.7f), rotationAngle * i)
            canvas.drawCircle(cx, cy, ringRadius, ringPaint)
        }
        canvas.restore()

        // 4. Wave Rings (Sine Waves, amplitude reactive)
        if (currentState != OrbState.IDLE) {
            wavePaint.strokeWidth = 2.5f
            wavePaint.color = Color.argb(170, Color.red(colorEnd), Color.green(colorEnd), Color.blue(colorEnd))
            val waveRadius = reactiveRadius + 12f + currentAmplitude * 30f
            canvas.drawCircle(cx, cy, waveRadius, wavePaint)
        }

        // 5. Thinking Arc (spinning arcs in thinking mode)
        if (currentState == OrbState.THINKING) {
            thinkingPaint.color = colorStart
            thinkingRect.set(cx - reactiveRadius * 1.25f, cy - reactiveRadius * 1.25f, cx + reactiveRadius * 1.25f, cy + reactiveRadius * 1.25f)
            canvas.drawArc(thinkingRect, thinkingAngle, 100f, false, thinkingPaint)
            canvas.drawArc(thinkingRect, (thinkingAngle + 180f) % 360f, 100f, false, thinkingPaint)
        }

        // 6. Particles (12 orbiting dots when active or speaking)
        if (currentState == OrbState.ACTIVE || currentState == OrbState.SPEAKING || currentState == OrbState.LISTENING) {
            val numParticles = 12
            for (p in 0 until numParticles) {
                val angle = particleAngle + (p.toFloat() / numParticles) * (2f * PI.toFloat())
                val orbitDist = reactiveRadius + 22f + (p % 3) * 12f + currentAmplitude * 20f
                val px = cx + cos(angle) * orbitDist
                val py = cy + sin(angle) * orbitDist
                val pRadius = 2.5f + (p % 3) * 1.2f
                particlePaint.color = if (p % 2 == 0) colorStart else colorEnd
                particlePaint.alpha = (160 + (p * 8)).coerceIn(120, 255)
                canvas.drawCircle(px, py, pRadius, particlePaint)
            }
        }

        // 7. Inner Highlight (Ethereal glass reflection)
        highlightPaint.shader = RadialGradient(
            cx - reactiveRadius * 0.35f,
            cy - reactiveRadius * 0.35f,
            reactiveRadius * 0.5f,
            intArrayOf(Color.argb(160, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0.0f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx - reactiveRadius * 0.35f, cy - reactiveRadius * 0.35f, reactiveRadius * 0.45f, highlightPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator.cancel()
        rotationAnimator.cancel()
        thinkingAnimator.cancel()
    }
}
