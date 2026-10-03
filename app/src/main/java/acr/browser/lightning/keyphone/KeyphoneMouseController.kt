package acr.browser.lightning.keyphone

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.webkit.WebView
import android.widget.FrameLayout
import io.github.cctyl.nokia.common.log.KeydroidxLog

/**
 * 按键机鼠标模式引擎：方向键驱动光标移动 + 光标贴边继续按键触发网页滚动。
 *
 * 移动/加速/连发数值与节奏照抄 keydroidx-foucs 的 FocusNavigationService：
 * - 单击步长 = 短边 * 0.015，下限 6px；
 * - 按住 140ms 后开始 16ms（约 60FPS）连发，连发起步步长 = 短边 * 0.005（下限 2px），
 *   800ms 内从 1.0x 线性加速到 6.5x，松手重置；
 *
 * 边缘滚动（光标贴边继续按键）设计约束：**速度恒定，任何情况下都不突变**。
 * - 全程只有一个滚动动画（[edgeAnimator]），速度恒为
 *   垂直 32% 高度/700ms、水平 72% 宽度/280ms（foucs 原值 0.18f/0.54f，按用户反馈提速）；
 * - 按下贴边方向键即启动，按住期间恒速滚动（动画时钟逐帧推进，丝滑无抖动）；
 * - 松手后不立即停，而是以同样速度滚满「一个步长」后自停（单击因此能平滑滚一段）；
 * - 到内容边界后封住该方向直至松手/换向。UP 由 Activity 自行消费（必然送达），
 *   不引入 foucs 的按压额度限制。不存在第二个滚动源，速度不可能叠加或突变。
 *
 * 边界判定基于 WebView 宿主（光标只在网页内容区活动）。滚动目标不再是文档根，而是
 * **光标下的元素**（见 [KeyphoneScroll] 脚本）：移动站把侧边抽屉做成 fixed 浮层 + 自己的
 * 滚动容器，只滚文档根会去滚抽屉**背后**的整页，而不是抽屉本身。
 *
 * @param ensureScrollModule 注入滚动脚本（幂等）；每次贴边滚动开始时调一次
 * @param pageBusyProvider 页面是否无响应；为真时**一律不碰 WebView**
 */
