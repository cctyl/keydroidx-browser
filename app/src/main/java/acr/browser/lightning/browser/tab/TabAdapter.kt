package acr.browser.lightning.browser.tab

import acr.browser.lightning.browser.view.CustomGestureListener
import acr.browser.lightning.browser.view.ToggleListener
import acr.browser.lightning.browser.view.TouchListener
import acr.browser.lightning.browser.view.setCompositeOnFocusChangeListener
import acr.browser.lightning.browser.view.setCompositeTouchListener
import acr.browser.lightning.concurrency.CoroutineDispatchers
import acr.browser.lightning.concurrency.TabCoroutineScope
import acr.browser.lightning.connectivity.ConnectivityProvider
import acr.browser.lightning.constant.DESKTOP_USER_AGENT
import acr.browser.lightning.download.PendingDownload
import acr.browser.lightning.ids.ViewIdGenerator
import acr.browser.lightning.keyphone.KeyphoneUrlGuard
import acr.browser.lightning.pool.ObjectPool
import acr.browser.lightning.preview.PreviewModel
import acr.browser.lightning.ssl.SslCertificateInfo
import acr.browser.lightning.ssl.SslState
import acr.browser.lightning.useragent.UserAgentProvider
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.util.Log
import io.github.cctyl.nokia.common.log.KeydroidxLog
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import androidx.activity.result.ActivityResult
import androidx.core.graphics.createBitmap
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/** 进度上报量化步进（百分比），见 init 块 progressSharedFlow 收集处的节流注释。 */
private const val PROGRESS_UI_STEP = 5

/**
 * 一次真实导航开始时的进度起步值。
 *
 * 不能继续沿用 [TabAdapter] 的默认值 100：新标签页 / 刷新 / 页面内跳转在 Chromium 真正
 * 上报 onProgressChanged 之前，UI 会读到旧的 100 并把「还没开始加载」误判成「已经完成」。
 * 用 10 明确表示「导航已发起，正在等首个真实进度」。
 */
internal const val PAGE_LOAD_START_PROGRESS = 10

/**
 * 渲染进程死亡后重载的「是否真的出帧」验证延时（见 verifyReloadedPageRenders）。
 * 取 6 秒：本机型首屏实测 1.5s+，重载后还要重建渲染进程，留足余量避免误报。
 */
private const val RELOAD_VERIFY_DELAY_MS = 6000L

/**
 * 渲染进程死亡后重建 WebView 的最多尝试次数。
 *
 * 第一次重建会被 WebView 池的释放回调吞掉（真机日志「重载后仍无 WebView」），
 * 再补两次基本都能拿回一个可用的 WebView；有上限，避免一直失败时无限重试。
 */
private const val MAX_RELOAD_REBUILD_ATTEMPTS = 3

/**
 * Creates the adaptation between a [WebView] and the [TabModel] interface used by the browser.
 */
