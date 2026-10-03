package acr.browser.lightning.keyphone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * 虚拟鼠标微型光标视图（40dp x 40dp）：
 *
 * 核心性能架构：
 * - 尺寸固定为微小图层，绝不覆盖全屏；
 * - 光标移动通过 RenderNode 的 [translationX] / [translationY] 矩阵变换完成，
 *   完全不触发 CPU/GPU 重新构建全屏 DisplayList，完全不触发 [invalidate]；
 * - 杜绝低端芯片（展讯等）GPU 在全屏透明图层和硬件加速 WebView 之间发生 [nSyncAndDrawFrame] 同步互斥卡死；
 * - 只有在贴边箭头方向变化时，才在该微小视图内部进行局部重绘。
 */
class KeyphoneCursorView(context: Context) : View(context) {

    companion object {
        const val COLOR_RING_FILL = 0xE6FFFFFF.toInt()
        const val COLOR_RING_STROKE = 0xFF101418.toInt()
        const val COLOR_CROSS = 0xFFF2F5F8.toInt()
        const val COLOR_DOT = 0xFFFF8C1A.toInt()
        const val COLOR_EDGE_FILL = 0xE63478D6.toInt()
        const val COLOR_EDGE_STROKE = 0xFF101418.toInt()
    }

    private val density = resources.displayMetrics.density
    private val sizePx = (40f * density).toInt()
    private val halfSize = sizePx / 2f
    private val radius = 7f * density
    private val strokeW = 1.5f * density

    private var edgeDir = KeyphoneShellOverlay.DIR_NONE

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
        color = COLOR_DOT
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

    override fun onDraw(canvas: Canvas) {
        val cx = halfSize
        val cy = halfSize

        canvas.save()
        canvas.translate(cx, cy)

        if (edgeDir == KeyphoneShellOverlay.DIR_NONE) {
            canvas.drawCircle(0f, 0f, radius, ringFill)
            canvas.drawCircle(0f, 0f, radius, ringStroke)
            val crossLen = radius + 3.5f * density
            canvas.drawLine(0f, -radius, 0f, -crossLen, cross)
            canvas.drawLine(0f, radius, 0f, crossLen, cross)
            canvas.drawLine(-radius, 0f, -crossLen, 0f, cross)
            canvas.drawLine(radius, 0f, crossLen, 0f, cross)
            canvas.drawCircle(0f, 0f, 1.8f * density, dot)
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
