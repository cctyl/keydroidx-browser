package acr.browser.lightning.keyphone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * 鼠标模式的光标绘制层（样式仿 keydroidx-foucs 的 CursorOverlay）：
 *
 * - 普通态：白圈黑边 + 四向准星 + 橙色实心点；
 * - 贴边态（光标已到 WebView 宿主边界且继续按键滚动）：蓝色圆底 + 白色方向箭头，
 *   提示当前操作是「基于光标位置滚动网页」。
 *
 * 该 View 铺满 WebView 宿主（[KeyphoneBrowserActivity] 的 browserFrameHost），
 * 仅在网页态显示；不响应触摸/焦点，事件全部穿透给下层 WebView。
 */
class KeyphoneCursorView(context: Context) : View(context) {

    companion object {
        /** 方向常量，与 [KeyphoneMouseController] 保持一致 */
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
    }

    private var cursorX = -1f
    private var cursorY = -1f
    private var edgeDir = DIR_NONE

    // 上次 update 的值（去重用），MIN_VALUE 表示从未绘制过
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE
    private var lastEdgeDir = Int.MIN_VALUE

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

    /** 指向正上方的白色箭头路径（0,0 为中心），绘制时按 edgeDir 旋转 */
    private val arrowPath = Path()

    fun update(x: Int, y: Int, atEdgeDir: Int) {
        // 去重：贴边滚动期间每 16ms tick 都会调 update，值未变时严禁重复 invalidate
        // （否则与 WebView 滚动帧抢 UI 线程，造成滚动卡顿）
        if (x == lastX && y == lastY && atEdgeDir == lastEdgeDir && lastX != Int.MIN_VALUE) return
        lastX = x
        lastY = y
        lastEdgeDir = atEdgeDir
        cursorX = x.toFloat()
        cursorY = y.toFloat()
        edgeDir = atEdgeDir
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cursorX < 0f || cursorY < 0f) return

        val d = resources.displayMetrics.density
        val radius = 7f * d
        val strokeW = 1.5f * d
        ringStroke.strokeWidth = strokeW
        edgeStroke.strokeWidth = strokeW
        cross.strokeWidth = strokeW

        canvas.save()
        canvas.translate(cursorX, cursorY)

        if (edgeDir == DIR_NONE) {
            // 白圈黑边
            canvas.drawCircle(0f, 0f, radius, ringFill)
            canvas.drawCircle(0f, 0f, radius, ringStroke)
            // 四向准星
            val crossLen = radius + 3.5f * d
            canvas.drawLine(0f, -radius, 0f, -crossLen, cross)
            canvas.drawLine(0f, radius, 0f, crossLen, cross)
            canvas.drawLine(-radius, 0f, -crossLen, 0f, cross)
            canvas.drawLine(radius, 0f, crossLen, 0f, cross)
            // 橙色实心点
            canvas.drawCircle(0f, 0f, 1.8f * d, dot)
        } else {
            // 贴边态：蓝色圆底 + 白色方向箭头
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
            when (edgeDir) {
                DIR_DOWN -> canvas.rotate(180f)
                DIR_LEFT -> canvas.rotate(-90f)
                DIR_RIGHT -> canvas.rotate(90f)
            }
            canvas.drawPath(arrowPath, cross)
            canvas.restore()
        }
        canvas.restore()
    }
}
