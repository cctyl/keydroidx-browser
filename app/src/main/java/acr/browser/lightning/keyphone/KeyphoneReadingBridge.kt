package acr.browser.lightning.keyphone

import android.webkit.JavascriptInterface
import java.lang.ref.WeakReference

/**
 * 阅读模式 JS 桥：`KeyphoneReadingMode.js` 运行期通过
 * `window.__keyphoneReadingBridge__.onEvent(type, payload)` 回传事件
 * （`chapter` / `fail` / `end`）。
 *
 * 注意：本机 WebView 对**已加载完成**的页面 `addJavascriptInterface` 往往不生效
 * （`window.__keyphoneReadingBridge__` 为 undefined），所以取章的主通道是
 * `evaluateJavascript` 的**返回值**（见 `KeyphoneBrowserActivity.requestNextChapter`）；
 * 这个桥只作为「恰好可用时更快」的旁路，两条通道在宿主侧按 URL 去重。
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
