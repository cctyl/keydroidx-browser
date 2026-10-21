package acr.browser.lightning.keyphone

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import io.github.cctyl.nokia.common.log.KeydroidxLog

/**
 * 按键机专用顶部极细炫彩动态加载进度条（2dp）。
 *
 * 核心设计与体验定位：
 * 1. 极细视觉 (2dp)：
 *    在 240×320 / 320×480 微屏上紧贴地址栏下方，既醒目又绝不侵占网页可视纵深；
 * 2. 炫彩流光 (Rainbow / Aurora Shimmer)：
 *    内部渐变色自左向右循环流动。在弱网或大网页加载停顿于某一进度（如 75%）时，
 *    流光依然持续流动，让用户清晰感知到网络链路仍在传输、页面仍在活跃加载，避免“死机”错觉；
 * 3. 完备的生命周期与低能耗优化：
 *    - 1~99%：自左向右平滑推进并保持流光；
 *    - 100%：快速冲过终点并平滑淡出，随后隐藏；
 *    - 主页态 / 离开屏幕 / 窗口隐藏时，自动销毁/暂停流光动画，零 CPU / GPU 浪费；
 *    - 零 onDraw 临时对象分配，60FPS 丝滑不丢帧。
 */
class KeyphoneRainbowProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "KeyphoneRainbowProgress"

        /** 流光一周期时长 (ms) */
        private const val SHIMMER_DURATION_MS = 1400L

        /** 进度平滑推进动画基准时长 (ms) */
        private const val PROGRESS_SMOOTH_DURATION_MS = 160L

        /** 达到 100% 时的淡出动画时长 (ms) */
        private const val FADE_OUT_DURATION_MS = 240L

        /**
         * 炫彩渐变色环：青蓝 → 蔚蓝 → 紫罗兰 → 霓虹粉 → 琥珀金 → 翠绿 → 青蓝（首尾闭环无缝循环）
         */
        private val RAINBOW_COLORS = intArrayOf(
            0xFF00F2FE.toInt(), // Electric Cyan
            0xFF4FACFE.toInt(), // Sky Blue
            0xFF7F00FF.toInt(), // Deep Violet
            0xFFFF007F.toInt(), // Neon Pink
            0xFFFFB300.toInt(), // Amber Gold
            0xFF00E676.toInt(), // Emerald Green
            0xFF00F2FE.toInt()  // Loop back to Electric Cyan
        )

        private val RAINBOW_POSITIONS = floatArrayOf(
            0.00f, 0.18f, 0.38f, 0.58f, 0.78f, 0.90f, 1.00f
        )
    }

    /** 当前绘制进度 (0f..100f) */
    private var currentProgress = 0f

    /** 目标进度 (0..100) */
    private var targetProgress = 0

    /** 底轨背景画笔（极弱半透明底轨，提升微小屏幕上的对比度） */
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33000000.toInt()
    }

    /** 炫彩流光主体画笔 */
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** 进度条顶端高亮光斑画笔 */
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xEEFFFFFF.toInt()
    }

    private val progressRect = RectF()
    private val shaderMatrix = Matrix()
    private var gradientShader: LinearGradient? = null

    /** 流光循环位移动画 */
    private var shimmerAnimator: ValueAnimator? = null

    /** 进度值插值平滑动画 */
    private var progressAnimator: ValueAnimator? = null

    /** 完成时的淡出动画 */
    private var fadeOutAnimator: ValueAnimator? = null

    private var shimmerOffset = 0f

    init {
        // 初始为不可见
        visibility = INVISIBLE
        alpha = 1f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            rebuildShader(w.toFloat(), h.toFloat())
        }
    }

    private fun rebuildShader(width: Float, height: Float) {
        try {
            // 设置宽度为宽度的 1.5 倍以保证流光平滑跨越整个屏幕
            val shaderWidth = width * 1.5f
            val shader = LinearGradient(
                0f, 0f, shaderWidth, 0f,
                RAINBOW_COLORS,
                RAINBOW_POSITIONS,
                Shader.TileMode.REPEAT
            )
            gradientShader = shader
            progressPaint.shader = shader
            updateShaderMatrix()
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "创建渐变着色器失败", t)
        }
    }

    private fun updateShaderMatrix() {
        val shader = gradientShader ?: return
        val w = width.toFloat()
        if (w <= 0f) return
        shaderMatrix.setTranslate(-w * shimmerOffset, 0f)
        shader.setLocalMatrix(shaderMatrix)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || currentProgress <= 0f) return

        // 1. 绘制极暗底轨
        canvas.drawRect(0f, 0f, w, h, trackPaint)

        // 2. 绘制炫彩填充进度
        val progressWidth = (w * (currentProgress / 100f)).coerceIn(0f, w)
        progressRect.set(0f, 0f, progressWidth, h)
        canvas.drawRect(progressRect, progressPaint)

        // 3. 绘制前端推进微光斑 (当进度在 1% ~ 99% 时展示推进感)
        if (currentProgress in 1f..99.5f && progressWidth > 2f) {
            val sparkRadius = h * 0.9f
            canvas.drawCircle(progressWidth - 0.5f, h / 2f, sparkRadius, sparkPaint)
        }
    }

    /**
     * 更新当前加载进度 (0..100)。
     *
     * @param progress 0~100 整数
     */
    fun setProgress(progress: Int) {
        val clamped = progress.coerceIn(0, 100)
        targetProgress = clamped

        if (clamped <= 0) {
            reset()
            return
        }

        if (clamped in 1..99) {
            // 确保可见度与透明度
            cancelFadeOut()
            if (visibility != VISIBLE) {
                visibility = VISIBLE
            }
            alpha = 1f
            startShimmerIfNeed()

            // 平滑推进当前进度
            animateProgressTo(clamped.toFloat(), PROGRESS_SMOOTH_DURATION_MS)
        } else {
            // 达到 100%：先快速冲顶，然后优雅淡出
            startShimmerIfNeed()
            animateProgressTo(100f, 100L) {
                startFadeOut()
            }
        }
    }

    /**
     * 立即重置并隐藏进度条。
     */
    fun reset() {
        targetProgress = 0
        currentProgress = 0f
        cancelAllAnimators()
        visibility = INVISIBLE
        alpha = 1f
        postInvalidateOnAnimation()
    }

    private fun animateProgressTo(target: Float, durationMs: Long, onEnd: (() -> Unit)? = null) {
        progressAnimator?.cancel()
        val start = currentProgress
        if (target < start) {
            // 若新进度小于当前进度（如页面重定向或换页），直接快退
            currentProgress = target
            invalidate()
            onEnd?.invoke()
            return
        }

        val animator = ValueAnimator.ofFloat(start, target).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                currentProgress = (va.animatedValue as Float).coerceIn(0f, 100f)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd?.invoke()
                }
            })
        }
        progressAnimator = animator
        animator.start()
    }

    private fun startShimmerIfNeed() {
        if (shimmerAnimator?.isRunning == true) return
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SHIMMER_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                shimmerOffset = va.animatedValue as Float
                updateShaderMatrix()
                invalidate()
            }
        }
        shimmerAnimator = animator
        animator.start()
    }

    private fun stopShimmer() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
    }

    private fun startFadeOut() {
        cancelFadeOut()
        val animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = FADE_OUT_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                alpha = va.animatedValue as Float
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = INVISIBLE
                    alpha = 1f
                    currentProgress = 0f
                    stopShimmer()
                }
            })
        }
        fadeOutAnimator = animator
        animator.start()
    }

    private fun cancelFadeOut() {
        fadeOutAnimator?.cancel()
        fadeOutAnimator = null
    }

    private fun cancelAllAnimators() {
        progressAnimator?.cancel()
        progressAnimator = null
        fadeOutAnimator?.cancel()
        fadeOutAnimator = null
        stopShimmer()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            stopShimmer()
        } else if (currentProgress in 1f..99.9f) {
            startShimmerIfNeed()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            stopShimmer()
        } else if (isShown && currentProgress in 1f..99.9f) {
            startShimmerIfNeed()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAllAnimators()
    }
}