class KeyphoneMouseController(
    private val host: FrameLayout,
    private val cursorView: KeyphoneCursorView,
    private val webViewProvider: () -> WebView?,
    private val ensureScrollModule: (WebView) -> Unit,
    private val pageBusyProvider: () -> Boolean = { false }
) {

    companion object {
        private const val TAG = "KeyphoneMouse"

        private const val MOVE_REPEAT_DELAY_MS = 140L
        private const val MOVE_REPEAT_INTERVAL_MS = 16L
        private const val MOVE_ACCEL_DURATION_MS = 800L
        private const val ACCEL_FACTOR = 6.5f

        private const val SINGLE_STEP_RATIO = 0.015f
        private const val SINGLE_STEP_MIN_PX = 6
        private const val REPEAT_STEP_RATIO = 0.005f
        private const val REPEAT_STEP_MIN_PX = 2

        private const val PAGE_SCROLL_INTERVAL_MS = 450L
        private const val PAGE_SCROLL_INTERVAL_H_MS = 240L
        // 提升滚动灵敏度与顺畅度，长按长页面流畅推进
        private const val PAGE_SCROLL_STEP_RATIO_V = 0.45f
        private const val PAGE_SCROLL_STEP_RATIO_H = 0.65f

        /** 1~9 热区跳转动画时长 */
        private const val JUMP_DURATION_MS = 180L

        /**
         * 贴边滚动请求的最小间隔：每帧都发跨进程调用会把 UI 线程堵在等渲染进程上
         * （页面卡住时尤其致命，实测会导致 input ANR 被杀进程）。60ms ≈ 16 次/秒，
         * 攒起来的增量照原速发出，平均速度与手感不变。
         */
        private const val SCROLL_REQUEST_INTERVAL_MS = 60L

        /** 短边缩放基准（foucs 用屏幕短边，这里用宿主短边，视觉一致） */
        private fun shortSideOf(host: FrameLayout): Int =
            minOf(host.width, host.height).coerceAtLeast(1)
    }

    /** 按住尚未松开的逻辑方向（后按覆盖先按，同 foucs 单向连发模型） */
    private val heldDirs = ArrayDeque<Int>()
    private val handler = Handler(Looper.getMainLooper())

    private var cursorX = -1
    private var cursorY = -1
    private var repeatDir = 0
    private var moveRepeatStart = 0L
    private var moveSpeedScale = 1f

    /** 内容已到边界而封住的方向（松手或换向时解除） */
    private val limitDirs = HashSet<Int>()

    private var repeatRunnable: Runnable? = null

    /** 贴边滚动动画：唯一的滚动源，null 表示未在滚动 */
    private var edgeAnimator: ValueAnimator? = null

    /** 1~9 热区跳转动画，null 表示未在跳转 */
    private var jumpAnimator: ValueAnimator? = null

    /** 松手标记：置位后平滑减速缓冲一个微步即停 */
    private var edgeReleased = false

    /** 当前光标位置（宿主坐标系），供确认键转触摸点击使用 */
    val posX: Int get() = cursorX
    val posY: Int get() = cursorY

    /** 方向键按下：重置加速、走一步、启动连发定时器 */
    fun onDirectionDown(dir: Int) {
        // ROM 连发的重复 DOWN（foucs: DIR_DOWN_DEDUP_MS 去重同理）：
        // 手势已在进行则只视为「仍按住」信号，不重置加速与额度
        if (repeatDir == dir && repeatRunnable != null && heldDirs.contains(dir)) return
        // 新手势（含换向）：终止上一轮贴边滚动与热区跳转动画，重开一轮
        jumpAnimator?.cancel()
        jumpAnimator = null
        cancelEdgeAnimator()
        edgeReleased = false
        heldDirs.remove(dir)
        heldDirs.addLast(dir)
        repeatDir = dir
        limitDirs.clear()
        ensurePositionValid()
        moveSpeedScale = 1f
        moveCursor(dir)
        startRepeat(dir)
    }

    /** 方向键抬起：全部松开则停光标连发；贴边滚动标记松手，滚满本步后自停 */
    fun onDirectionUp(dir: Int) {
        heldDirs.remove(dir)
        if (heldDirs.isEmpty()) {
            stopMoveRepeat()
            edgeReleased = true
            moveSpeedScale = 1f
        }
    }

    /** 外部停止（Activity 切后台 / 退出网页态）：停连发并立即终止贴边滚动 */
    fun stopRepeat() {
        stopMoveRepeat()
        jumpAnimator?.cancel()
        jumpAnimator = null
        cancelEdgeAnimator()
    }

    /**
     * 1~9 热区跳转：光标平滑动画移动到目标点（只移动不点击，落点确认交给确认键）。
     * 期间按方向键会立即取消动画改由手势接管。
     */
    fun animateCursorTo(x: Int, y: Int) {
        val w = host.width
        val h = host.height
        if (w <= 0 || h <= 0) return
        ensurePositionValid()
        val targetX = x.coerceIn(1, w - 1)
        val targetY = y.coerceIn(1, h - 1)
        if (targetX == cursorX && targetY == cursorY) return
        stopMoveRepeat()
        cancelEdgeAnimator()
        jumpAnimator?.cancel()
        val startX = cursorX
        val startY = cursorY
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = JUMP_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                cursorX = Math.round(startX + (targetX - startX) * t)
                cursorY = Math.round(startY + (targetY - startY) * t)
                cursorView.updatePosition(cursorX, cursorY, KeyphoneShellOverlay.DIR_NONE)
            }
        }
        jumpAnimator = anim
        anim.start()
    }

    /** 停止光标连发定时器（不影响贴边滚动动画） */
    private fun stopMoveRepeat() {
        repeatRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable = null
        repeatDir = 0
    }

    /** 光标位置无效或宿主尺寸变化时重置到中心 */
    fun ensurePositionValid() {
        val w = host.width
        val h = host.height
        if (w <= 0 || h <= 0) return
        if (cursorX < 0 || cursorY < 0 || cursorX > w - 1 || cursorY > h - 1) {
            cursorX = w / 2
            cursorY = h / 2
            cursorView.updatePosition(cursorX, cursorY, KeyphoneShellOverlay.DIR_NONE)
        }
    }

    private fun startRepeat(dir: Int) {
        repeatRunnable?.let { handler.removeCallbacks(it) }
        moveRepeatStart = System.currentTimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                if (repeatDir != dir || heldDirs.isEmpty()) return
                val now = System.currentTimeMillis()
                // 800ms 内从 1.0x 线性加速到 6.5x（foucs: MOVE_ACCEL_DURATION_MS / ACCEL_FACTOR）
                val t = ((now - moveRepeatStart).toFloat() / MOVE_ACCEL_DURATION_MS).coerceIn(0f, 1f)
                moveSpeedScale = 1f + (ACCEL_FACTOR - 1f) * t
                moveCursor(dir)
                handler.postDelayed(this, MOVE_REPEAT_INTERVAL_MS)
            }
        }
        repeatRunnable = runnable
        handler.postDelayed(runnable, MOVE_REPEAT_DELAY_MS)
    }

    private fun cursorStep(): Int {
        val shortSide = shortSideOf(host)
        return if (!isRepeating()) {
            // 单次点按：短边 * 0.015，下限 6px
            maxOf(SINGLE_STEP_MIN_PX, Math.round(shortSide * SINGLE_STEP_RATIO))
        } else {
            // 连发中：短边 * 0.005 起步，随 moveSpeedScale 加速
            val base = maxOf(REPEAT_STEP_MIN_PX, Math.round(shortSide * REPEAT_STEP_RATIO))
            maxOf(1, Math.round(base * moveSpeedScale))
        }
    }

    private fun isRepeating(): Boolean = repeatRunnable != null

    /** 移动光标一步；若已贴边则转为边缘滚动 */
    private fun moveCursor(dir: Int) {
        val w = host.width
        val h = host.height
        if (w <= 0 || h <= 0) return
        val minX = 1
        val maxX = w - 1
        val minY = 1
        val maxY = h - 1
        val step = cursorStep()

        var atEdge = KeyphoneShellOverlay.DIR_NONE
        when (dir) {
            KeyphoneShellOverlay.DIR_UP -> {
                if (cursorY <= minY) atEdge = KeyphoneShellOverlay.DIR_UP else cursorY = (cursorY - step).coerceAtLeast(minY)
            }
            KeyphoneShellOverlay.DIR_DOWN -> {
                if (cursorY >= maxY) atEdge = KeyphoneShellOverlay.DIR_DOWN else cursorY = (cursorY + step).coerceAtMost(maxY)
            }
            KeyphoneShellOverlay.DIR_LEFT -> {
                if (cursorX <= minX) atEdge = KeyphoneShellOverlay.DIR_LEFT else cursorX = (cursorX - step).coerceAtLeast(minX)
            }
            KeyphoneShellOverlay.DIR_RIGHT -> {
                if (cursorX >= maxX) atEdge = KeyphoneShellOverlay.DIR_RIGHT else cursorX = (cursorX + step).coerceAtMost(maxX)
            }
        }

        if (atEdge != KeyphoneShellOverlay.DIR_NONE) {
            // 光标已在边界：不再移动，转为基于光标位置的网页滚动
            applyEdgeScroll(dir)
            cursorView.updatePosition(cursorX, cursorY, atEdge)
        } else {
            cursorView.updatePosition(cursorX, cursorY, KeyphoneShellOverlay.DIR_NONE)
        }
    }

    /** 光标贴边后的网页滚动入口：同一时刻最多存在一个滚动动画 */
    private fun applyEdgeScroll(dir: Int) {
        if (dir in limitDirs) return
        if (edgeAnimator != null) return
        startEdgeScroll(dir)
    }

    /**
     * 启动恒速贴边滚动（唯一滚动源）：
     * - 按住期间无限滚动，速度恒为 pxPerMs（动画时钟逐帧推进）；
     * - 松手（edgeReleased）后最多滚满一个步长自停，速度不变。
     */
    private fun startEdgeScroll(dir: Int) {
        val webView = webViewProvider() ?: return
        KeydroidxLog.d(
            TAG,
            "贴边滚动开始 dir=$dir cursor=($cursorX,$cursorY) host=${host.width}x${host.height}"
        )
        try {
            ensureScrollModule(webView)
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "注入光标滚动脚本失败", t)
        }
        val vertical = dir == KeyphoneShellOverlay.DIR_UP || dir == KeyphoneShellOverlay.DIR_DOWN
        val sign = if (dir == KeyphoneShellOverlay.DIR_UP || dir == KeyphoneShellOverlay.DIR_LEFT) -1 else 1
        val dim = if (vertical) host.height else host.width
        val interval = if (vertical) PAGE_SCROLL_INTERVAL_MS else PAGE_SCROLL_INTERVAL_H_MS
        val ratio = if (vertical) PAGE_SCROLL_STEP_RATIO_V else PAGE_SCROLL_STEP_RATIO_H
        val pxPerMs = dim * ratio / interval
        val oneStep = Math.max(1, Math.round(dim * ratio)).toFloat()
        var lastFrame = 0L
        var remainder = 0f
        var scrolled = 0f
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = interval
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { _ ->
                val now = AnimationUtils.currentAnimationTimeMillis()
                if (lastFrame == 0L) {
                    lastFrame = now
                    return@addUpdateListener
                }
                val dt = (now - lastFrame).coerceIn(0L, 100L)
                lastFrame = now
                remainder += pxPerMs * dt
                var d = remainder.toInt()
                if (d <= 0) return@addUpdateListener
                remainder -= d
                if (edgeReleased) {
                    // 松手后：以平滑缓冲减速，滚满一个微步即停
                    val remain = (oneStep - scrolled).toInt()
                    if (remain <= 0) {
                        handler.post { cancelEdgeAnimator() }
                        return@addUpdateListener
                    }
                    if (d > remain) d = remain
                }
                // 原生硬件加速平滑滚动：直接驱动视口位移，零 JS 解释开销，帧率稳定 60FPS
                val dx = if (vertical) 0 else sign * d
                val dy = if (vertical) sign * d else 0
                try {
                    webView.scrollBy(dx, dy)
                } catch (t: Throwable) {
                    KeydroidxLog.e(TAG, "滚动异常", t)
                }
                scrolled += d
                if (edgeReleased && scrolled >= oneStep) {
                    handler.post { cancelEdgeAnimator() }
                }
            }
        }
        edgeAnimator = anim
        anim.start()
    }

    private fun cancelEdgeAnimator() {
        edgeAnimator?.cancel()
        edgeAnimator = null
    }

    /** 页面是否无响应（宿主看门狗判定）；为真时所有跨进程调用都要跳过 */
    private fun pageBusy(): Boolean = try {
        pageBusyProvider()
    } catch (t: Throwable) {
        false
    }
}
