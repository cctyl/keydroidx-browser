package acr.browser.lightning.browser.tab

import acr.browser.lightning.browser.view.LongPress
import acr.browser.lightning.browser.view.WebViewLongPressHandler
import acr.browser.lightning.di.BrowserFrame
import acr.browser.lightning.di.BrowserScope
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.view.children
import javax.inject.Inject

/**
 * A sort of coordinator that manages the relationship between [WebViews][WebView] and the container
 * the views are placed in.
 */
@BrowserScope
class TabPager @Inject constructor(
    @BrowserFrame private val container: FrameLayout,
    private val webViewLongPressHandler: WebViewLongPressHandler
) {

    private val webViews: MutableMap<Int, WebView> = mutableMapOf()

    var longPressListener: ((id: Int, longPress: LongPress) -> Unit)? = null

    /**
     * Select the tab with the provided [id] to be displayed by the pager.
     */
    fun selectTab(id: Int) {
        container.removeWebViews(excludeId = id)
        val webView = webViews[id]!!
        if (webView.parent != container) {
            container.addView(
                webView,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        // 诊断：WebView 被 add 进的 container 是不是当前 Activity 的窗口里那个？
        // container.windowToken 与 webRoot.windowToken 一致 → 绑定正确；
        // 不一致或 container 未 attach → 绑到了旧/死的窗口（黑屏根因定位用）
        android.util.Log.d(
            "TabPager",
            "selectTab id=$id container=${container.hashCode()} " +
                "containerAttached=${container.isAttachedToWindow} " +
                "containerWinToken=${container.windowToken?.hashCode()} " +
                "webView=${webView.hashCode()} wvAttached=${webView.isAttachedToWindow} " +
                "wvVisible=${webView.isShown} wvSize=${webView.width}x${webView.height}"
        )

        webViewLongPressHandler.configure(webView, onLongClick = {
            longPressListener?.invoke(id, it)
        })
    }

    /**
     * Remove the current webview from the pool.
     */
    fun removeTab(id: Int) {
        webViews.remove(id)
    }

    /**
     * Clear the container of the [WebView] currently shown and remove the current webview.
     */
    fun clearTab(id: Int) {
        webViews.remove(id)
        container.removeWebViews()
    }

    /**
     * Add a [WebView] to the list of views shown by this pager.
     */
    fun addTab(id: Int, webView: WebView) {
        webViews[id] = webView
    }

    private fun FrameLayout.removeWebViews(excludeId: Int = -1) {
        children
            .filterIsInstance<WebView>()
            .filter { it.id != excludeId }
            .forEach(container::removeView)
    }

}
