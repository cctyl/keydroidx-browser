package acr.browser.lightning.keyphone

import android.webkit.JavascriptInterface
import java.lang.ref.WeakReference

/**
 * 阅读模式 JS 桥：`KeyphoneReadingMode.js` 运行期通过
 * `window.__keyphoneReadingBridge__.onEvent(type, payload)` 回传事件
 * （进入 / 拼接 / 结束 / 失败 / 正文内点击链接 / 退出）。
 *
 * 实现要点：
 * - 必须是 **public 类 + public 方法**，`addJavascriptInterface` 才能通过反射找到
 *   `@JavascriptInterface` 方法，故声明为顶层 `internal` 类（字节码为 public）；
 * - 持 `WeakReference<KeyphoneBrowserActivity>`：WebView 被 `pool/` 池化复用后会长期存活，
 *   强引用会导致 Activity 无法释放；
 * - 仅暴露 `onEvent` 一个方法，不向 JS 开放任何宿主能力。
 */
internal class KeyphoneReadingBridge(activity: KeyphoneBrowserActivity) {

    private val hostRef = WeakReference(activity)

    @JavascriptInterface
    fun onEvent(type: String, payload: String) {
        val host = hostRef.get() ?: return
        host.runOnUiThread { host.onReadingBridgeEvent(type, payload) }
    }
}