@SuppressLint("ClickableViewAccessibility")
class TabAdapter @AssistedInject constructor(
    @Assisted override val id: Int,
    @Assisted private val tabInitializer: TabInitializer,
    @Assisted private val webViewPool: ObjectPool<WebView>,
    @Assisted private val requestHeaders: Map<String, String>,
    @Assisted private val tabWebViewClient: TabWebViewClient,
    @Assisted override var tabType: TabModel.Type,
    @Assisted private val tabCoroutineScope: TabCoroutineScope,
    @Assisted private var priority: Priority,
    private val tabPager: TabPager,
    private val tabWebChromeClientFactory: TabWebChromeClient.Factory,
    private val userAgentProvider: UserAgentProvider,
    private val viewIdGenerator: ViewIdGenerator,
    private val previewModel: PreviewModel,
    private val coroutineDispatchers: CoroutineDispatchers,
    private val connectivityProvider: ConnectivityProvider,
    private val rendererBusyProvider: acr.browser.lightning.di.RendererBusyProvider,
) : TabModel {

    @AssistedFactory
    interface Factory {

        fun create(
            id: Int,
            tabInitializer: TabInitializer,
            webViewPool: ObjectPool<WebView>,
            requestHeaders: Map<String, String>,
            tabWebViewClient: TabWebViewClient,
            tabType: TabModel.Type,
            tabCoroutineScope: TabCoroutineScope,
            priority: Priority,
        ): TabAdapter
    }

    enum class Priority {
        LOW,
        HIGH,
    }

    private var latentInitializer: FreezableInitializer? = tabInitializer as? FreezableInitializer

    private var findInPageQuery: String? = null
    private var toggleDesktop: Boolean = false
    private val downloadsShareFlow = MutableSharedFlow<PendingDownload>()
    private val focusSharedFlow = MutableSharedFlow<Unit>()
    private val showHideFlow = MutableSharedFlow<Boolean>()

    private val tabWebChromeClient by lazy { tabWebChromeClientFactory.create(tabCoroutineScope) }

    private fun WebView.setup() {
        webViewClient = tabWebViewClient
        webChromeClient = tabWebChromeClient
        setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            // 按键机下载审批（仅按键机浏览页在场时生效）：凡下载必先确认
            if (KeyphoneUrlGuard.isActive()) {
                val pending = PendingDownload(
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimetype,
                    contentLength = contentLength
                )
                KeyphoneUrlGuard.handleDownload(
                    context = context,
                    sourceUrl = this@setup.url,
                    targetUrl = url,
                    fileName = KeyphoneUrlGuard.guessFileName(url, contentDisposition, mimetype),
                    contentLength = contentLength
                ) { allowed ->
                    if (allowed) {
                        tabCoroutineScope.launch {
                            downloadsShareFlow.emit(pending)
                        }
                    }
                }
                return@setDownloadListener
            }
            tabCoroutineScope.launch {
                downloadsShareFlow.emit(
                    PendingDownload(
                        url = url,
                        userAgent = userAgent,
                        contentDisposition = contentDisposition,
                        mimeType = mimetype,
                        contentLength = contentLength
                    )
                )
            }
        }
        id = this@TabAdapter.id

        setCompositeOnFocusChangeListener("focus_change") { _, hasFocus ->
            tabCoroutineScope.launch {
                if (hasFocus) {
                    focusSharedFlow.emit(Unit)
                }
            }
        }

        setCompositeTouchListener("toggle", createToolbarAwareTouchListener(context))

        setCompositeTouchListener("focus") { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                tabCoroutineScope.launch {
                    focusSharedFlow.emit(Unit)
                }
            }
            false
        }

        tabCoroutineScope.launch {
            connectivityProvider.hasInternetAccess.collectLatest {
                setNetworkAvailable(it)
            }
        }

        // 渲染进程死亡回调（API 26+，经由 WebViewClient.onRenderProcessGone）。
        // 不处理时系统默认会连 App 进程一起杀（网页崩 → 浏览器闪退）。
        tabWebViewClient.renderProcessGoneHandler = { view, detail ->
            // didCrash() 自身是 API 26+ 的方法。本回调确实只在 API 26+ 才会被系统触发，
            // 但这里是 lambda，不在 onRenderProcessGone 的 @RequiresApi 作用域内，
            // 静态检查无法推断，且本项目 minSdk 24 —— 直接调用在旧机上会 NoSuchMethodError，
            // 故显式判版本（Lint NewApi 的正解，不是单纯抑制）。
            val didCrash = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                detail.didCrash()
            } else {
                true
            }
            val deadUrl = urlStateFlow.value
            KeydroidxLog.w(
                "TabAdapter",
                "渲染进程死亡 tab=$id didCrash=$didCrash url=$deadUrl"
            )
            // 不再直接 `_acquiredWebView = null`：那样尸体仍留在池的 deque 里，后续某次 acquire
            // 把它 removeLast().release() 时会触发它注册的 awaitRelease→freeze，把刚重建的替换
            // WebView 一起 destroy（日志实证 "重载后仍无 WebView"）。改为只置 rendererDead 标记，
            // 让 webView() 在重建时先把尸体从池里摘掉再 acquire 全新的（见 webView()）。
            rendererDead = true
            // 先摘除再 destroy：attached 状态 destroy 会撕裂 AwContents（destroy while attached 警告）
            runCatching { (view.parent as? ViewGroup)?.removeView(view) }
            runCatching { view.destroy() }
            // 关键：渲染进程死亡后必须**重新加载**，否则该标签再也没有 WebView：
            // 内容区空白、progressStateFlow 停在旧值，宿主 updatePageLoading 在
            // webView == null 时无条件显示「正在打开…」遮罩 —— 用户看到的就是
            // 「字全部消失 + 一直卡在正在打开」。这正是 2026-10-19 21:13 那次
            // （渲染进程被系统按 excessive cpu 杀掉）之后的形态。
            if (deadUrl.isNotBlank()) {
                tabCoroutineScope.launch {
                    try {
                        // webView() 检测到 rendererDead 会先把尸体从池中移除、再 acquire 全新 WebView
                        // 并 addView 进 TabPager；loadUrl 会在新 WebView 上导航到 deadUrl。
                        // 不直接 selectTab：loadUrl 内部在 priority==HIGH 时会 selectTab 把新 WebView
                        // 挂到 browserFrame，保证 currentWebView() 能取到。
                        loadUrl(deadUrl)
                        KeydroidxLog.w("TabAdapter", "渲染进程死亡后已重新加载 tab=$id url=$deadUrl")
                        verifyReloadedPageRenders(deadUrl)
                    } catch (t: Throwable) {
                        KeydroidxLog.e("TabAdapter", "渲染进程死亡后重新加载失败 tab=$id url=$deadUrl", t)
                    }
                }
            }
            true // 返回 false 系统会连 App 进程一起杀；返回 true 由宿主接管（下次 webView() 重建）
        }
    }

    /**
     * 重载后确认页面**真的渲染出来了**（不是只有进度 100）。
     *
     * 实测：渲染进程被杀后重新 `loadUrl`，`onPageFinished progress=100` 正常上报，
     * 但画面可能是空的（WebView 重建后首帧合成失败 / 站点脚本因缺状态而渲染失败）。
     * 这种情况下用户看到的就是「一直空白」，日志却全是正常值。
     * 这里在重载后请求一次 `postVisualStateCallback`：回调不回来说明没出帧，落 ERROR；
     * 回调回来了再补一条成功日志，两种结果都能在文件里查到。
     */
    private fun verifyReloadedPageRenders(url: String, attempt: Int = 1) {
        tabCoroutineScope.launch {
            kotlinx.coroutines.delay(RELOAD_VERIFY_DELAY_MS)
            val view = webViewIfInitialized() ?: run {
                KeydroidxLog.e("TabAdapter", "重载后仍无 WebView tab=$id url=$url 第 $attempt 次")
                // 首次重建可能被 WebView 池的释放回调吞掉（见 renderProcessGoneHandler 的注释）。
                // 不再只记一条 ERROR 就收手：该标签会**永远**没有 WebView，表现在阅读模式上
                // 就是「读到章末再也取不到下一章」（2026-10-22 20:01 真机现场）。
                if (attempt < MAX_RELOAD_REBUILD_ATTEMPTS) {
                    runCatching { loadUrl(url) }
                        .onFailure { KeydroidxLog.e("TabAdapter", "重建 WebView 失败 tab=$id url=$url", it) }
                    verifyReloadedPageRenders(url, attempt + 1)
                } else {
                    KeydroidxLog.e(
                        "TabAdapter",
                        "重建 WebView 次数用尽 tab=$id url=$url（该标签已无可用 WebView）"
                    )
                }
                return@launch
            }
            var frameSeen = false
            try {
                view.postVisualStateCallback(RELOAD_VERIFY_DELAY_MS, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        frameSeen = true
                    }
                })
            } catch (t: Throwable) {
                KeydroidxLog.e("TabAdapter", "重载后帧回调请求失败 tab=$id url=$url", t)
                return@launch
            }
            kotlinx.coroutines.delay(RELOAD_VERIFY_DELAY_MS)
            if (frameSeen) {
                KeydroidxLog.w("TabAdapter", "重载后已出帧 tab=$id url=$url")
            } else {
                KeydroidxLog.e(
                    "TabAdapter",
                    "重载后未出帧（画面可能是空白） tab=$id url=$url" +
                        " progress=${view.progress} size=${view.width}x${view.height}" +
                        " attached=${view.parent != null}"
                )
            }
        }
    }

    private val previewPathDeferred = tabCoroutineScope.async {
        previewModel.previewForId(id)
    }

    private var _acquiredWebView: ObjectPool.AcquiredObject<WebView>? = null

    private val webViewMutex = Mutex()

    /**
     * 渲染进程死亡标记（由 [renderProcessGoneHandler] 置位、由 [webView] 重建后清除）。
     *
     * 仅靠 `_acquiredWebView = null` 不可靠：`onRenderProcessGone` 与恢复用的
     * `loadUrl` 都跑在主线程，但二者之间还有若干状态收集协程会调 `webView()`，
     * 实测会出现 `loadUrl` 仍命中已 `destroy()` 的旧 WebView（chromium 报
     * "Application attempted to call on a destroyed WebView"，`TabAdapter.kt:222`），
     * 导致重载是空操作、`_acquiredWebView` 最终仍指向尸体 ——
     * 用户视角就是「字全部消失 + 一直卡在正在打开」。
     *
     * 置位后：[webViewIfInitialized] 一律返回 null（避免 [freeze]/[background] 等继续碰尸体），
     * [webView] 会先把尸体从池里摘掉（见下文理由）再 acquire 全新的 WebView。
     */
    @Volatile
    private var rendererDead: Boolean = false

    private suspend fun webView(): WebView {
        return withContext(coroutineDispatchers.main) {
            webViewMutex.withLock {
                val held = _acquiredWebView
                if (held != null && !rendererDead) {
                    held.actual
                } else {
                    // 走到这里两种情况：从未 acquire 过，或 held 指向一具渲染进程已死的尸体。
                    // 后者必须先把尸体从池的 deque 里移除——否则它仍躺在池里，后续任意一次
                    // `acquire` 把它 `removeLast().release()` 时会触发它早在 acquire 时注册的
                    // `awaitRelease` 回调，而那时 `_acquiredWebView` 已指向新重建的替换 WebView，
                    // 回调里的 `freeze()` 会把替换 WebView 一起 destroy，正是日志里
                    // "重载后仍无 WebView" 的成因。`webViewPool.release` 只把它从 deque 摘下、
                    // 不触发 `awaitRelease`（见 LimitedObjectPool.release），故这具尸体的
                    // `awaitRelease` 协程会一直挂起（一次性泄漏，可接受）。
                    if (held != null) {
                        runCatching { webViewPool.release(held) }
                    }
                    _acquiredWebView = null
                    rendererDead = false
                    webViewPool.acquire(
                        highPriority = priority == Priority.HIGH
                    ).also {
                        _acquiredWebView = it
                        it.actual.setup()
                        tabPager.addTab(id, it.actual)
                        tabCoroutineScope.launch {
                            it.awaitRelease {
                                val bundle = save()
                                freeze()
                                latentInitializer = FreezableInitializer(
                                    bundle = bundle,
                                    delegate = BundleInitializer(bundle),
                                    initialTitle = title.orEmpty(),
                                    id = id
                                )
                                _acquiredWebView = null
                            }
                        }
                    }.actual
                }
            }
        }
    }

    private fun webViewIfInitialized(): WebView? =
        _acquiredWebView?.actual?.takeIf { !rendererDead }

    private val titleStateFlow = MutableStateFlow(
        latentInitializer?.initialTitle
    )

    private val faviconStateFlow = MutableStateFlow(
        latentInitializer?.let { TabModel.Favicon.Frozen } ?: TabModel.Favicon.None
    )

    private val urlStateFlow = MutableStateFlow("")

    /**
     * 初始进度：只有「本来就什么都不加载」的标签才可以是 100。
     *
     * UrlInitializer / HtmlPageFactoryInitializer / HomePageInitializer 等会发起导航，
     * 初始必须是 [PAGE_LOAD_START_PROGRESS]；否则 selectTab 的
     * `onStart { emit(tab.loadingProgress) }` 会先把假的 100 推给 UI。
     */
    private val progressStateFlow = MutableStateFlow(
        when (tabInitializer) {
            is NoOpInitializer,
            is PermissionInitializer,
            is BundleInitializer,
            is FreezableInitializer -> 100

            else -> PAGE_LOAD_START_PROGRESS
        }
    )

    private val previewStateFlow = MutableStateFlow<TabModel.Preview>(TabModel.Preview.None)

    init {
        if (tabInitializer !is FreezableInitializer) {
            loadFromInitializer(tabInitializer)
        }
        tabCoroutineScope.launch {
            merge(
                tabWebViewClient.startedSharedFlow.map { null },
                tabWebViewClient.finishedSharedFlow.map { webView().title },
                tabWebChromeClient.titleShareFlow
            ).collectLatest { titleStateFlow.emit(it) }
        }
        tabCoroutineScope.launch {
            merge(
                tabWebViewClient.startedSharedFlow.map { TabModel.Favicon.None },
                tabWebChromeClient.faviconSharedFlow
            ).collectLatest { faviconStateFlow.emit(it) }
        }
        tabCoroutineScope.launch {
            @OptIn(FlowPreview::class)
            tabWebViewClient.finishedSharedFlow
                .debounce(100.milliseconds)
                .map { renderViewToBitmap(webView()) }
                .flowOn(coroutineDispatchers.main)
                .map { bitmap ->
                    if (bitmap != null) {
                        previewModel.cachePreviewForId(id, bitmap)
                        TabModel.Preview.Image(
                            previewPathDeferred.await(),
                            System.currentTimeMillis()
                        )
                    } else {
                        TabModel.Preview.None
                    }
                }
                .onStart {
                    emit(
                        TabModel.Preview.Image(
                            previewPathDeferred.await(),
                            System.currentTimeMillis()
                        )
                    )
                }
                .flowOn(coroutineDispatchers.io)
                .collectLatest { previewStateFlow.emit(it) }
        }
        tabCoroutineScope.launch {
            tabWebViewClient.urlSharedFlow.collect {
                urlStateFlow.emit(it)
            }
        }
        tabCoroutineScope.launch {
            // 页内点击链接 / JS 跳转 / 重定向 / 前进后退都可能绕过 TabAdapter.loadUrl()，
            // 必须在 onPageStarted 时也把进度拉回起步值，避免沿用上一页的 100。
            tabWebViewClient.startedSharedFlow.collect {
                KeydroidxLog.i(
                    "KeyphoneProgress",
                    "[TabAdapter.startedSharedFlow] tab#$id url=$url -> emit PAGE_LOAD_START_PROGRESS($PAGE_LOAD_START_PROGRESS)"
                )
                progressStateFlow.emit(PAGE_LOAD_START_PROGRESS)
            }
        }
        tabCoroutineScope.launch {
            // 进度量化节流：Chromium 在资源密集页（如起点目录）的 onProgressChanged 可达
            // 每秒上百次，全部透传会让主线程上的 combine/collector 每秒重算上百遍 UI。
            // 按 PROGRESS_UI_STEP 量化 + 去重，终点 100 永远透传（停止/刷新切换、
            // 加载遮罩撤除都依赖它）；消费方只有「==100 / <100」两类判断，量化安全。
            var lastEmitted = -1
            tabWebChromeClient.progressSharedFlow.collect { raw ->
                val quantized = when {
                    raw >= 100 -> 100
                    raw <= 0 -> 0
                    else -> (raw / PROGRESS_UI_STEP) * PROGRESS_UI_STEP
                }
                if (quantized != lastEmitted) {
                    lastEmitted = quantized
                    KeydroidxLog.i(
                        "KeyphoneProgress",
                        "[TabAdapter.progressSharedFlow] tab#$id raw=$raw quantized=$quantized"
                    )
                    progressStateFlow.emit(quantized)
                }
            }
        }
    }

    override suspend fun loadUrl(url: String) {
        KeydroidxLog.i(
            "KeyphoneProgress",
            "[TabAdapter.loadUrl] tab#$id url=$url -> emit PAGE_LOAD_START_PROGRESS($PAGE_LOAD_START_PROGRESS)"
        )
        progressStateFlow.emit(PAGE_LOAD_START_PROGRESS)
        // 按键机提交兜底拦截：用户主动输入/选择的地址放行一次
        tabWebViewClient.allowExplicitNavigation(url)
        val wv = webView()
        wv.loadUrl(url, requestHeaders)
        if (priority == Priority.HIGH) {
            tabPager.selectTab(id)
        }
    }

    override fun loadFromInitializer(tabInitializer: TabInitializer) {
        tabCoroutineScope.launch {
            tabInitializer.initialize(this@TabAdapter)
        }
    }

    override suspend fun goBack() {
        KeydroidxLog.i(
            "KeyphoneProgress",
            "[TabAdapter.goBack] tab#$id -> emit PAGE_LOAD_START_PROGRESS($PAGE_LOAD_START_PROGRESS)"
        )
        progressStateFlow.emit(PAGE_LOAD_START_PROGRESS)
        tabWebViewClient.allowExplicitNavigation(null)
        webView().goBack()
    }

    override suspend fun canGoBack(): Boolean = webView().canGoBack()

    override fun canGoBackChanges(): Flow<Boolean> = tabWebViewClient.goBackSharedFlow

    override suspend fun goForward() {
        KeydroidxLog.i(
            "KeyphoneProgress",
            "[TabAdapter.goForward] tab#$id -> emit PAGE_LOAD_START_PROGRESS($PAGE_LOAD_START_PROGRESS)"
        )
        progressStateFlow.emit(PAGE_LOAD_START_PROGRESS)
        tabWebViewClient.allowExplicitNavigation(null)
        webView().goForward()
    }

    override suspend fun canGoForward(): Boolean = webView().canGoForward()

    override fun canGoForwardChanges(): Flow<Boolean> = tabWebViewClient.goForwardSharedFlow

    override suspend fun toggleDesktopAgent() {
        webView().settings.userAgentString = if (!toggleDesktop) {
            DESKTOP_USER_AGENT
        } else {
            userAgentProvider.getUserAgent()
        }

        toggleDesktop = !toggleDesktop
    }

    override suspend fun reload() {
        KeydroidxLog.i(
            "KeyphoneProgress",
            "[TabAdapter.reload] tab#$id -> emit PAGE_LOAD_START_PROGRESS($PAGE_LOAD_START_PROGRESS)"
        )
        progressStateFlow.emit(PAGE_LOAD_START_PROGRESS)
        webView().reload()
    }

    override suspend fun stopLoading() {
        webView().stopLoading()
    }

    override suspend fun find(query: String) {
        webView().findAllAsync(query)
        findInPageQuery = query
    }

    override suspend fun findNext() {
        webView().findNext(true)
    }

    override suspend fun findPrevious() {
        webView().findNext(false)
    }

    override suspend fun clearFindMatches() {
        webView().clearMatches()
        findInPageQuery = null
    }

    override val preview: TabModel.Preview
        get() = previewStateFlow.value

    override fun previewChanges(): StateFlow<TabModel.Preview> = previewStateFlow

    override val findQuery: String?
        get() = findInPageQuery

    override var searchQuery: String
        get() = tabWebViewClient.searchQuery
        set(value) {
            tabWebViewClient.searchQuery = value
        }

    override var searchQuerySelection: Pair<Int, Int>
        get() = tabWebViewClient.searchQuerySelection
        set(value) {
            tabWebViewClient.searchQuerySelection = value
        }

    override val favicon: TabModel.Favicon
        get() = faviconStateFlow.value

    override fun faviconChanges(): StateFlow<TabModel.Favicon> = faviconStateFlow

    override val themeColor: Int
        get() = tabWebChromeClient.colorChangeStateFlow.value

    // TODO: Do something with theme color or drop it
    override fun themeColorChanges(): Flow<Int> {
        // Treat it like a SharedFlow for consistency on presenter side
        return tabWebChromeClient.colorChangeStateFlow.drop(1)
    }

    override val url: String
        get() = urlStateFlow.value

    override fun urlChanges(): Flow<String> = urlStateFlow

    override val title: String?
        get() = titleStateFlow.value

    override fun titleChanges(): StateFlow<String?> = titleStateFlow

    override suspend fun getSslCertificateInfo(): SslCertificateInfo? = webView().certificate?.let {
        SslCertificateInfo(
            issuedByCommonName = it.issuedBy.cName,
            issuedToCommonName = it.issuedTo.cName,
            issuedToOrganizationName = it.issuedTo.oName,
            issueDate = it.validNotBeforeDate,
            expireDate = it.validNotAfterDate,
            sslState = sslState
        )
    }

    override val sslState: SslState
        get() = tabWebViewClient.sslStateFlow.value

    override fun sslChanges(): StateFlow<SslState> = tabWebViewClient.sslStateFlow

    override val loadingProgress: Int
        get() = progressStateFlow.value

    override fun loadingProgress(): Flow<Int> = progressStateFlow

    override fun pageFinishedChanges(): Flow<Unit> = tabWebViewClient.pageFinishedSharedFlow

    override fun downloadRequests(): Flow<PendingDownload> = downloadsShareFlow

    override fun fileChooserRequests(): Flow<Intent> = tabWebChromeClient.fileChooserSharedFlow

    override fun handleFileChooserResult(activityResult: ActivityResult) {
        tabWebChromeClient.onResult(activityResult)
    }

    override fun showCustomViewRequests(): Flow<Unit> = tabWebChromeClient.showCustomViewSharedFlow

    override fun hideCustomViewRequests(): Flow<Unit> = tabWebChromeClient.hideCustomViewObservable

    override fun hideCustomView() {
        tabWebChromeClient.hideCustomView()
    }

    override suspend fun handleMessage(message: Message) {
        message.apply {
            (obj as WebView.WebViewTransport).webView = webView()
        }.sendToTarget()
    }

    override fun createWindowRequests(): Flow<TabInitializer> =
        tabWebChromeClient.createWindowSharedFlow

    override fun closeWindowRequests(): Flow<Unit> = tabWebChromeClient.closeWindowSharedFlow

    override fun focusRequests(): Flow<Unit> = focusSharedFlow
    override fun showHideToolbar(): Flow<Boolean> = showHideFlow

    override suspend fun foreground() {
        priority = Priority.HIGH
        webView().resumeTimers()
        // 预栅格化已全局关闭：巨型 DOM 上它会把低配机的渲染进程烧爆（见 WebViewFactory）
        webView().onResume()
        latentInitializer?.let(::loadFromInitializer)
        latentInitializer = null
        tabPager.selectTab(id)
    }

    override suspend fun background(backgroundAll: Boolean) {
        priority = Priority.LOW
        webViewIfInitialized()?.apply {
            onPause()
            settings.offscreenPreRaster = false
            if (backgroundAll) {
                pauseTimers()
            }
        }
    }

    override suspend fun freeze() {
        val freezeStart = SystemClock.elapsedRealtime()
        // 先从视图树摘除（本地操作，不等渲染进程）：即使后续 WebView 销毁因渲染进程繁忙
        // 而卡住，用户视角的标签也已消失、可切到其它标签
        if (priority == Priority.HIGH) {
            tabPager.clearTab(id)
        } else {
            tabPager.removeTab(id)
        }
        webViewIfInitialized()?.apply {
            // 先把 WebView 从父容器摘除再 destroy：removeAllViews() 删的是 WebView 的子视图，
            // 不是把自己从父容器摘下；attached 状态 destroy 会把 Chromium AwContents 撕裂
            // （日志实证 "destroy() called while still attached"），且阻塞渲染中继。
            (parent as? ViewGroup)?.removeView(this)
            stopLoading()
            onPause()
            clearHistory()
            removeAllViews()
            destroy()
        }
        val freezeMs = SystemClock.elapsedRealtime() - freezeStart
        if (freezeMs >= 300) {
            Log.w("TabAdapter", "【主线程耗时】freeze() tab=$id = ${freezeMs}ms")
        }
    }

    override suspend fun destroy() {
        viewIdGenerator.releaseViewId(id)
        previewModel.prune()
        freeze()
        tabCoroutineScope.cancel()
        _acquiredWebView?.let { webViewPool.release(it) }
    }

    override suspend fun restore(bundle: Bundle) {
        webView().restoreState(bundle)
    }

    override suspend fun save(): Bundle = latentInitializer?.bundle
        ?: Bundle(ClassLoader.getSystemClassLoader()).also { b ->
            webViewIfInitialized()?.saveState(b)
        }

    private fun createToolbarAwareTouchListener(context: Context): View.OnTouchListener {
        val gestureListener = CustomGestureListener(
            ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
        )

        val touchListener = TouchListener(context, GestureDetector(context, gestureListener))

        val toggleListener = object : ToggleListener {
            override fun hideToolbar() {
                tabCoroutineScope.launch {
                    showHideFlow.emit(false)
                }
            }

            override fun showToolbar() {
                tabCoroutineScope.launch {
                    showHideFlow.emit(true)
                }
            }
        }

        gestureListener.toggleListener = toggleListener
        touchListener.toggleListener = toggleListener

        return touchListener
    }

    private suspend fun renderViewToBitmap(
        view: View,
        width: Int = view.width,
        height: Int = view.height
    ): Bitmap? = withContext(coroutineDispatchers.main) {
        // 渲染进程繁忙时跳过截图：view.draw(canvas) 会同步等渲染进程，DOM 巨大时拖死主线程。
        // 保留旧预览即可，待页面恢复后下次刷新补上。
        if (rendererBusyProvider.isRendererBusy()) {
            return@withContext null
        }
        // Ensure the view has been laid out
        if (width == 0 || height == 0) {
            return@withContext null
        }

        // Create a Bitmap with the specified dimensions and ARGB_8888 configuration
        val bitmap = createBitmap(width / 3, height / 3)

        // Create a Canvas to draw on the Bitmap
        val canvas = Canvas(bitmap)

        canvas.scale(0.33F, 0.33F)

        canvas.translate(-webView().scrollX.toFloat(), -webView().scrollY.toFloat())

        // Layout the view if it hasn't been laid out yet
        view.layout(0, 0, width, height)

        // Draw the view onto the canvas
        val drawStart = SystemClock.elapsedRealtime()
        view.draw(canvas)
        val drawMs = SystemClock.elapsedRealtime() - drawStart
        if (drawMs >= 300) {
            Log.w("TabAdapter", "【主线程耗时】标签预览 view.draw ${width}x${height} = ${drawMs}ms")
        }

        return@withContext bitmap
    }
}
