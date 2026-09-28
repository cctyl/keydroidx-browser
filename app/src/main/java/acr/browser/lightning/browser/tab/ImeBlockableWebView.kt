package acr.browser.lightning.browser.tab

import android.content.Context
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView

/**
 * 支持「彻底屏蔽系统输入法」的 [WebView] 子类（按键机鼠标模式专用）。
 *
 * 按键机上网页文本输入统一改为：点中输入框 → 跳转 SDK 输入页
 * （KeydroidxTextInputFragment）→ 输入完成后经 JS 回填，从根上避开
 * IME 悬浮窗与 WebView 抢焦点/抢窗口的问题。
 *
 * [blockIme] = true 时：声明自身不是文本编辑器（[onCheckIsTextEditor]）且返回空
 * 输入连接（[onCreateInputConnection]），输入法框架不会再为它弹软键盘；
 * 默认 false，上游触屏 UI（DefaultBrowserActivity 等）行为完全不变。
 */
class ImeBlockableWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle
) : WebView(context, attrs, defStyleAttr) {

    var blockIme: Boolean = false

    override fun onCheckIsTextEditor(): Boolean = !blockIme && super.onCheckIsTextEditor()

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        return if (blockIme) null else super.onCreateInputConnection(outAttrs)
    }
}
