package acr.browser.lightning.keyphone

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import io.github.cctyl.nokia.common.util.KeydroidxDimens

/**
 * 按键机通用底部选项卡弹窗（审批卡 / 规则操作卡共用）。
 *
 * 形态：底部弹出卡片 = 迷你标题 + 正文若干行 + 数字快捷选项列表（1~9 直选），
 * 键位：上/下移动焦点，确认键或数字键选择，返回键取消。
 * 视觉与快捷菜单卡一致（深蓝卡 + 天蓝高亮条）。
 *
 * @param bodyLines 正文行（纯文本，逐行展示）
 * @param options 选项列表；[KeyphoneViews.QuickMenuItem.keyLabel] 作为直选数字
 * @param defaultFocusIndex 初始焦点（如外部协议审批默认落在「永久忽略」）
 * @param onSelected 选项回调（选择后自动关闭）
 * @param onCancelled 取消回调（返回键 / 触屏点外部）
 */
class KeyphoneOptionSheetDialog(
    context: Context,
    private val titleText: String,
    private val bodyLines: List<String>,
    private val options: List<KeyphoneViews.QuickMenuItem>,
    private val defaultFocusIndex: Int = 0,
    private val onSelected: (KeyphoneViews.QuickMenuItem) -> Unit,
    private val onCancelled: () -> Unit
) : Dialog(context) {

    private var focusIndex = defaultFocusIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0))
    private var optionsContainer: LinearLayout? = null
    private var decided = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(buildContent())

        window?.apply {
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        setCanceledOnTouchOutside(true)
        renderOptions()
    }

    private fun dp(v: Float): Int = KeydroidxDimens.dp(context.resources, v)

    private fun buildContent(): LinearLayout {
        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable().apply {
            cornerRadius = dp(4f).toFloat()
            setColor(0xF01E2836.toInt())
            setStroke(dp(1f), 0xFF3D516B.toInt())
        }
        card.background = bg
        val margin = dp(6f)
        card.setPadding(dp(4f), dp(4f), dp(4f), dp(4f))

        val title = TextView(context)
        title.text = titleText
        title.setTextColor(0xFFE8EEF5.toInt())
        title.includeFontPadding = false
        title.isSingleLine = true
        KeydroidxFontHelper.applyBody(title)
        title.setPadding(dp(4f), dp(3f), dp(4f), dp(2f))
        card.addView(title)

        bodyLines.filter { it.isNotBlank() }.forEach { line ->
            val body = TextView(context)
            body.text = line
            body.setTextColor(0xFF90CAF9.toInt())
            body.includeFontPadding = false
            body.isSingleLine = false
            body.maxLines = 2
            KeydroidxFontHelper.applyCaption(body)
            body.setPadding(dp(4f), dp(1f), dp(4f), dp(1f))
            card.addView(body)
        }

        val options = LinearLayout(context)
        options.orientation = LinearLayout.VERTICAL
        options.setPadding(0, dp(2f), 0, 0)
        card.addView(options)
        optionsContainer = options
        return card
    }

    private fun renderOptions() {
        val container = optionsContainer ?: return
        container.removeAllViews()
        options.forEachIndexed { index, item ->
            val isFocused = index == focusIndex
            val row = KeyphoneViews.quickMenuItem(context, item, isFocused) {
                choose(index)
            }
            container.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun choose(index: Int) {
        val item = options.getOrNull(index) ?: return
        decided = true
        dismiss()
        // 必须执行选项自身的 action：审批结果的写规则 / 放行动作都挂在 item.action 上
        item.action()
        onSelected(item)
    }

    private fun moveFocus(delta: Int) {
        if (options.isEmpty()) return
        focusIndex = (focusIndex + delta + options.size) % options.size
        renderOptions()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    moveFocus(-1)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    moveFocus(1)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    choose(focusIndex)
                    return true
                }

                KeyEvent.KEYCODE_BACK -> {
                    cancel()
                    return true
                }
            }
            val label = keyLabelOf(event)
            if (label != null) {
                val index = options.indexOfFirst { it.keyLabel == label }
                if (index >= 0) {
                    choose(index)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun keyLabelOf(event: KeyEvent): String? = when (event.keyCode) {
        KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> "1"
        KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> "2"
        KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> "3"
        KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> "4"
        KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> "5"
        KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> "6"
        KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> "7"
        KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> "8"
        KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> "9"
        else -> null
    }

    override fun cancel() {
        if (decided) {
            super.cancel()
            return
        }
        decided = true
        super.cancel()
        onCancelled()
    }
}

/** 字号应用小助手：统一走 KeydroidxFontManager Token（红线 #12）。 */
private object KeydroidxFontHelper {
    private val bodyRes = io.github.cctyl.nokia.common.R.dimen.keydroidx_font_body
    private val captionRes = io.github.cctyl.nokia.common.R.dimen.keydroidx_font_caption

    fun applyBody(view: android.widget.TextView) {
        io.github.cctyl.nokia.common.ui.KeydroidxFontManager.setTextSizeResource(view, bodyRes)
    }

    fun applyCaption(view: android.widget.TextView) {
        io.github.cctyl.nokia.common.ui.KeydroidxFontManager.setTextSizeResource(view, captionRes)
    }
}
