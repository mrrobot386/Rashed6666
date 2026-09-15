package com.example.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.random.Random

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val BAR_COUNT = 20
    }

    private val barHeights = FloatArray(BAR_COUNT) { 0.1f }
    private val targetHeights = FloatArray(BAR_COUNT) { 0.1f }

    private var currentAmplitude: Float = 0f
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF1744")
    }

    private val barRect = RectF()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 50
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            updateWaveform()
            invalidate()
        }
    }

    init {
        startAnimation()
    }

    fun startAnimation() {
        if (!animator.isRunning) {
            animator.start()
        }
    }

    fun stopAnimation() {
        animator.cancel()
        for (i in 0 until BAR_COUNT) {
            barHeights[i] = 0.05f
            targetHeights[i] = 0.05f
        }
        invalidate()
    }

    fun setAmplitude(rms: Float) {
        currentAmplitude = rms.coerceIn(0f, 1f)
    }

    private fun updateWaveform() {
        val rand = Random.Default
        for (i in 0 until BAR_COUNT) {
            val distFromCenter = 1f - (Math.abs(i - BAR_COUNT / 2f) / (BAR_COUNT / 2f))
            val base = 0.08f
            val dynamic = if (currentAmplitude > 0.01f) {
                (currentAmplitude * distFromCenter * (0.5f + rand.nextFloat() * 0.5f)).coerceIn(0.1f, 1f)
            } else {
                (base + rand.nextFloat() * 0.05f)
            }
            targetHeights[i] = dynamic
            // Lerp animation
            barHeights[i] += (targetHeights[i] - barHeights[i]) * 0.3f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val totalWidth = width.toFloat()
        val maxHeight = height.toFloat()
        val barWidth = (totalWidth / (BAR_COUNT * 1.6f)).coerceAtLeast(4f)
        val spacing = (totalWidth - (BAR_COUNT * barWidth)) / (BAR_COUNT + 1)
        val centerY = maxHeight / 2f

        for (i in 0 until BAR_COUNT) {
            val h = (barHeights[i] * maxHeight).coerceIn(6f, maxHeight)
            val left = spacing + i * (barWidth + spacing)
            val top = centerY - (h / 2f)
            val right = left + barWidth
            val bottom = centerY + (h / 2f)

            barRect.set(left, top, right, bottom)
            val alpha = (150 + (barHeights[i] * 105)).toInt().coerceIn(120, 255)
            barPaint.alpha = alpha
            canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, barPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }
}
