package com.fyp.safesyncwatch.theme

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.fyp.safesyncwatch.R

class PulseHeartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val pulseColor = Color.RED
    private val iconColor = Color.WHITE
    private val pulseCount = 3
    private val innerSize = 80f
    private val pulseSize = 200f
    private val pulseDuration = 2000L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    private var animationValue = 0f
    private val heartDrawable: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_heart)

    init {
        startPulse()
    }

    private fun startPulse() {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = pulseDuration
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                animationValue = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        val maxRadius = pulseSize / 2
        val innerRadius = innerSize / 2

        for (i in 0 until pulseCount) {
            val waveProgress = (animationValue + (i.toFloat() / pulseCount)) % 1f
            val radius = innerRadius + (maxRadius - innerRadius) * waveProgress
            val opacity = 1f - waveProgress
            if (radius > innerRadius && opacity > 0) {
                paint.color = pulseColor
                paint.alpha = (opacity * 180).toInt()
                canvas.drawCircle(centerX, centerY, radius, paint)
            }
        }

        // Draw inner circle
        paint.color = pulseColor
        paint.alpha = 120
        canvas.drawCircle(centerX, centerY, innerRadius, paint)

        // Draw heart icon
        heartDrawable?.let {
            val iconSize = innerSize * 0.6f
            val left = (centerX - iconSize / 2).toInt()
            val top = (centerY - iconSize / 2).toInt()
            val right = (centerX + iconSize / 2).toInt()
            val bottom = (centerY + iconSize / 2).toInt()
            it.setBounds(left, top, right, bottom)
            it.setTint(iconColor)
            it.draw(canvas)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
