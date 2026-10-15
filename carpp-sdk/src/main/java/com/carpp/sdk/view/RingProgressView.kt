package com.carpp.sdk.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 环形连接进度：背景环 + 渐变进度弧 + 中心百分比数字。
 *
 * ## 百分比是哪来的
 * 优先用协议下发的**真实**协商进度（[com.carpp.sdk.sdk.LinkState.progress]，0–100，
 * ICCOA 的 `onConnectingProgress` 在手机 Wi-Fi 直连 / 热点协商推进时持续回调）。
 * 当协议没给真实进度时，[setProgress] 的值由调用方按阶段映射而来：
 * ```
 *   Idle        →   0%
 *   Advertising →   0%   （等待连接，从 0 起）
 *   Pairing     →   0%   （出配对码，从 0 起；手机输入后由真实进度接管）
 *   Connected   → 100%
 * ```
 * 中心显示的就是这个值 —— 有真实进度时它反映真实进展，没有时是「走到哪一段了」。
 */
class RingProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 底环：进度未走到的部分。 */
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0x1FFFFFFF
    }

    /** 进度弧：从蓝到紫的渐变，跟随图标同一套配色。 */
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F1F5FC")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val percentSuffixPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A8B6CA")
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    private val arcRect = RectF()
    private var ringStroke = 0f
    private var current = 0f
    private var animator: ValueAnimator? = null

    init {
        // minSdk 16：硬件层下描边抗锯齿在部分车机 GPU 上会丢，留软件绘制更稳。
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /** @param target 0..100 的连接进度 */
    fun setProgress(target: Float, animate: Boolean = true) {
        val clamped = target.coerceIn(0f, 100f)
        if (!animate) {
            animator?.cancel()
            current = clamped
            invalidate()
            return
        }
        if (current == clamped) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(current, clamped).apply {
            duration = 550
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                current = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ringStroke = (minOf(w, h) * 0.085f).coerceAtLeast(6f)
        val inset = ringStroke / 2f + 2f
        arcRect.set(inset, inset, w - inset, h - inset)

        trackPaint.strokeWidth = ringStroke
        progressPaint.strokeWidth = ringStroke
        progressPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            Color.parseColor("#A6C8FF"), Color.parseColor("#8B5CF6"),
            Shader.TileMode.CLAMP,
        )

        val size = minOf(w, h).toFloat()
        percentPaint.textSize = size * 0.26f
        percentSuffixPaint.textSize = size * 0.13f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 12 点方向起画
        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)
        canvas.drawArc(arcRect, -90f, 360f * (current / 100f), false, progressPaint)

        // 中心百分比：数字大、% 小，两者基线对齐后整体居中
        val text = current.toInt().toString()
        val suffix = "%"
        val numWidth = percentPaint.measureText(text)
        val sufWidth = percentSuffixPaint.measureText(suffix)
        val total = numWidth + sufWidth * 0.85f

        val cx = width / 2f
        val baseline = cy()
        canvas.drawText(text, cx - total / 2f, baseline, percentPaint)
        canvas.drawText(suffix, cx - total / 2f + numWidth + sufWidth * 0.15f,
            baseline, percentSuffixPaint)
    }

    /** 数字在圆心略偏下，使「数字+%」整块视觉居中。 */
    private fun cy(): Float {
        val fm = percentPaint.fontMetrics
        return height / 2f - (fm.ascent + fm.descent) / 2f
    }
}
