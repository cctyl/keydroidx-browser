package acr.browser.lightning.keyphone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * 浏览器外壳覆盖层：**独立 Surface + 专用渲染线程**绘制光标与快捷菜单。
 *
 * 为什么要独立 Surface：
 * 巨型页面把 WebView 渲染进程烧爆时（日志实证 `IntendedVsync→Vsync` 5.4s、`SwapBuffers` 2.7s），
 * 主窗口的帧同步被拖垮，主窗口里所有 View（光标、菜单）都要等那一帧才能画出来 → 用户感知
 * 「光标卡死、菜单卡死」。本层用 [SurfaceHolder.lockCanvas]（软件 Canvas）在自建线程上直接出帧，
 * **不经过主线程 Choreographer、不经过 RenderThread、不参与主窗口帧同步**——WebView 再烧，
 * 光标和菜单照样 60fps 跟手。页面本体不受任何影响：不冻结、不隐藏、照常渲染。
 */
class KeyphoneShellOverlay(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    companion object {
        /** 方向常量，与旧 KeyphoneCursorView / KeyphoneMouseController 保持一致 */
        const val DIR_NONE = 0
        const val DIR_UP = 1
        const val DIR_DOWN = 2
        const val DIR_LEFT = 3
        const val DIR_RIGHT = 4

        private val COLOR_RING_FILL = 0xE6FFFFFF.toInt()
        private val COLOR_RING_STROKE = 0xFF101418.toInt()
        private val COLOR_CROSS = 0xFFF2F5F8.toInt()
        private val COLOR_DOT = 0xFFFF8C1A.toInt()
        private val COLOR_EDGE_FILL = 0xE63478D6.toInt()
        private val COLOR_EDGE_STROKE = 0xFF101418.toInt()

        private val COLOR_MENU_CARD = 0xF01E2836.toInt()
        private val COLOR_MENU_STROKE = 0xFF3D516B.toInt()
        private val COLOR_MENU_TEXT = 0xFFD0DDEB.toInt()
        private val COLOR_MENU_FOCUS = 0xFF4A90E2.toInt()
    }

    /** 快捷菜单条目（keyLabel 预留给将来显示按键号，目前只画 text） */
    data class MenuRow(val keyLabel: String, val text: String)

    private val stateLock = Any()

    // ---- 光标状态（主线程写，渲染线程读） ----
    private var cursorX = -1f
    private var cursorY = -1f
    private var edgeDir = DIR_NONE
    private var cursorVisible = false

    // ---- 快捷菜单状态 ----
    private var menuRows: List<MenuRow> = emptyList()
    private var menuFocusIndex = 0
    private var menuVisible = false
    private var menuGravityRight = false

    // ---- 渲染线程 ----
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    @Volatile
    private var renderQueued = false

    private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_RING_FILL
    }
    private val ringStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_RING_STROKE
    }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_CROSS
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_DOT
    }
    private val edgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_EDGE_FILL
    }
    private val edgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_EDGE_STROKE
    }
    private val arrowPath = Path()

    private val menuCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_MENU_CARD
    }
    private val menuStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_MENU_STROKE
    }
    private val menuTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_MENU_TEXT
        typeface = Typeface.DEFAULT
    }
    private val menuFocusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_MENU_FOCUS
    }

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        // 透明 surface 浮在主窗口一切内容之上（含 WebView）；不抢焦点、不响应触摸，
        // 触摸事件默认返回 false 透传给下层视图（WebView）
        setZOrderOnTop(true)
        isFocusable = false
        isFocusableInTouchMode = false
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    // region Surface 生命周期

    override fun surfaceCreated(holder: SurfaceHolder) {
        startRenderThread()
        requestRender()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        requestRender()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopRenderThread()
    }

    private fun startRenderThread() {
        if (renderThread != null) return
        val thread = HandlerThread("keyphone-shell-overlay", android.os.Process.THREAD_PRIORITY_DISPLAY)
        thread.start()
        renderThread = thread
        renderHandler = Handler(thread.looper)
    }

    private fun stopRenderThread() {
        renderHandler = null
        renderThread?.quitSafely()
        renderThread = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopRenderThread()
    }

    // endregion

    // region 主线程 API（Activity / MouseController 调用）

    /** 更新光标位置与贴边态；状态未变时跳过（渲染线程按需出帧） */
    fun updateCursor(x: Int, y: Int, atEdgeDir: Int) {
        synchronized(stateLock) {
            if (cursorX == x.toFloat() && cursorY == y.toFloat() && edgeDir == atEdgeDir) return
            cursorX = x.toFloat()
            cursorY = y.toFloat()
            edgeDir = atEdgeDir
        }
        requestRender()
    }

    fun setCursorVisible(visible: Boolean) {
        synchronized(stateLock) {
            if (cursorVisible == visible) return
            cursorVisible = visible
        }
        requestRender()
    }

    /** 弹出快捷菜单；gravityRight=true 时靠右弹出（级联子面板），否则居中 */
    fun showMenu(rows: List<MenuRow>, focusIndex: Int, gravityRight: Boolean) {
        synchronized(stateLock) {
            menuRows = rows
            menuFocusIndex = focusIndex
            menuGravityRight = gravityRight
            menuVisible = rows.isNotEmpty()
        }
        requestRender()
    }

    fun updateMenuFocus(index: Int) {
        synchronized(stateLock) {
            if (menuFocusIndex == index) return
            menuFocusIndex = index
        }
        requestRender()
    }

    fun hideMenu() {
        synchronized(stateLock) {
            if (!menuVisible) return
            menuVisible = false
            menuRows = emptyList()
        }
        requestRender()
    }

    // endregion

    // region 渲染线程

    /** 按需渲染：状态变更时合并为一帧（高频连发按键也只触发一次绘制） */
    private fun requestRender() {
        val handler = renderHandler ?: return
        if (renderQueued) return
        renderQueued = true
        handler.post {
            renderQueued = false
            drawFrame()
        }
    }

    private fun drawFrame() {
        if (renderThread == null) return
        val canvas = try {
            holder.lockCanvas() ?: return
        } catch (t: Throwable) {
            return
        }
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            drawCursor(canvas)
            drawMenu(canvas)
        } catch (t: Throwable) {
            // 渲染线程自绘不能崩：任何异常都吞掉，下一帧照常
        } finally {
            try {
                holder.unlockCanvasAndPost(canvas)
            } catch (t: Throwable) {
                // surface 已销毁，忽略
            }
        }
    }

    private fun drawCursor(canvas: Canvas) {
        val visible: Boolean
        val x: Float
        val y: Float
        val dir: Int
        synchronized(stateLock) {
            visible = cursorVisible
            x = cursorX
            y = cursorY
            dir = edgeDir
        }
        if (!visible || x < 0f || y < 0f) return

        val d = resources.displayMetrics.density
        val radius = 7f * d
        val strokeW = 1.5f * d
        ringStroke.strokeWidth = strokeW
        edgeStroke.strokeWidth = strokeW
        cross.strokeWidth = strokeW

        canvas.save()
        canvas.translate(x, y)

        if (dir == DIR_NONE) {
            canvas.drawCircle(0f, 0f, radius, ringFill)
            canvas.drawCircle(0f, 0f, radius, ringStroke)
            val crossLen = radius + 3.5f * d
            canvas.drawLine(0f, -radius, 0f, -crossLen, cross)
            canvas.drawLine(0f, radius, 0f, crossLen, cross)
            canvas.drawLine(-radius, 0f, -crossLen, 0f, cross)
            canvas.drawLine(radius, 0f, crossLen, 0f, cross)
            canvas.drawCircle(0f, 0f, 1.8f * d, dot)
        } else {
            canvas.drawCircle(0f, 0f, radius + 1.5f * d, edgeFill)
            canvas.drawCircle(0f, 0f, radius + 1.5f * d, edgeStroke)
            val arrowW = 3.6f * d
            val arrowH = 5f * d
            arrowPath.reset()
            arrowPath.moveTo(0f, -arrowH)
            arrowPath.lineTo(arrowW, arrowH * 0.5f)
            arrowPath.lineTo(0f, arrowH * 0.1f)
            arrowPath.lineTo(-arrowW, arrowH * 0.5f)
            arrowPath.close()
            canvas.save()
            when (dir) {
                DIR_DOWN -> canvas.rotate(180f)
                DIR_LEFT -> canvas.rotate(-90f)
                DIR_RIGHT -> canvas.rotate(90f)
            }
            canvas.drawPath(arrowPath, cross)
            canvas.restore()
        }
        canvas.restore()
    }

    private fun drawMenu(canvas: Canvas) {
        val rows: List<MenuRow>
        val focus: Int
        val visible: Boolean
        val gravityRight: Boolean
        synchronized(stateLock) {
            rows = menuRows
            focus = menuFocusIndex
            visible = menuVisible
            gravityRight = menuGravityRight
        }
        if (!visible || rows.isEmpty()) return

        val d = resources.displayMetrics.density
        val textSize = 12f * d
        menuTextPaint.textSize = textSize

        // 行高 / 内边距与 KeyphoneViews.quickMenuItem 保持一致
        val rowH = 21f * d
        val padX = 7f * d
        val padTop = 2f * d
        val cardRadius = 4f * d

        var maxTextW = 0f
        rows.forEach { row ->
            val w = menuTextPaint.measureText(row.text)
            if (w > maxTextW) maxTextW = w
        }

        val cardW = maxTextW + padX * 2
        val cardH = rows.size * rowH + padTop * 2
        val margin = 8f * d
        val left = if (gravityRight) canvas.width - cardW - margin else (canvas.width - cardW) / 2f
        val top = (canvas.height - cardH) / 2f

        // 卡片背景 + 描边
        val cardRect = RectF(left, top, left + cardW, top + cardH)
        canvas.drawRoundRect(cardRect, cardRadius, cardRadius, menuCardPaint)
        menuStrokePaint.strokeWidth = 1f * d
        canvas.drawRoundRect(cardRect, cardRadius, cardRadius, menuStrokePaint)

        // 行：焦点行蓝色圆角高亮 + 白字，其余透明底 + 浅灰字
        rows.forEachIndexed { index, row ->
            val rowTop = top + padTop + index * rowH
            val rowRect = RectF(left + padX, rowTop, left + cardW - padX, rowTop + rowH)
            val isFocused = (index == focus)
            if (isFocused) {
                canvas.drawRoundRect(rowRect, 2f * d, 2f * d, menuFocusPaint)
                menuTextPaint.color = Color.WHITE
                menuTextPaint.isFakeBoldText = true
            } else {
                menuTextPaint.color = COLOR_MENU_TEXT
                menuTextPaint.isFakeBoldText = false
            }
            val baseline = rowTop + (rowH - menuTextPaint.fontMetrics.descent - menuTextPaint.fontMetrics.ascent) / 2f
            canvas.drawText(row.text, left + padX, baseline, menuTextPaint)
        }
        menuTextPaint.color = COLOR_MENU_TEXT
        menuTextPaint.isFakeBoldText = false
    }

    // endregion
}
