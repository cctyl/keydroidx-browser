package acr.browser.lightning.keyphone.reading

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.cctyl.nokia.common.log.KeydroidxLog
import org.json.JSONTokener

/**
 * 方案 B：阅读模式**专属**的取章 WebView（CSR / 无规则站点的兜底）。
 *
 * ## 为什么不复用浏览标签的 WebView
 *
 * 旧实现把「当前浏览标签的 WebView」改造成取章工具。它一旦被渲染进程回收，
 * 重建要走 `LimitedObjectPool` —— 池在淘汰旧实例时会触发旧实例登记的 `awaitRelease`
 * 回调，把**刚建好的新实例**一起销毁（日志里的 `重载后仍无 WebView`），
 * 于是取章通道永久消失，用户看到「没有下一章」。
 *
 * 这个类**不进池**：`new WebView(context)` → 用完 `destroy()`。没有池、没有 `awaitRelease`、
 * 没有跨标签 freeze，所以「新实例被旧回调打死」在这条路径上**物理上不可能发生**，
 * 重建是一条直线（见 [ensureView]），开发机上 `destroy()` 就能复现验证。
 *
 * ## 红线
 *
 * - 不挂到视图树上（不渲染、不滚动、不跑站点脚本）→ 没有合成帧压力；
 * - 没有任何定时器 / 心跳：只在宿主按键催章时被调用一次；
 * - 渲染进程死了**不立刻重建**（那会白养一个进程），等下一次取章懒重建。
 */
