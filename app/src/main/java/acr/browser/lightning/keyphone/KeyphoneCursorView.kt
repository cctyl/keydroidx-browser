package acr.browser.lightning.keyphone

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.OvershootInterpolator

/**
 * 虚拟鼠标微型光标视图（40dp x 40dp）：
 *
 * 核心性能与交互设计：
 * - 尺寸固定为微小图层（40dp），绝不覆盖全屏；
 * - 光标移动通过 RenderNode 的 [translationX] / [translationY] 矩阵变换完成，
 *   完全不触发 CPU/GPU 重新构建全屏 DisplayList，0 重绘开销稳定 60FPS；
 * - 杜绝低端芯片（展讯等）GPU 在全屏透明图层和硬件加速 WebView 之间发生 [nSyncAndDrawFrame] 同步互斥卡死；
 * - 贴边指示仅在方向变化时重绘；
 * - 点击时提供清晰灵动的按压动效与波纹反馈：
 *   1. 按下 (DOWN)：快速缩至 0.76x 下沉，中心指示点变大变亮切换电光青蓝，带来清晰按压质感；
 *   2. 抬起 (UP)：弹性回弹至 1.0x，并向外扩散一圈优雅的点击光波（Click Ripple）；
 *   3. 动画仅限微小图层内部，零卡顿，彻底解决“不知道点没点到”的反馈缺失问题。
 */
class KeyphoneCursorView(context: Context) : View(context) {

    companion object {
        const val COLOR_RING_FILL = 0xE6FFFFFF.toInt()
        const val COLOR_RING_STROKE = 0xFF101418.toInt()
        const val COLOR_CROSS = 0xFFF2F5F8.toInt()
        const val COLOR_DOT_NORMAL = 0xFFFF8C1A.toInt()      // 活力橙
        const val COLOR_DOT_PRESSED = 0xFF00E5FF.toInt()     // 电光青蓝（按下高对比高光）
        const val COLOR_RING_PRESSED = 0xFFE0F7FA.toInt()
        const val COLOR_EDGE_FILL = 0xE63478D6.toInt()
        const val COLOR_EDGE_STROKE = 0xFF101418.toInt()
        const val COLOR_RIPPLE = 0xCC00E5FF.toInt()          // 点击扩散光波
    }

    private val density = resources.displayMetrics.density
    private val sizePx = (40f * density).toInt()
    private val halfSize = sizePx / 2f
    private val radius = 7f * density
    private val strokeW = 1.5f * density

    private var edgeDir = KeyphoneShellOverlay.DIR_NONE
    private var isPressedState = false
    private var rippleProgress = 0f
    private var rippleAnimator: ValueAnimator? = null

    private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_RING_FILL
    }
    private val ringStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_RING_STROKE
        strokeWidth = strokeW
    }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_CROSS
        strokeCap = Paint.Cap.ROUND
        strokeWidth = strokeW
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_DOT_NORMAL
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = COLOR_RIPPLE
    }
    private val edgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_EDGE_FILL
    }
    private val edgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_EDGE_STROKE
        strokeWidth = strokeW
    }
    private val arrowPath = Path()

    init {
        isFocusable = false
        isFocusableInTouchMode = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    /**
     * 高速更新光标坐标（RenderNode 纯矩阵位移，0 重绘开销，稳定 60FPS）。
     */
    fun updatePosition(x: Int, y: Int, atEdgeDir: Int) {
        translationX = x - halfSize
        translationY = y - halfSize
        if (edgeDir != atEdgeDir) {
            edgeDir = atEdgeDir
            invalidate()
        }
    }

    /**
     * 设置按下状态（所见即所点的点击视觉动效反馈）
     */
    fun setPressedState(pressed: Boolean) {
        if (isPressedState == pressed) return
        isPressedState = pressed

        if (pressed) {
            // 按下：快速缩小，下沉感
            animate().cancel()
            animate()
                .scaleX(0.76f)
                .scaleY(0.76f)
                .setDuration(60)
                .start()
            dot.color = COLOR_DOT_PRESSED
            ringFill.color = COLOR_RING_PRESSED
            invalidate()
        } else {
            // 抬起：回弹并扩散点击波纹
            animate().cancel()
            animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(160)
                .setInterpolator(OvershootInterpolator(1.6f))
                .start()
            dot.color = COLOR_DOT_NORMAL
            ringFill.color = COLOR_RING_FILL
            playClickRipple()
        }
    }

    private fun playClickRipple() {
        rippleAnimator?.cancel()
        rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            addUpdateListener { anim ->
                rippleProgress = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = halfSize
        val cy = halfSize

        canvas.save()
        canvas.translate(cx, cy)

        // 绘制点击扩散波纹
        if (rippleProgress > 0f && rippleProgress < 1f) {
            val rippleRadius = radius + (11f * density * rippleProgress)
            val alpha = ((1f - rippleProgress) * 220).toInt()
            ripplePaint.alpha = alpha
            canvas.drawCircle(0f, 0f, rippleRadius, ripplePaint)
        }

        if (edgeDir == KeyphoneShellOverlay.DIR_NONE) {
            canvas.drawCircle(0f, 0f, radius, ringFill)
            canvas.drawCircle(0f, 0f, radius, ringStroke)
            val crossLen = radius + 3.5f * density
            canvas.drawLine(0f, -radius, 0f, -crossLen, cross)
            canvas.drawLine(0f, radius, 0f, crossLen, cross)
            canvas.drawLine(-radius, 0f, -crossLen, 0f, cross)
            canvas.drawLine(radius, 0f, crossLen, 0f, cross)

            // 按下时中心点放大且变色，呈现坚实明确的触击反馈
            val dotRadius = if (isPressedState) 3.2f * density else 1.8f * density
            canvas.drawCircle(0f, 0f, dotRadius, dot)
        } else {
            canvas.drawCircle(0f, 0f, radius + 1.5f * density, edgeFill)
            canvas.drawCircle(0f, 0f, radius + 1.5f * density, edgeStroke)
            val arrowW = 3.6f * density
            val arrowH = 5f * density
            arrowPath.reset()
            arrowPath.moveTo(0f, -arrowH)
            arrowPath.lineTo(arrowW, arrowH * 0.5f)
            arrowPath.lineTo(0f, arrowH * 0.1f)
            arrowPath.lineTo(-arrowW, arrowH * 0.5f)
            arrowPath.close()

            canvas.save()
            when (edgeDir) {
                KeyphoneShellOverlay.DIR_UP -> Unit
                KeyphoneShellOverlay.DIR_DOWN -> canvas.rotate(180f)
                KeyphoneShellOverlay.DIR_LEFT -> canvas.rotate(-90f)
                KeyphoneShellOverlay.DIR_RIGHT -> canvas.rotate(90f)
            }
            canvas.drawPath(arrowPath, cross)
            canvas.restore()
        }
        canvas.restore()
    }
}
