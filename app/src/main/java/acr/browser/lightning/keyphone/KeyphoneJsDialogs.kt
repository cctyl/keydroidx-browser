package acr.browser.lightning.keyphone

import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebView
import io.github.cctyl.nokia.common.log.KeydroidxLog

/**
 * 网页 JS 弹窗（alert / confirm / prompt / beforeunload）的按键机接管层。
 *
 * **为什么必须接管**：WebView 自带的 JS 弹窗是**独立窗口的系统模态对话框**。本项目实测
 * （Nokia Q968 / Android 13）：页面一弹 `alert`，宿主 Activity 就再也收不到按键——
 * `*` 菜单打不开、方向键光标不动、标签栏也点不了，用户看到的就是「浏览器卡死、功能全失效」。
 * 广告站正是靠这个把浏览器钉住（弹窗风暴时更甚）。
 *
 * 因此按键机浏览页在场时（[isActive]）一律不让 WebView 弹自己的对话框：
 * - `alert`：立即 `confirm()` 放行页面 JS，宿主只用**非模态提示条**告知，不抢按键；
 * - `confirm`：交由宿主的**键控确认框**，用户选完才回填结果；
 * - `prompt`：交由宿主的**键控输入页**，取消时回填 `null`；
 * - `beforeunload`：直接放行离开——跳转 / 关闭标签永远不会被「离开确认」挡住。
 *
 * 结果回填全部包在 try/catch 里：弹窗期间页面可能已被导航 / 销毁，回填失败只能忽略，
 * 绝不能因为回填异常把宿主按键链带崩。
 *
 * 上游普通（触屏）浏览器不受影响：未注册 [Host] 时所有回调都返回 false，
 * 交回 `WebChromeClient` 原实现，行为与改造前完全一致。
 */
object KeyphoneJsDialogs {

    private const val TAG = "KeyphoneJsDialogs"

    /**
     * 宿主回调：由按键机浏览页实现。全部在主线程发生。
     *
     * 实现方**必须保证不产生系统模态窗口**（不得调用 WebView 自带的对话框、
     * 也不得阻塞等待），否则「页面卡住时宿主仍可响应」这条底线会被打破。
     */
    interface Host {

        /** 网页 alert：非模态提示（实现方自行节流），必须立即返回。 */
        fun showWebAlert(url: String?, message: String)

        /** 网页 confirm：宿主键控确认，用户选择后回调（允许 / 不允许）。 */
        fun showWebConfirm(url: String?, message: String, onDecision: (Boolean) -> Unit)

        /** 网页 prompt：宿主键控输入，取消时回调 null。 */
        fun showWebPrompt(url: String?, message: String, defaultValue: String, onResult: (String?) -> Unit)
    }

    @Volatile
    private var host: Host? = null

    /**
     * 「这个 WebView 是不是当前可见页」的判定，由宿主注入。
     * 未注入时按可见处理（宁可多提示，也不要静默吞掉当前页的弹窗）。
     */
    @Volatile
    private var visiblePage: ((WebView) -> Boolean)? = null

    fun attach(newHost: Host, isVisiblePage: (WebView) -> Boolean) {
        host = newHost
        visiblePage = isVisiblePage
    }

    fun detach(oldHost: Host) {
        if (host === oldHost) {
            host = null
            visiblePage = null
        }
    }

    /** 接管是否生效（仅按键机浏览页在场时；上游浏览器不受影响）。 */
    fun isActive(): Boolean = host != null

    /**
     * 接管 alert。
     *
     * @return true 表示已接管（调用方**不要**再走 WebView 默认弹窗）
     */
    fun handleAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        val current = host ?: return false
        // 后台标签页的 alert 不打扰用户：静默放行页面 JS 即可
        if (!isVisible(view)) {
            safeConfirm(result, "后台标签 alert")
            return true
        }
        try {
            current.showWebAlert(url, message.orEmpty())
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "展示网页 alert 提示失败", t)
        }
        // 立刻放行页面 JS：宿主提示是非模态的，页面没必要等用户
        safeConfirm(result, "alert")
        return true
    }

    /** 接管 confirm：异步等用户选择，再回填结果。 */
    fun handleConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        val current = host ?: return false
        // 后台标签页（含切页瞬间）的 confirm 会盖到当前页面上打断用户，一律按「不允许」静默回填
        if (!isVisible(view)) {
            KeydroidxLog.d(TAG, "后台标签页 confirm，静默按「不允许」回填")
            safeCancel(result, "后台标签 confirm")
            return true
        }
        try {
            current.showWebConfirm(url, message.orEmpty()) { allow ->
                try {
                    if (allow) result.confirm() else result.cancel()
                } catch (t: Throwable) {
                    KeydroidxLog.e(TAG, "回填网页 confirm 结果失败 allow=$allow", t)
                }
            }
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "展示网页 confirm 框失败", t)
            try {
                result.cancel()
            } catch (inner: Throwable) {
                KeydroidxLog.e(TAG, "回填网页 confirm 失败结果时异常", inner)
            }
        }
        return true
    }

    /** 接管 prompt：异步等用户输入，[onResult] 为 null 表示取消。 */
    fun handlePrompt(
        view: WebView,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult
    ): Boolean {
        val current = host ?: return false
        // 后台标签页的 prompt 不能抢走按键（会突然切到输入页），直接按取消回填
        if (!isVisible(view)) {
            KeydroidxLog.d(TAG, "后台标签页 prompt，静默按取消回填")
            try {
                result.cancel()
            } catch (t: Throwable) {
                KeydroidxLog.e(TAG, "回填后台标签 prompt 失败", t)
            }
            return true
        }
        try {
            current.showWebPrompt(url, message.orEmpty(), defaultValue.orEmpty()) { text ->
                try {
                    if (text == null) result.cancel() else result.confirm(text)
                } catch (t: Throwable) {
                    KeydroidxLog.e(TAG, "回填网页 prompt 结果失败", t)
                }
            }
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "展示网页 prompt 输入页失败", t)
            try {
                result.cancel()
            } catch (inner: Throwable) {
                KeydroidxLog.e(TAG, "回填网页 prompt 失败结果时异常", inner)
            }
        }
        return true
    }

    /**
     * 接管 beforeunload：**直接放行**。
     *
     * 小说/广告站常挂「离开确认」脚本挽留用户，WebView 默认会为它弹系统模态框，
     * 于是「点下一章 / 关标签」全被挡住。这里一律按允许离开回填，绝不弹窗。
     */
    fun handleBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        if (host == null) return false
        safeConfirm(result, "beforeunload")
        return true
    }

    /** 弹窗是否来自当前可见页；非可见页（后台标签、切页瞬间）一律静默处理。 */
    private fun isVisible(view: WebView): Boolean = visiblePage?.invoke(view) ?: true

    private fun safeConfirm(result: JsResult, scene: String) {
        try {
            result.confirm()
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "回填网页 $scene 结果失败", t)
        }
    }

    private fun safeCancel(result: JsResult, scene: String) {
        try {
            result.cancel()
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "回填网页 $scene 结果失败", t)
        }
    }
}