class KeyphoneFetchWebView(
    context: Context,
    private val shellBuilder: ReadingFetchShellBuilder
) {

    private val appContext: Context = context.applicationContext

    /** 纯状态机（可单测，见 `KeyphoneFetchWebViewStateTest`） */
    val state = FetchWebViewState()

    private var webView: WebView? = null

    /** 外壳正在加载的 baseUrl（onPageFinished 时据此标记 ready） */
    private var pendingShellUrl: String? = null

    private var destroyed = false

    /**
     * 取下一章。
     *
     * @return true = 已把 `evaluateJavascript` 发出去（结果走 [onResult]，可能是空串）；
     *   false = 外壳还在加载 / 刚重建 / 通道不可用 —— 宿主延时重试即可，**不是**「没有下一章」
     */
    fun fetchNext(
        baseUrl: String,
        nextUrl: String?,
        seen: List<String>,
        onResult: (rawJson: String?) -> Unit
    ): Boolean {
        if (destroyed) return false
        val view = ensureView(baseUrl, nextUrl, seen) ?: return false
        if (state.dead || !state.ready) return false
        if (state.shellUrl != baseUrl) {
            // 会话换了基准章（退出重进 / 换书）：外壳里的 currentUrl 必须跟着换
            loadShell(view, baseUrl, nextUrl, seen)
            return false
        }
        return try {
            view.evaluateJavascript(ReadingFetchShellBuilder.FETCH_NEXT_SCRIPT) { raw ->
                onResult(decodeJsString(raw))
            }
            true
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "取章脚本执行失败（下次按键会重建）", t)
            false
        }
    }

    /** 退出阅读模式 / 切走标签时调用：销毁实例，释放渲染进程 */
    fun destroy() {
        destroyed = true
        destroyInternal()
    }

    private fun destroyInternal() {
        val view = webView
        webView = null
        pendingShellUrl = null
        state.reset()
        if (view == null) return
        try {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
        } catch (t: Throwable) {
            KeydroidxLog.w(TAG, "取章 WebView 摘除失败", t)
        }
        try {
            view.stopLoading()
            view.destroy()
        } catch (t: Throwable) {
            KeydroidxLog.w(TAG, "取章 WebView 销毁失败", t)
        }
    }

    /**
     * 拿到可用的实例；渲染进程死了就**一次性**重建（不循环、不重试）。
     *
     * 返回 null 表示创建失败（没权限 / WebView 不可用），宿主据此提示通道不可用。
     */
    private fun ensureView(baseUrl: String, nextUrl: String?, seen: List<String>): WebView? {
        if (state.dead) {
            KeydroidxLog.w(TAG, "取章 WebView 渲染进程已死，重建")
            destroyInternal()
        }
        webView?.let { return it }
        return try {
            val created = createWebView()
            webView = created
            loadShell(created, baseUrl, nextUrl, seen)
            created
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "创建取章 WebView 失败", t)
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(appContext).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        // 只取 HTML：不加载图片、不跑站点媒体，减少这个进程的资源画像
        settings.loadsImagesAutomatically = false
        settings.blockNetworkImage = true
        settings.mediaPlaybackRequiresUserGesture = true
        // 外壳不需要能滚动 / 缩放，关掉减少无谓的排版与合成
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
        }
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val shellUrl = pendingShellUrl
                pendingShellUrl = null
                if (shellUrl != null) {
                    state.onShellLoaded(shellUrl)
                    KeydroidxLog.d(TAG, "取章外壳就绪 baseUrl=$shellUrl")
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                val didCrash = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    detail.didCrash()
                } else {
                    true
                }
                KeydroidxLog.w(TAG, "取章 WebView 渲染进程死亡 didCrash=$didCrash（等下次取章懒重建）")
                state.onRendererGone()
                destroyInternal()
                // 返回 true 表示宿主已处理，系统不会连 App 进程一起杀
                return true
            }
        }
    }

    private fun loadShell(view: WebView, baseUrl: String, nextUrl: String?, seen: List<String>) {
        state.onShellLoading(baseUrl)
        pendingShellUrl = baseUrl
        val shell = shellBuilder.build(baseUrl, nextUrl, seen)
        KeydroidxLog.d(TAG, "载入取章外壳 baseUrl=$baseUrl shellChars=${shell.length} next=${nextUrl ?: "-"}")
        try {
            view.loadDataWithBaseURL(baseUrl, shell, "text/html", "utf-8", null)
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "载入取章外壳失败 baseUrl=$baseUrl", t)
            state.onShellLoadFailed()
            pendingShellUrl = null
        }
    }

    /** evaluateJavascript 回传的是 JSON 字符串字面量（如 `"{\"ok\":true}"`），解一层 */
    private fun decodeJsString(raw: String?): String? {
        if (raw == null) return null
        return try {
            JSONTokener(raw).nextValue() as? String ?: raw
        } catch (t: Throwable) {
            KeydroidxLog.w(TAG, "取章返回值解析失败: ${raw.take(80)}")
            raw
        }
    }

    private companion object {
        const val TAG = "KeyphoneFetchWebView"
    }
}

/**
 * 取章 WebView 的状态机（纯逻辑，无 Android 依赖，可单测）。
 *
 * 语义刻意区分「还没就绪」和「死了需要重建」：
 * - [onShellLoading] / [onShellLoadFailed]：外壳在加载或加载失败，下次按键重试；
 * - [onRendererGone]：渲染进程被系统回收，下次取章必须先重建实例。
 */
class FetchWebViewState {

    var ready: Boolean = false
        private set

    var dead: Boolean = false
        private set

    /** 当前就绪外壳对应的章节 URL */
    var shellUrl: String? = null
        private set

    private var loadingUrl: String? = null

    fun onShellLoading(url: String) {
        ready = false
        dead = false
        shellUrl = null
        loadingUrl = url
    }

    fun onShellLoaded(url: String) {
        ready = true
        dead = false
        shellUrl = url
        loadingUrl = null
    }

    fun onShellLoadFailed() {
        ready = false
        shellUrl = null
        loadingUrl = null
    }

    fun onRendererGone() {
        ready = false
        dead = true
        shellUrl = null
        loadingUrl = null
    }

    fun reset() {
        ready = false
        dead = false
        shellUrl = null
        loadingUrl = null
    }

    /** 正在加载中的外壳 URL（诊断用） */
    fun loadingShellUrl(): String? = loadingUrl
}
