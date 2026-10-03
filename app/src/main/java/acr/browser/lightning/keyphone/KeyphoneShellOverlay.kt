package acr.browser.lightning.keyphone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * 浏览器外壳覆盖层：轻量级全透明自定义视图，负责在 WebView 上层自绘虚拟光标与快捷菜单。
 *
 * 采用硬件加速的普通 View，而非 SurfaceView 或独立窗口，从根源上杜绝 SurfaceView 打孔穿透、
 * 窗口 Z-order 栈序竞争以及黑屏/遮挡问题。
 */
class KeyphoneShellOverlay(context: Context) : View(context) {

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

    // ---- 光标状态 ----
    private var cursorX = -1f
    private var cursorY = -1f
    private var edgeDir = DIR_NONE
    private var cursorVisible = false

    // ---- 快捷菜单状态 ----
    private var menuRows: List<MenuRow> = emptyList()
    private var menuFocusIndex = 0
    private var menuVisible = false
    private var menuGravityRight = false

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
        isFocusable = false
        isFocusableInTouchMode = false
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    // region 主线程 API（Activity / MouseController 调用）

    /** 更新光标位置与贴边态；状态未变时跳过 */
    fun updateCursor(x: Int, y: Int, atEdgeDir: Int) {
        synchronized(stateLock) {
            if (cursorX == x.toFloat() && cursorY == y.toFloat() && edgeDir == atEdgeDir) return
            cursorX = x.toFloat()
            cursorY = y.toFloat()
            edgeDir = atEdgeDir
        }
        postInvalidate()
    }

    fun setCursorVisible(visible: Boolean) {
        synchronized(stateLock) {
            if (cursorVisible == visible) return
            cursorVisible = visible
        }
        postInvalidate()
    }

    /** 弹出快捷菜单；gravityRight=true 时靠右弹出（级联子面板），否则居中 */
    fun showMenu(rows: List<MenuRow>, focusIndex: Int, gravityRight: Boolean) {
        synchronized(stateLock) {
            menuRows = rows
            menuFocusIndex = focusIndex
            menuGravityRight = gravityRight
            menuVisible = rows.isNotEmpty()
        }
        postInvalidate()
    }

    fun updateMenuFocus(index: Int) {
        synchronized(stateLock) {
            if (menuFocusIndex == index) return
            menuFocusIndex = index
        }
        postInvalidate()
    }

    fun hideMenu() {
        synchronized(stateLock) {
            if (!menuVisible) return
            menuVisible = false
            menuRows = emptyList()
        }
        postInvalidate()
    }

    // endregion

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawCursor(canvas)
        drawMenu(canvas)
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
}
