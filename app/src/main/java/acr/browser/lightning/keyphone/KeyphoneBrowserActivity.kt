package acr.browser.lightning.keyphone

import acr.browser.lightning.BrowserUiEvent
import acr.browser.lightning.R
import acr.browser.lightning.browser.BrowserContract
import io.github.cctyl.nokia.common.R as CommonR
import acr.browser.lightning.browser.BrowserPresenter
import acr.browser.lightning.browser.BrowserViewState
import acr.browser.lightning.browser.menu.MenuSelection
import acr.browser.lightning.browser.search.IntentExtractor
import acr.browser.lightning.browser.tab.ImeBlockableWebView
import acr.browser.lightning.browser.tab.TabPager
import acr.browser.lightning.browser.tab.TabViewState
import acr.browser.lightning.constant.SCHEME_BLANK
import acr.browser.lightning.BuildConfig
import acr.browser.lightning.constant.SCHEME_HOMEPAGE
import acr.browser.lightning.database.Bookmark
import acr.browser.lightning.database.HistoryEntry
import acr.browser.lightning.database.bookmark.BookmarkRepository
import acr.browser.lightning.database.history.HistoryRepository
import acr.browser.lightning.di.injector
import acr.browser.lightning.html.homepage.HomePageFactory
import acr.browser.lightning.js.KeyphoneReadingMode
import acr.browser.lightning.js.KeyphoneScroll
import acr.browser.lightning.preference.UserPreferencesDataStore
import acr.browser.lightning.search.SearchEngineProvider
import acr.browser.lightning.utils.isSpecialUrl
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import io.github.cctyl.nokia.common.log.KeydroidxLog
import io.github.cctyl.nokia.common.model.KeydroidxKeyAction
import io.github.cctyl.nokia.common.ui.KeydroidxTextInputFragment
import io.github.cctyl.nokia.common.ui.dialog.KeydroidxConfirmDialog
import io.github.cctyl.nokia.common.ui.page.KeydroidxListFocusHelper
import io.github.cctyl.nokia.common.util.KeydroidxDimens
import io.github.cctyl.nokia.keycore.ui.KeydroidxBaseActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import javax.inject.Inject

/**
 * 按键机浏览页（UC 风格多标签架构）：
 *
 * 结构：
 * - 顶部标签栏：横向滚动展示全部标签页及主页，按键 0 快速循环切换；
 * - 页面快捷菜单（按键 * 弹出）：面向当前网页/窗口深度操作，按 1~9 直达执行；
 * - 全局快捷菜单（按键 # 弹出）：面向全局快捷跳转、无图/全屏/滚动等常用工具，按 0~9、*、# 直达执行；
 * - 地址/搜索栏：浏览具体网页时展示（可直接输入/前往）；
 * - 加载进度线：2dp 横向拉伸；
 * - 内容区：主页（搜索框+书签宫格+历史记录）与 WebView 容器无缝切换；
 * - 软键栏：菜单/选项/选择 | 动作 | 返回/退出/取消。
 */
class KeyphoneBrowserActivity : KeydroidxBaseActivity(), BrowserContract.View {

    @Inject
    internal lateinit var presenter: BrowserPresenter

    @Inject
    internal lateinit var tabPager: TabPager

    @Inject
    internal lateinit var intentExtractor: IntentExtractor

    @Inject
    internal lateinit var bookmarkRepository: BookmarkRepository

    @Inject
    internal lateinit var historyRepository: HistoryRepository

    @Inject
    internal lateinit var userPreferencesDataStore: UserPreferencesDataStore

    @Inject
    internal lateinit var searchEngineProvider: SearchEngineProvider

    @Inject
    internal lateinit var keyphoneReadingMode: KeyphoneReadingMode

    @Inject
    internal lateinit var keyphoneScroll: KeyphoneScroll

    @Inject
    internal lateinit var rendererBusyHolder: acr.browser.lightning.di.RendererBusyHolder

    /** 顶部标签栏 */
    private var tabBarScroll: HorizontalScrollView? = null
    private var tabBarContainer: LinearLayout? = null

    /** 标签栏上次渲染签名（id/title/选中态/主页归属），签名不变时 [renderTabBar] 跳过重渲染 */
    private var lastTabBarSignature: List<TabBarEntry>? = null

    /** [lastTabBarSignature] 的条目；cell 只依赖这四个字段渲染，其余字段变化不触发重建 */
    private data class TabBarEntry(val id: Int, val title: String, val selected: Boolean, val home: Boolean)

    /** 顶栏地址栏容器与输入框 */
    private var addressBarContainer: LinearLayout? = null
    private var addressInput: EditText? = null
    private var progressBar: KeyphoneRainbowProgressBar? = null

    /** 主页容器与列表焦点助手 */
    private var homeScroll: ScrollView? = null
    private var homeRowsContainer: LinearLayout? = null
    private var focusHelper: KeydroidxListFocusHelper? = null

    /** Web 宿主容器 */
    private var browserFrameHost: FrameLayout? = null
    private lateinit var browserFrame: FrameLayout
    private var contentFrame: FrameLayout? = null

    /** 加载中占位层（网页态但页面还没加载完时盖在内容区上，见 keyphone_browser.xml） */
    private var pageLoading: View? = null
    private var pageLoadingText: TextView? = null

    /**
     * 已点击打开、但页面还没呈现出来的 URL（非空即「网页态 + 内容区正在加载」）。
     *
     * 背景：主页点书签走 [openUrlInNewTab] 时，要等 `KeyphoneUrls.normalize` 读 DataStore、
     * 新建 WebView 时读 19 项 TabSettings、拼请求头再读 3 项偏好，整条链走完才
     * `loadUrl`，而 `presenter.onEvent` 只是异步 `launch` 不 await —— 中间这几百毫秒
     * 里主页已隐藏、内容区是空的，顶栏 2dp 进度线又因为 `progressStateFlow` 初值为 100
     * 而不显示，用户看到的就是「点了没反应、像卡住」。本字段让点击瞬间就切到网页态
     * 并显示加载提示，等 WebView 真正 attach、state 也确认已切页后由 [observeState] 清除。
     */
    private var pendingOpenUrl: String? = null

    /**
     * 加载遮罩开始显示的时刻（0 = 当前遮罩不显示）。仅用于诊断日志：
     * 遮罩一盖上就把内容区全挡住，用户感知是「黑屏 / 一直加载」，必须能看到它挂了多久。
     */
    private var pageLoadingShownAt = 0L

    /** 遮罩已显示多久（未显示返回 0） */
    private fun pageLoadingBusyMs(): Long =
        if (pageLoadingShownAt == 0L) 0L else SystemClock.elapsedRealtime() - pageLoadingShownAt

    /** [ensureHomeWindow] 最近一次派发新建主页的时间戳，用于在途短路（见该方法注释） */
    private var homeWindowDispatchAt = 0L

    /** 鼠标模式（仅网页态）：微型光标（RenderNode 纯矩阵变换）+ 全屏菜单浮层（按需展示） */
    private var cursorView: KeyphoneCursorView? = null
    private var shellOverlay: KeyphoneShellOverlay? = null
    private var mouseController: KeyphoneMouseController? = null

    /** 确认键已向 WebView 派发 DOWN 尚未派发 UP（防止触摸序列悬挂） */
    private var mouseTouchDown = false

    /** 按住 5 键作为修饰键与方向键配合光标定位滚动 */
    private var cursorScrollModifierHeld = false
    private var cursorScrollModifierUsed = false
    private var cursorScrollModifierDownAt = 0L
    private var cursorScrollDirectionKeyCode = KeyEvent.KEYCODE_UNKNOWN

    /** 网页输入劫持：探测定时器与状态 */
    private val inputProbeHandler = Handler(Looper.getMainLooper())
    private var webInputProbeAttempt = 0

    /** 探测命中的输入框是否应在确认回填后模拟 Enter/提交表单（poll() 的 submit 字段） */
    private var webInputPendingSubmit = false

    /** 网页输入探测结果：value=当前值，multiline=多行输入，submit=确认后是否提交 */
    private data class WebInputProbe(val value: String, val multiline: Boolean, val submit: Boolean)

    /** 已注入 JS 模块的「标签:URL」键，避免重复 evaluate */
    private var webInputInjectedKey: String? = null

    /** 已注入光标滚动脚本的「标签:URL」键：页面导航后键值变化会自动重新注入 */
    private var scrollModuleKey: String? = null

    /** UC 风格数字快捷菜单 */
    private enum class QuickMenuType {
        NONE,
        PAGE_MENU,   // 1 键：页面/窗口操作
        GLOBAL_MENU, // # 键：全局工具导航
        URL_MENU,    // # 菜单 7：专属按键适配的网址确认面板
        JUMP_MENU    // # 菜单 4：站内跳转拦截开关（级联子面板）
    }

    private var activeMenuType = QuickMenuType.NONE
    private var quickMenuFocusIndex = 0
    private var lastConsumedDownKeyCode: Int = KeyEvent.KEYCODE_UNKNOWN

    /**
     * 动作键（0~9 / * / #）防重入状态。
     *
     * 设备连击、手指回弹或固件抖动会在一次物理按下后，再补一个 repeatCount=0 的 DOWN。
     * 此时快捷菜单往往已经打开，第二个 DOWN 会被当成「执行菜单项」——`#` 菜单里
     * `#` 对应「切换全屏」、`*` 对应「无图切换」；`*` 菜单里 `*` 对应「关闭窗口」——
     * 用户看到的就是「按一下 # 却退出了全屏」。
     */
    private var actionKeyGuardCode: Int = KeyEvent.KEYCODE_UNKNOWN
    private var actionKeyGuardUntil: Long = 0L

    /** 全屏与滚动切换状态 */
    private var isFullScreenMode = false
    private var isScrolledToBottom = false

    /** 页内查找控制条与状态 */
    private var findBarContainer: LinearLayout? = null
    private var findQueryText: TextView? = null
    private var findCountText: TextView? = null
    private var isFindMode = false
    private var currentFindQuery = ""

    /** 自由复制模式状态 */
    private var isFreeCopyMode = false
    private var isCopySelecting = false

    /** 专属按键适配（按网址自定义 1~9 落点）状态 */
    private var isCalibrateMode = false
    private var calibratingDigit = 0
    private var calibrateSiteKey = ""
    private var calibrateStatusText: TextView? = null

    /** 本次标定的内存映射（digit -> 比例坐标）：保存后立即生效，退出时与磁盘一致 */
    private val calibratePoints = HashMap<Int, Pair<Float, Float>>()

    /** 当前是否停留在「主页」标签 */
    private var isHomeSelected = true

    /** 主页书签与历史数据 */
    private var bookmarks: List<Bookmark.Entry> = emptyList()
    private var history: List<HistoryEntry> = emptyList()

    /** 书签宫格状态 */
    private var gridStart = -1
    private var gridCount = 0
    private var isBookmarkEditMode = false
    private var bookmarkCells: List<KeyphoneViews.BookmarkCell> = emptyList()

    private var selectLongPressJob: Job? = null
    private var isSelectLongPressed = false

    /** 当前网页应显示的地址文本 */
    private var addressText: String = ""
    private var settingAddressText = false
    private var addressEditedByUser = false

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (activeMenuType != QuickMenuType.NONE) {
                hideQuickMenu()
                return
            }
            if (isReadingMode()) {
                exitReadingMode()
                return
            }
            if (isFindMode) {
                exitFindMode()
                return
            }
            if (isFreeCopyMode) {
                exitFreeCopyMode()
                return
            }
            if (isCalibrateMode) {
                exitCalibrateMode()
                return
            }
            if (isBookmarkEditMode) {
                exitBookmarkEditMode()
            }
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { presenter.onEvent(BrowserUiEvent.FileChooserResult(it)) }

    /** 选项页回传的动作 */
    private val optionsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = result.data?.getStringExtra(KeyphoneOptionsActivity.EXTRA_ACTION)
        if (action != null) {
            onPageMenuAction(action)
        }
    }

    override fun getContentLayoutRes(): Int = R.layout.keyphone_browser

    override fun onCreate(savedInstanceState: Bundle?) {
        val frame = FrameLayout(this)
        browserFrame = frame
        injector.browserComponentBuilder()
            .activity(this)
            .incognitoMode(false)
            .browserFrame(frame)
            .customFrame(FrameLayout(this))
            .initialIntent(intent.takeIf { savedInstanceState == null })
            .build()
            .inject(this)

        super.onCreate(savedInstanceState)
        // 把渲染繁忙预判注入 app 作用域 holder，供 TabsRepository/TabAdapter 走降级路径
        rendererBusyHolder.provider = { isRendererLikelyBusy() }
        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        presenter.onViewAttached(this)
        tabPager.longPressListener = { id, longPress ->
            presenter.onEvent(BrowserUiEvent.PageLongPress(id, longPress))
        }

        supportFragmentManager.addOnBackStackChangedListener { updateTopBar() }
        observeState()

        val initialAction = intent?.let { intentExtractor.extractUrlFromIntent(it) }
        if (initialAction != null) {
            isHomeSelected = false
        }

        if (intent?.getBooleanExtra(EXTRA_SHOW_TABS, false) == true) {
            showTabsPage()
        }
        // 「回首页」入口（见 BrowserHomeActivity）：等标签恢复落定后再切主页，
        // 否则读到的还是空 tabs，会多建一个主页窗口
        if (intent?.getBooleanExtra(EXTRA_SHOW_HOME, false) == true) {
            goHomeWhenTabsReady()
        }
    }

    override fun onInitViews() {
        // 移除电量/信号顶栏，隐藏默认标题栏
        setStatusBarVisible(false)
        findViewById<View>(CommonR.id.topPanel)?.visibility = View.GONE

        tabBarScroll = findViewById(R.id.keyphone_tab_bar_scroll)
        tabBarContainer = findViewById(R.id.keyphone_tab_bar_container)

        addressBarContainer = findViewById(R.id.keyphone_address_bar_container)
        setUpAddressBar()

        findBarContainer = findViewById(R.id.keyphone_find_bar_container)
        findQueryText = findViewById(R.id.keyphone_find_query_text)
        findCountText = findViewById(R.id.keyphone_find_count_text)

        progressBar = findViewById(R.id.keyphone_progress)
        pageLoading = findViewById(R.id.keyphone_page_loading)
        pageLoadingText = findViewById(R.id.keyphone_page_loading_text)

        homeScroll = findViewById(R.id.keyphone_home_scroll)
        homeRowsContainer = findViewById(R.id.keyphone_home_rows)
        focusHelper = homeScroll?.let { KeydroidxListFocusHelper(it) }

        val contentFrameView = findViewById<FrameLayout>(R.id.keyphone_content_frame)
        contentFrame = contentFrameView

        browserFrameHost = findViewById(R.id.keyphone_browser_frame_host)
        browserFrameHost?.addView(
            browserFrame,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // 微型光标视图：硬件加速 RenderNode 矩阵位移，0 重绘开销，消除全屏合成卡顿
        val cursor = KeyphoneCursorView(this).apply { visibility = View.GONE }
        contentFrameView?.addView(
            cursor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        cursorView = cursor

        // 全屏覆盖层：仅用于承载快捷菜单，平时 GONE，绝不常驻合成管线
        val overlay = KeyphoneShellOverlay(this)
        contentFrameView?.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        shellOverlay = overlay

        mouseController = KeyphoneMouseController(
            host = browserFrameHost!!,
            cursorView = cursor,
            webViewProvider = { currentWebView() },
            ensureScrollModule = { ensureCursorScrollModule(it) },
            pageBusyProvider = { isRendererLikelyBusy() }
        )

        // 快捷菜单绘制已迁入 shellOverlay（独立 Surface），主窗口不再持有菜单视图

        registerWebPromptLifecycle()
        installMainThreadWatchdog()
        installReadingWatchdog()
        installReadingFrameWatchdog()
        installMainLoopTracer()
        updateTabMode()
        loadHomeData()
    }

    /**
     * 主线程消息探针：生产与调试均停用，避免每秒数百次 Looper 字符串格式化与 Logcat 写入拖垮弱机主线程吞吐。
     */
    private fun installMainLoopTracer() {
        // 彻底停用，消除主线程严重性能拖累
    }

    /**
     * 主线程阻塞看门狗（仅 debug）：主线程连续停在同一个调用上超过约 2s（且不是 Looper 空闲等待）时，
     * 把它的 Java 栈打进日志。
     *
     * 用途：定位「网页把渲染进程占死时，宿主也跟着卡死、最后被系统按 ANR 杀掉」到底是卡在哪一步
     * —— 这类问题看不见栈就只能猜。release 构建不启动。
     */
    private fun installMainThreadWatchdog() {
        if (!BuildConfig.DEBUG) return
        val main = Looper.getMainLooper().thread
        Thread {
            var busySince = 0L
            var lastLogAt = 0L
            while (!isFinishing && !isDestroyed) {
                try {
                    Thread.sleep(MAIN_WATCH_INTERVAL_MS)
                } catch (t: InterruptedException) {
                    return@Thread
                }
                val stack = main.stackTrace
                if (stack.isEmpty()) continue
                val top = stack[0]
                val now = SystemClock.elapsedRealtime()
                // 只有停在 Looper 取消息才算空闲；其余都算「主线程在忙」
                val idle = top.className.contains("MessageQueue") ||
                    top.methodName.contains("nativePollOnce")
                if (idle) {
                    if (busySince != 0L) {
                        val busyMs = now - busySince
                        if (busyMs >= MAIN_WATCH_MIN_BUSY_MS) {
                            KeydroidxLog.w(TAG, "主线程连续忙 ${busyMs}ms 后恢复空闲")
                        }
                    }
                    busySince = 0L
                    continue
                }
                if (busySince == 0L) {
                    busySince = now
                    continue
                }
                val busyMs = now - busySince
                // 关键：不管栈是否变化，只要一直不空闲就抓一次（栈会变的情况以前会漏掉）
                if (busyMs >= MAIN_WATCH_MIN_BUSY_MS && now - lastLogAt >= MAIN_WATCH_LOG_INTERVAL_MS) {
                    lastLogAt = now
                    KeydroidxLog.e(
                        TAG,
                        "主线程持续非空闲 ${busyMs}ms，当前栈:\n\t" +
                            stack.take(MAIN_WATCH_STACK_FRAMES).joinToString("\n\t")
                    )
                }
            }
        }.apply {
            isDaemon = true
            name = "keyphone-main-watch"
        }.start()
    }

    override fun onResume() {
        super.onResume()
        KeyphoneUrlGuard.attach(guardHost)
        // 只有当前挂在浏览容器里的 WebView 才算「可见页」：后台标签页的弹窗一律静默处理，
        // 否则广告站的后台标签能随时把确认框盖到用户正在看的页面上
        KeyphoneJsDialogs.attach(jsDialogHost) { view -> view === currentWebView() }
        presenter.onViewShown()
        // 回前台：先复位阅读看门狗的前台标记与帧心跳，再让探针重新开始计时。
        // 后台期间 Surface 已销毁、合成器停产，帧回调必然不回来；若把后台挂着的那次
        // pending 带回前台，第一个采样就会拿「后台时长」当作合成卡死，进而触发
        // [recoverBrokenReader]（用户视角：挂机键回桌面再进来，阅读模式被踢掉）。
        isInForeground = true
        readerFramePending = false
        readerFrameRequestedAt = 0L
        readerFrameLastLogAt = 0L
        // 后台期间错过的阅读自动重进（见 [maybeReenterReading]）：回前台补一次判定
        maybeReenterReading(presenter.state.value)
        // 回前台立刻校时（后台期间可能已跨分钟），再对齐下一次整分刷新
        refreshBottomClock()
        scheduleBottomClock()
        // 页面无响应看门狗（保活提示；见 checkPageResponsive）
        schedulePageBusyTick()
        if (isHomeSelected) {
            loadHomeData()
        }
    }

    override fun onPause() {
        // 后台不做任何阅读采样 / 帧探测：后台没有「用户看得见的黑屏」，探测只会误判
        isInForeground = false
        bottomClockHandler.removeCallbacks(bottomClockTick)
        pageBusyHandler.removeCallbacks(pageBusyTick)
        pagePingPending = false
        setPageBusy(false)
        KeyphoneUrlGuard.detach(guardHost)
        KeyphoneJsDialogs.detach(jsDialogHost)
        super.onPause()
        selectLongPressJob?.cancel()
        if (mouseTouchDown) {
            dispatchCursorTouch(MotionEvent.ACTION_UP)
        }
        mouseController?.stopRepeat()
        resetCursorScrollModifierState()
        cancelWebInputProbe()
        presenter.onViewHidden()
    }

    override fun onDestroy() {
        // 复位，避免 holder 持有已销毁 Activity 的 lambda（其它浏览器 Activity 默认 false）
        rendererBusyHolder.provider = { false }
        super.onDestroy()
        presenter.onViewDetached()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask 复用同一实例：新 intent 必须写回，否则 getIntent() 仍停在首次启动那个；
        // 同时保证后续按键（如右软键退出、分享、书签）读到的是最新意图
        setIntent(intent)
        KeydroidxLog.d(
            TAG,
            "onNewIntent: action=${intent.action} data=${intent.data} " +
                "showHome=${intent.getBooleanExtra(EXTRA_SHOW_HOME, false)} " +
                "showTabs=${intent.getBooleanExtra(EXTRA_SHOW_TABS, false)}"
        )
        val action = intentExtractor.extractUrlFromIntent(intent)
        if (action != null) {
            isHomeSelected = false
            progressBar?.startLoading(12)
            presenter.onEvent(BrowserUiEvent.NewAction(action))
            updateTabMode()
        } else if (intent.getBooleanExtra(EXTRA_SHOW_HOME, false)) {
            // 复用实例时必须显式回主页（新实例靠初始状态就是主页，这里不会）
            goHomeWhenTabsReady()
        }
        if (intent.getBooleanExtra(EXTRA_SHOW_TABS, false)) {
            showTabsPage()
        }
    }

    /**
     * 切回按键机主页（搜索框 + 书签宫格），等标签列表就绪后再动手。
     *
     * `presenter` 的事件是异步派发的，刚 `onNewIntent` / `onCreate` 时读到的 tabs 可能是空的，
     * 直接调 [switchToHome] 会白建一个主页窗口；与 [showTabsPage] 同一策略。
     */
    private fun goHomeWhenTabsReady() {
        lifecycleScope.launch {
            withTimeoutOrNull(TABS_WAIT_MS) {
                presenter.state.first { it.tabs.isNotEmpty() }
            }
            switchToHome()
            updateTabMode()
            KeydroidxLog.d(TAG, "回到按键机主页（复用实例）")
        }
    }

    // region 顶部标签栏与模式切换

    /**
     * 判断指定标签页是否处于「原生主页」状态（显示九宫格，隐藏 WebView）。
     *
     * 只有真·主页算主页：空白地址、`about:home`、`about:blank`、主页 HTML。
     * 书签 / 历史 / 下载这些内部页同样是 `about:` 开头或 `file://...html`（[isSpecialUrl]），
     * 但它们的内容由 WebView 渲染，**必须显示 WebView** —— 之前一律判成主页，
     * 于是「打开历史记录」看到的是主页（内部页被藏起来了）。
     */
    private fun isTabHome(tab: TabViewState?): Boolean {
        if (tab == null) return true
        val url = tab.url.trim()
        if (url.isBlank()) return true
        if (url == SCHEME_HOMEPAGE || url == SCHEME_BLANK) return true
        if (url.isSpecialUrl()) {
            return url.endsWith(HomePageFactory.FILENAME)
        }
        return false
    }

    /** 刷新 UC 风格顶部标签栏与主内容区可见性 */
    private fun updateTabMode() {
        val currentTab = presenter.state.value.tabs.firstOrNull { it.isSelected }
        // pendingOpenUrl 非空 =「刚点了书签/历史/提交地址，页面还在路上」。此刻 state 往往
        // 还停在被点中的那个主页标签（切页尚未落定），若按 state 重算就会继续留在主页，
        // 用户点了完全看不到反应。这里强制走网页态，等 observeState 确认 state 已切页后
        // 再把归属交回 state。
        isHomeSelected = pendingOpenUrl == null && (currentTab == null || isTabHome(currentTab))
        val wv = currentWebView()
        KeydroidxLog.i(TAG, "updateTabMode: isHomeSelected=$isHomeSelected, tab=${currentTab?.url}, wv=$wv (w=${wv?.width}, h=${wv?.height}, vis=${wv?.visibility}, parent=${wv?.parent})")

        // 内容区切换
        if (isHomeSelected) {
            // 任何切回主页的路径（点顶栏主页标签、按键机主页键、关闭网页标签等）
            // 都要重新拉一次书签/历史：书签可能在其他标签页或书签页被增删改。
            loadHomeData()
            if (isFindMode) {
                exitFindMode()
            }
            if (isFreeCopyMode) {
                exitFreeCopyMode()
            }
            homeScroll?.visibility = View.VISIBLE
            browserFrameHost?.visibility = View.GONE
            addressBarContainer?.visibility = View.GONE
            findBarContainer?.visibility = View.GONE
            progressBar?.reset()
            if (activeMenuType == QuickMenuType.NONE) {
                updateHomeSoftKeys()
            }
        } else {
            homeScroll?.visibility = View.GONE
            browserFrameHost?.visibility = View.VISIBLE
            val wv = currentWebView()
            if (wv != null) {
                wv.onResume()
                wv.resumeTimers()
            } else {
                presenter.onViewShown()
            }
            if (isFindMode) {
                addressBarContainer?.visibility = View.GONE
                findBarContainer?.visibility = View.VISIBLE
                if (activeMenuType == QuickMenuType.NONE) {
                    updateFindSoftKeys()
                }
            } else if (isFreeCopyMode) {
                addressBarContainer?.visibility = View.GONE
                findBarContainer?.visibility = View.VISIBLE
                if (activeMenuType == QuickMenuType.NONE) {
                    updateFreeCopySoftKeys()
                }
            } else {
                findBarContainer?.visibility = View.GONE
                if (!isFullScreenMode) {
                    addressBarContainer?.visibility = View.VISIBLE
                }
                if (activeMenuType == QuickMenuType.NONE) {
                    updateWebSoftKeys()
                }
            }
            applyAddressBar()
        }

        if (isFullScreenMode) {
            tabBarScroll?.visibility = View.GONE
            addressBarContainer?.visibility = View.GONE
            findBarContainer?.visibility = View.GONE
            findViewById<View>(CommonR.id.bottomPanel)?.visibility = View.GONE
        } else {
            tabBarScroll?.visibility = View.VISIBLE
            findViewById<View>(CommonR.id.bottomPanel)?.visibility = View.VISIBLE
        }

        renderTabBar()
        updatePageLoading(wv)
        updateMouseCursorVisibility()
    }

    /**
     * 加载中占位层的显隐（见 [pendingOpenUrl]）。
     *
     * 判据是「页面还没加载完」，三种情况都要盖住：
     * - [currentWebView] 取到 null：该标签的 WebView 还没被 TabPager addView 进容器
     *   （建 WebView 要读 18 项 TabSettings、再设 40 多个 settings）；
     * - [pendingOpenUrl] 非空：已经点了，但 `loadUrl` 还没让页面跑起来；
     * - progress < 100：已经开始加载，但首屏还没画完 —— **这一段最长（实测 1.5s+）**。
     *
     * 为什么一直盖到 progress 100：WebView 一 attach 就是白底，从 attach 到首帧绘制之间
     * （实测 1.5s 以上）什么都没有，正是用户说的「点了没反应、以为卡住」。中途撤遮罩就会
     * 把这段白屏露出来，所以宁可让「正在打开…」多挂一会儿，也不要给用户一片空白。
     */
    private fun updatePageLoading(webView: WebView?) {
        val host = pageLoading ?: return
        val progress = presenter.state.value.progress
        val show = !isHomeSelected && (webView == null || pendingOpenUrl != null || progress < 100)
        host.visibility = if (show) View.VISIBLE else View.GONE
        if (show && pageLoadingShownAt == 0L) {
            pageLoadingShownAt = SystemClock.elapsedRealtime()
        } else if (!show) {
            pageLoadingShownAt = 0L
        }
        // 遮罩盖住整个内容区（背景 #121D2C），只要它一直 VISIBLE，用户看到的就永远是
        // 「正在打开…」或者一片空白——阅读模式黑屏事故的头号嫌疑就在这里，故每次显隐都记日志
        // 落盘走生态标准 KeydroidxLog（BrowserApp 已 init，详见 BrowserApp.onCreate 的说明）
        KeydroidxLog.d(TAG, "加载遮罩 ${if (show) "显示" else "隐藏"}: home=$isHomeSelected" +
            " wv=${webView != null} pending=${pendingOpenUrl} progress=$progress" +
            " busy=${pageLoadingBusyMs()}ms id=${System.identityHashCode(webView)}")
    }

    /** 内容区当前标签的现场快照（阅读模式黑屏排查用，见 [reportReadingTabState]） */
    private fun describeReadingTab(): String {
        val page = getCurrentPage()
        return "home=$isHomeSelected tab=${selectedTabId()}" +
            " wv=${currentWebView() != null} progress=${presenter.state.value.progress}" +
            " pending=$pendingOpenUrl overlay=${activeMenuType} find=$isFindMode copy=$isFreeCopyMode" +
            " fullscreen=$isFullScreenMode page=${page?.javaClass?.simpleName}"
    }

    /** 渲染顶部标签栏：所有打开的标签页（主页 / 各网页） */
    private fun renderTabBar() {
        val container = tabBarContainer ?: return
        val tabs = presenter.state.value.tabs

        // 签名守卫：标签结构 / 标题 / 选中态 / 主页归属都没变时跳过整棵重建。
        // updateTabMode 的调用方很多（state 脉冲、按键、onResume、软键刷新…），
        // 加载期 progress 脉冲曾让这里每次 removeAllViews + 全量重建 cell（弱机假死主因之一）。
        val signature = ArrayList<TabBarEntry>(tabs.size)
        for (tab in tabs) {
            signature.add(TabBarEntry(tab.id, tab.title, tab.isSelected, isTabHome(tab)))
        }
        if (signature == lastTabBarSignature) {
            return
        }
        lastTabBarSignature = signature

        container.removeAllViews()
        if (tabs.isEmpty()) return

        var selectedChildView: View? = null

        tabs.forEachIndexed { index, tab ->
            val isHome = isTabHome(tab)
            val title = if (isHome) {
                getString(R.string.keyphone_tab_home)
            } else {
                tab.title.ifBlank { getString(R.string.keyphone_tab_untitled) }
            }

            val item = KeyphoneViews.UcTabItem(
                id = tab.id,
                title = title,
                isSelected = tab.isSelected
            )

            val cell = KeyphoneViews.ucTabCell(this, item) { clickedItem ->
                val targetIndex = tabs.indexOfFirst { it.id == clickedItem.id }
                if (targetIndex >= 0) {
                    switchToTab(targetIndex)
                }
            }
            if (item.isSelected) {
                selectedChildView = cell
            }
            container.addView(cell)
        }

        // 自动滚动让当前选中的标签居中可见
        selectedChildView?.let { view ->
            tabBarScroll?.post {
                val scrollX = view.left - (tabBarScroll?.width ?: 0) / 3
                tabBarScroll?.smoothScrollTo(scrollX.coerceAtLeast(0), 0)
            }
        }
    }

    /** 切换到主页标签页（若当前未处于主页标签，切到现存主页或新建主页） */
    private fun switchToHome() {
        pendingOpenUrl = null
        isScrolledToBottom = false
        val tabs = presenter.state.value.tabs
        val currentTab = tabs.firstOrNull { it.isSelected }
        if (currentTab != null && isTabHome(currentTab)) {
            loadHomeData()
            return
        }
        val homeIndex = tabs.indexOfFirst { isTabHome(it) }
        if (homeIndex >= 0) {
            switchToTab(homeIndex)
        } else {
            // 走幂等版本：本函数也会与 Presenter 侧 closeBrowser → onAllTabsClosed
            // 抢跑（标签页页删最后一个标签时 deleteFocused 同时调 closeTab 和
            // goHomeFromTabs），无条件建主页会多出一个窗口。
            ensureHomeWindow()
        }
    }

    /** 新建一个主页窗口（在顶栏新建一个标签页，底层 WebView 保持空白静止不发起网络请求） */
    private fun createNewWindow() {
        isScrolledToBottom = false
        KeydroidxLog.i(TAG, "createNewWindow: 派发新建主页窗口")
        presenter.onEvent(BrowserUiEvent.NewAction(BrowserContract.Action.LoadUrl("")))
        loadHomeData()
    }

    /**
     * 幂等地确保「当前处于主页窗口」——关掉标签后的**唯一**兜底入口。
     *
     * 背景：新建主页有三条会汇合的路径（[closeTab] 的空列表兜底、Presenter 的
     * `onTabClose`/`CLOSE_ALL` → `navigator.closeBrowser` → [onAllTabsClosed]、
     * [switchToHome]），而 [presenter] 的事件是**异步派发**的，keyphone 侧读
     * `state.value` 常常还是关闭前的旧快照，于是同一轮关闭里两条路径都会各自
     * 建一个主页窗口（顶栏出现两个「首页」）。本方法按当前 tabs 状态收敛：
     * 已有主页就切过去（没有才新建），重复调用不会多建。
     */
    private fun ensureHomeWindow() {
        if (isFinishing || isDestroyed) return
        val tabs = presenter.state.value.tabs
        val homeIndex = tabs.indexOfFirst { isTabHome(it) }
        when {
            homeIndex < 0 -> {
                // 光靠 tabs 判幂等是不够的：presenter.onEvent(AddTab) 是异步派发，
                // state 要几百毫秒才落定，这段窗口里 closeTab / switchToHome /
                // onAllTabsClosed 可能各来一次（实测日志里两次 ensureHomeWindow 只隔
                // 5ms，读到的 tabs 是同一个旧快照），于是各派发一次 AddTab，
                // 顶栏就多出一个主页。用在途时间戳短路，等 state 里真的出现主页标签
                // 或超时（3s，兜住事件丢失）后再放行。
                val now = SystemClock.uptimeMillis()
                if (now - homeWindowDispatchAt < HOME_WINDOW_DISPATCH_TIMEOUT) {
                    KeydroidxLog.i(TAG, "ensureHomeWindow: 已有在途的新建，跳过（tabs=${tabs.size}）")
                    return
                }
                homeWindowDispatchAt = now
                KeydroidxLog.i(TAG, "ensureHomeWindow: 无主页标签，新建一个（tabs=${tabs.size}）")
                createNewWindow()
            }

            tabs[homeIndex].isSelected -> loadHomeData()
            else -> switchToTab(homeIndex)
        }
    }

    /** 切换到指定 Web 标签页 */
    internal fun switchToTab(index: Int) {
        val tabs = presenter.state.value.tabs
        if (index in tabs.indices) {
            // 用户主动切走 → 放弃上一个还没打开完的地址，别让加载中遮住新页面
            pendingOpenUrl = null
            if (isFindMode) {
                exitFindMode()
            }
            if (isFreeCopyMode) {
                exitFreeCopyMode()
            }
            isScrolledToBottom = false
            presenter.onEvent(BrowserUiEvent.TabClick(index))
            updateTabMode()
        }
    }

    /** 按键 0：循环切换下一个标签页 */
    private fun cycleNextTab() {
        val tabs = presenter.state.value.tabs
        if (tabs.size <= 1) return
        val currentIndex = tabs.indexOfFirst { it.isSelected }
        val nextIndex = if (currentIndex < 0 || currentIndex >= tabs.size - 1) 0 else currentIndex + 1
        switchToTab(nextIndex)
    }

    /** 打开网址：若当前标签是主页空白页直接在当前标签加载，否则新建标签页加载 */
    internal fun openUrlInNewTab(url: String) {
        KeydroidxLog.i("KeyphoneProgress", "[Activity.openUrlInNewTab] url=$url")
        KeydroidxLog.d(TAG, "openUrlInNewTab: $url")
        if (isFindMode) {
            exitFindMode()
        }
        if (isFreeCopyMode) {
            exitFreeCopyMode()
        }
        clearReadingModeOnNavigation()

        // 点击瞬间就切到「网页态 + 加载中」，把反馈放在协程之前：
        // 下面 normalize 要读 DataStore，新建 WebView 要串行读 18 项 TabSettings 和
        // 3 项请求头偏好（且都跑在主线程上），整条链走完才有 loadUrl。这些时间里
        // 页面一行都画不出来，若还停在主页，用户就是「点了没反应」。
        pendingOpenUrl = url
        isHomeSelected = false
        addressText = url
        isScrolledToBottom = false
        showPageLoadingNow()
        armPendingOpenWatchdog()
        progressBar?.startLoading(12)

        lifecycleScope.launch {
            try {
                val normalizedUrl = KeyphoneUrls.normalize(url, searchEngineProvider)
                val currentTab = presenter.state.value.tabs.firstOrNull { it.isSelected }
                if (currentTab != null && isTabHome(currentTab)) {
                    presenter.onEvent(BrowserUiEvent.SearchConfirmed(normalizedUrl))
                } else {
                    presenter.onEvent(
                        BrowserUiEvent.NewAction(
                            BrowserContract.Action.LoadUrl(normalizedUrl)
                        )
                    )
                }
                updateTabMode()
                presenter.onViewShown()
            } catch (t: Throwable) {
                KeydroidxLog.e(TAG, "打开网址失败: $url", t)
                // 失败时不能把「加载中」挂着，否则内容区永远卡在转圈
                clearPendingOpen()
            }
        }
    }

    /**
     * 「页面正在打开」兜底：到点还在 pending 就记日志并检查 WebView 是否其实早就有内容了。
     *
     * 背景：pendingOpenUrl 只在 [observeState] 看到「WebView 已 attach 且当前标签不再是主页」时清除。
     * 这条判据依赖 state 在**打开之后**还发射一次；一旦那次发射恰好发生在 WebView attach 之前
     * （弱机上很常见：建 WebView 要串行读 20 多项偏好），pending 就再也没人清，遮罩
     * （pageLoading，全屏不透明底）永久盖住内容区 —— 用户看到的就是「界面全空 / 黑屏」，
     * 且它会连带让 [sendPendingOpenUrl] 永久跳过重新导航。
     *
     * 这里只在**兜底**时清：到点后若当前页已有 URL（不是空白页）说明页面其实已经打开了，
     * 清 pending 并重算界面；若仍是空白页则保留 pending（导航可能真在路上），只留日志。
     */
    private fun armPendingOpenWatchdog() {
        pendingOpenWatchHandler.removeCallbacks(pendingOpenWatchdog)
        pendingOpenWatchHandler.postDelayed(pendingOpenWatchdog, PENDING_OPEN_TIMEOUT_MS)
    }

    private val pendingOpenWatchHandler = Handler(Looper.getMainLooper())

    private val pendingOpenWatchdog = Runnable {
        val pending = pendingOpenUrl ?: return@Runnable
        val current = try {
            currentWebView()?.url.orEmpty()
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "兜底检查待打开地址失败: $pending", t)
            ""
        }
        val alreadyLoaded = current.isNotBlank() && !current.isSpecialUrl()
        KeydroidxLog.w(
            TAG,
            "待打开地址超时未确认: pending=$pending wvUrl=$current alreadyLoaded=$alreadyLoaded" +
                " ${describeReadingTab()}"
        )
        if (alreadyLoaded) {
            pendingOpenUrl = null
            updateTabMode()
        }
    }

    /** 放弃「页面正在打开」状态并按当前 state 重算界面（打开失败或用户切走了） */
    private fun clearPendingOpen() {
        if (pendingOpenUrl == null) return
        pendingOpenUrl = null
        updateTabMode()
    }

    /**
     * 点击瞬间把内容区切到「网页态 + 加载中」，**只动可见性**。
     *
     * 为什么不在这里直接调 [updateTabMode]：它的网页态分支在没有 WebView 时会调
     * `presenter.onViewShown()` → `currentTab.foreground()` → `TabPager.selectTab(id)`，
     * 而 `selectTab` 里是 `webViews[id]!!` —— 被点中的主页标签（NoOp 初始化）若还没
     * 真正创建 WebView，这里就会 NPE。此刻 presenter 还没收到事件，正是那个最危险的时刻。
     *
     * 顶栏标签栏、底部软键这些由紧随其后的 state 发射经 [observeState] → [updateTabMode]
     * 补齐（通常几十毫秒内），用户无感。
     */
    private fun showPageLoadingNow() {
        homeScroll?.visibility = View.GONE
        browserFrameHost?.visibility = View.VISIBLE
        addressBarContainer?.visibility = View.VISIBLE
        findBarContainer?.visibility = View.GONE
        pageLoading?.visibility = View.VISIBLE
        applyAddressBar()
    }

    // endregion

    // region 阅读模式（按键 * 菜单 6：正文提纯 + 下一页自动拼页）

    /**
     * 阅读模式会话。
     *
     * 阅读模式把「进入时那一章 → 后面拼进来的每一章」放进同一张 DOM 里连续滚动。为了让阅读模式
     * 等价于**普通浏览**（地址栏、历史记录、关掉浏览器后下次启动恢复都停在最后读到的那一章），
     * 这里跟着 JS 的拼页进展记账：
     * - [recorded]：已写进 URL 历史的章节，逐条入库（见 [pullReadingProgress]）；
     * - [lastUrl]：最后拼进来的一章，退出即导航过去（见 [exitReadingMode]）。
     *
     * 渲染隔离后阅读页是 `loadDataWithBaseURL` 的外壳，`webView.getUrl()` 返回 `about:blank`，
     * 因此 [lastUrl] 同时是「这个标签的真实 URL」的唯一事实源（见 [currentRealUrl]）。
     */
    private class ReadingSession(val entryUrl: String) {
        val recorded = HashSet<String>()

        /** 拼页轨迹：按阅读顺序的全部章节（含进入时那一章），退出导航落地后据此补写历史标题。 */
        var trail: List<ReadingPage> = emptyList()
        var lastUrl: String = entryUrl
        var lastTitle: String = ""
        var chapterCount: Int = 1

        /** 已落盘的最后章节（去重写盘：翻页/拼章会频繁更新 lastUrl） */
        var persistedUrl: String = ""
    }

    /**
     * 阅读会话落盘：Activity 被系统回收 / 进程被杀后，重进能把用户带回**最后读到的章节**
     * （浏览内核只记得入口章，否则就是「章节错了」）。见 [KeyphoneReadingSessionStore]。
     */
    private fun persistReadingSession(session: ReadingSession) {
        val target = session.lastUrl.ifBlank { session.entryUrl }
        if (target == session.persistedUrl) return
        session.persistedUrl = target
        KeyphoneReadingSessionStore.save(
            this,
            session.entryUrl,
            target,
            session.chapterCount
        )
    }

    /** 清掉落盘的阅读会话（真实退出 / 页面被导航替换时调用） */
    private fun clearPersistedReadingSession() {
        KeyphoneReadingSessionStore.clear(this)
    }

    /** 阅读页启动校验定时器（见 [verifyReaderBoot]） */
    private val readerBootHandler = Handler(Looper.getMainLooper())

    /**
     * 阅读页被宿主替换后的恢复落点：恢复导航的目标章节 URL。
     * 页面加载完成（progress==100 且 URL 命中）后自动重进阅读模式（见 [maybeReenterReading]）。
     */
    private var pendingReadingReenterUrl: String? = null

    /**
     * 阅读页就绪时刻（[verifyReaderBoot] 成功时置位，[openReaderPage] 重新载入时清零）。
     * 看门狗的「阅读页丢失」判定以此做静默窗口，避免把刚发起的外壳载入误判成丢失。
     */
    private var readerSettledAt = 0L

    /**
     * 退出阅读模式后待补写的章节轨迹。
     *
     * 浏览内核的「历史记录」是把**标题**与 **URL** 两个独立事件流组合出来的
     * （`combine(tab.titleChanges(), tab.urlChanges())`）：退出阅读模式发起导航时，新章节的标题
     * 会先于 URL 上报，于是「进入时那一章」的历史行被写成新章节的标题
     * （实测：第 39 章的行被写成第 59 章）。导航落地后按阅读顺序把轨迹重写一遍即可纠正——
     * 写入时间随阅读顺序递增，主页历史列表的顺序（最后读到的在最上）不受影响。
     */
    private var pendingTrailReassert: List<ReadingPage>? = null

    /** 补写轨迹触发条件：当前页 URL 必须已经是这一章（否则页面还没导航过去）。 */
    private var pendingTrailTargetUrl: String? = null

    /** 退出阅读模式的时刻（用于避开导航刚开始那一瞬的 URL / 进度错位）。 */
    private var readingTrailExitAt = 0L

    /** 补写定时器：延时兜底那一遍（页面加载慢/中断，或站点很晚才设标题时用）。 */
    private val readingTrailHandler = Handler(Looper.getMainLooper())

    /** 拼页进展里的一章（章节 URL + 章节标题）。 */
    private data class ReadingPage(val url: String, val title: String)

    /** 已进入阅读模式的标签页：tabId(Int) -> 会话（页面导航后自动失配清理） */
    private val readingModeTabs = HashMap<Int, ReadingSession>()

    /** 已挂载阅读模式 JS 桥的 WebView（WebView 池化复用，同一实例只挂一次） */
    private val readingBridgeHosts = WeakHashMap<WebView, Boolean>()

    /** 最近一条阅读模式提示（见 [showReadingToast]） */
    private var readingToast: Toast? = null

    /**
     * Activity 是否在前台（[onResume] 置 true，[onPause] 置 false）。
     *
     * 阅读看门狗（[sampleReadingState] / [probeReaderFrame]）只看前台：后台不出帧是正常现象，
     * 不能算「合成卡住」，否则挂机键回桌面再进来会被误判成故障并触发错误恢复
     * （详见 [recoverBrokenReader]）。
     *
     * 注意：真机上挂机键（ENDCALL）会直接**熄屏**而 Activity 仍然是 resumed（实测
     * `dumpsys power` = Asleep 但 ResumedActivity 仍是本 Activity，onPause 不触发），
     * 所以光靠 onResume/onPause 不够，还要配合 [isScreenOn]。
     */
    private var isInForeground = false

    /** PowerManager（只用 [PowerManager.isInteractive] 判断屏幕是否点亮，见 [isScreenOn]） */
    private val powerManager: PowerManager? by lazy {
        try {
            getSystemService(Context.POWER_SERVICE) as? PowerManager
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "获取 PowerManager 失败", t)
            null
        }
    }

    /** 屏幕是否点亮（取不到时保守当作点亮，避免看门狗被静默关掉） */
    private fun isScreenOn(): Boolean = try {
        powerManager?.isInteractive ?: true
    } catch (t: Throwable) {
        KeydroidxLog.e(TAG, "读取屏幕状态失败", t)
        true
    }

    /**
     * 阅读模式提示。Toast 是排队显示的：连按 1 / 3 / 7 时屏幕上会依次补播历史提示，
     * 出现"实际已到 40px、提示还停在 32"的错觉，故先取消上一条再弹新的。
     */
    private fun showReadingToast(message: CharSequence) {
        readingToast?.cancel()
        readingToast = Toast.makeText(this, message, Toast.LENGTH_SHORT).also { it.show() }
    }

    /** 当前标签页是否处于阅读模式 */
    private fun isReadingMode(): Boolean {
        val tabId = selectedTabId() ?: return false
        return readingModeTabs.containsKey(tabId)
    }

    /** 当前选中标签页的 id（无标签时为空） */
    private fun selectedTabId(): Int? = presenter.state.value.tabs.firstOrNull { it.isSelected }?.id

    /** 统一计算返回键回调开关：任一模式 / 菜单激活时都要先由 Activity 消费返回键 */
    private fun updateBackPressedEnabled() {
        backPressedCallback.isEnabled = activeMenuType != QuickMenuType.NONE ||
            isFindMode ||
            isFreeCopyMode ||
            isBookmarkEditMode ||
            isReadingMode()
    }

    /** 按键 * 菜单 6：进入 / 退出阅读模式 */
    private fun toggleReadingMode() {
        KeydroidxLog.d(
            TAG,
            "阅读模式切换: from=${if (isReadingMode()) "阅读态" else "普通态"} ${describeReadingTab()}"
        )
        if (isReadingMode()) {
            exitReadingMode()
        } else {
            enterReadingMode()
        }
    }

    /**
     * 进入阅读模式（渲染隔离版，方案 C；背景见 docs/reading-mode-render-isolation-plan.md）。
     *
     * 三步：
     * 1. 在**源页面上**只装 API、做一次只读提取（`extract()` 只读 DOM：不清 body、不动
     *    viewport、不挂监听），拿到当前章正文 + 下一章 URL + 章节标题；
     * 2. 把 WebView 导航到一个干净外壳：`loadDataWithBaseURL(章节URL, 外壳, …)`。源页面连同
     *    它注册的 setInterval / rAF / scroll 监听 / 埋点一起随导航消亡 —— 「越拼越大的 DOM 上
     *    循环 reflow 把渲染进程烧死」的根被从结构上拔掉（这正是空屏 15 分钟的成因）；
     * 3. 外壳里内联执行阅读脚本并用第 1 步的正文直接渲染（`enterWithContent`），后续拼章仍是
     *    同源 fetch（baseUrl 决定 document.origin，origin / cookie / UA 与源页面完全一致）。
     *
     * 为什么 baseUrl 必须是章节 URL：它决定文档 origin。origin 一致，脚本里的
     * `fetch(next, {credentials:'include'})` 才带 cookie 且不受 CORS 限制。副作用是地址栏、
     * 标签标题、历史记录、会话恢复都自然停在真实章节 URL —— 但要注意
     * `webView.getUrl()` 在 data 页上返回 `about:blank`，所以凡需要真实 URL 的地方一律走
     * [currentRealUrl] / [ReadingSession.lastUrl]，不要问 WebView。
     */
    private fun enterReadingMode() {
        val tab = presenter.state.value.tabs.firstOrNull { it.isSelected }
        val webView = currentWebView()
        if (isHomeSelected || tab == null || webView == null) {
            Toast.makeText(this, R.string.keyphone_need_page, Toast.LENGTH_SHORT).show()
            return
        }
        val url = webView.url.orEmpty()
        if (url.isBlank() || url.isSpecialUrl()) {
            Toast.makeText(this, R.string.keyphone_need_page, Toast.LENGTH_SHORT).show()
            return
        }
        val wvId = System.identityHashCode(webView)
        val stateProgress = presenter.state.value.progress
        KeydroidxLog.i(
            "KeyphoneProgress",
            "[Activity.enterReadingMode] wv#$wvId url=$url wv.progress=${webView.progress} state.progress=$stateProgress"
        )
        if (webView.progress < 100) {
            progressBar?.startLoading(webView.progress.coerceAtLeast(12))
            Toast.makeText(this, R.string.keyphone_reading_loading, Toast.LENGTH_SHORT).show()
            return
        }
        installReadingBridge(webView)
        probeReadingBridge(webView)
        KeydroidxLog.d(TAG, "进入阅读模式: 提取源页面 url=$url tab=${tab.id} ${describeReadingTab()}")
        probeReadingVisuals(webView, "进入前")
        try {
            webView.evaluateJavascript(
                ReadingModeJs.extractScript(keyphoneReadingMode.provideJs())
            ) { rawResult ->
                val entry = ReadingEntry.fromJson(decodeJsString(rawResult))
                KeydroidxLog.d(
                    TAG,
                    "进入阅读模式提取结果: ${entry?.describe() ?: "无正文"} url=$url ${describeReadingTab()}"
                )
                if (entry == null) {
                    showReadingToast(getString(R.string.keyphone_reading_no_content))
                    return@evaluateJavascript
                }
                openReaderPage(tab.id, entry)
            }
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "进入阅读模式提取失败 url=$url", t)
            showReadingToast(getString(R.string.keyphone_reading_failed))
        }
    }

    /** 一次成功提取的结果（见 [enterReadingMode] 第 1 步）。 */
    private data class ReadingEntry(
        val url: String,
        val title: String,
        val html: String,
        val next: String,
        val textLen: Int
    ) {
        fun describe(): String = "textLen=$textLen next=${next.ifBlank { "-" }} title=$title"

        companion object {
            /** 解析 `extract()` 的 JSON；不是合法提取结果（空正文 / API 缺失 / 解析失败）时返回 null */
            fun fromJson(text: String): ReadingEntry? {
                if (text.isBlank() || text == "null") return null
                return try {
                    val obj = JSONObject(text)
                    if (!obj.optBoolean("ok")) return null
                    val html = obj.optString("html")
                    if (html.isBlank()) return null
                    ReadingEntry(
                        url = obj.optString("url"),
                        title = obj.optString("title"),
                        html = html,
                        next = obj.optString("next"),
                        textLen = obj.optInt("textLen")
                    )
                } catch (t: Throwable) {
                    KeydroidxLog.e(TAG, "阅读模式提取结果解析失败", t)
                    null
                }
            }
        }
    }

    /**
     * 打开干净阅读页（见 [enterReadingMode] 第 2/3 步）。
     *
     * 阅读脚本随外壳内联执行（脚本位于 `<body>` 末尾，执行时 body 一定已存在），避免
     * 「等 onPageFinished 再 evaluateJavascript」的竞态；正文经 `__keyphone_rm_boot__` 传入。
     */
    private fun openReaderPage(tabId: Int, entry: ReadingEntry) {
        val webView = currentWebView()
        if (webView == null) {
            KeydroidxLog.w(TAG, "打开阅读页跳过：WebView 不可用 tabId=$tabId")
            showReadingToast(getString(R.string.keyphone_reading_failed))
            return
        }
        val session = ReadingSession(entry.url).also {
            it.lastTitle = entry.title
            it.chapterCount = 1
        }
        readingModeTabs[tabId] = session
        persistReadingSession(session)
        updateBackPressedEnabled()
        // 阅读模式下隐藏鼠标光标层（方向键已改为整屏翻页）
        updateMouseCursorVisibility()
        val shell = buildReaderShell(entry)
        KeydroidxLog.d(
            TAG,
            "打开阅读页: baseUrl=${entry.url} shellChars=${shell.length} ${entry.describe()}"
        )
        try {
            // baseUrl = 章节 URL：文档 origin / cookie / 相对链接 / 地址栏全靠它
            readerSettledAt = 0L
            webView.loadDataWithBaseURL(entry.url, shell, "text/html", "utf-8", null)
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "打开阅读页失败 baseUrl=${entry.url}", t)
            readingModeTabs.remove(tabId)
            clearPersistedReadingSession()
            updateBackPressedEnabled()
            updateTabMode()
            showReadingToast(getString(R.string.keyphone_reading_failed))
            return
        }
        readerBootHandler.postDelayed({ verifyReaderBoot(tabId, entry, 0) }, READER_BOOT_CHECK_MS)
    }

    /**
     * 干净阅读页外壳：只有正文与阅读脚本，**不含任何站点脚本**。
     *
     * JSONObject 会把 `<` 后面的 `/` 转义成 `\/`，正文里即使出现 `</script>` 也不会截断脚本块。
     */
    private fun buildReaderShell(entry: ReadingEntry): String {
        val boot = JSONObject().apply {
            put("url", entry.url)
            put("title", entry.title)
            put("html", entry.html)
            put("next", entry.next)
        }.toString()
        return buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"")
            append(READER_VIEWPORT)
            append("\">")
            append("<title>").append(TextUtils.htmlEncode(entry.title)).append("</title>")
            append("</head><body><script>")
            append(readingAppearanceInitJs())
            append("window.__keyphone_rm_boot__=").append(boot).append(";\n")
            append(keyphoneReadingMode.provideJs())
            append("</script></body></html>")
        }
    }

    /**
     * 确认阅读页真的跑起来了（见 [openReaderPage]）。
     *
     * 外壳是本地页面，正常几百毫秒内 `isActive()` 就是 true。起不来时（脚本没跑 / 正文注入
     * 失败）绝不能把用户扔在一个空白 data 页上：直接导航回真实章节 URL。
     */
    private fun verifyReaderBoot(tabId: Int, entry: ReadingEntry, attempt: Int) {
        if (readingModeTabs[tabId] == null) return
        val webView = currentWebView() ?: return
        try {
            webView.evaluateJavascript(READER_JS_IS_ACTIVE) { raw ->
                val active = decodeJsString(raw) == "true"
                if (active) {
                    readerSettledAt = SystemClock.elapsedRealtime()
                    KeydroidxLog.d(TAG, "阅读页已就绪: ${entry.describe()} ${describeReadingTab()}")
                    // 首次进入弹按键使用引导；弹了就不必再叠一个进模式提示
                    if (!showReadingGuideIfNeeded()) {
                        showReadingToast(getString(R.string.keyphone_reading_enter))
                    }
                    return@evaluateJavascript
                }
                if (attempt < READER_BOOT_RETRIES) {
                    KeydroidxLog.w(TAG, "阅读页尚未就绪，重试 attempt=${attempt + 1} tabId=$tabId")
                    readerBootHandler.postDelayed(
                        { verifyReaderBoot(tabId, entry, attempt + 1) },
                        READER_BOOT_CHECK_MS
                    )
                    return@evaluateJavascript
                }
                KeydroidxLog.e(TAG, "阅读页启动失败，回到章节页: ${entry.describe()}")
                readingModeTabs.remove(tabId)
                clearPersistedReadingSession()
                updateBackPressedEnabled()
                updateTabMode()
                showReadingToast(getString(R.string.keyphone_reading_failed))
                try {
                    webView.loadUrl(entry.url)
                } catch (t: Throwable) {
                    KeydroidxLog.e(TAG, "阅读页启动失败后回到章节页也失败 url=${entry.url}", t)
                }
            }
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "阅读页就绪检查失败 tabId=$tabId", t)
        }
    }

    /**
     * 当前标签的**真实** URL。
     *
     * 阅读态下 WebView 实际停在 data 页（`getUrl()` 返回 `about:blank`），只有
     * [ReadingSession] 记着章节 URL。书签 / 分享这类要真实地址的地方都走这里。
     */
    private fun currentRealUrl(): String {
        val tabId = selectedTabId()
        val session = tabId?.let { readingModeTabs[it] }
        if (session != null) {
            return session.lastUrl.ifBlank { session.entryUrl }
        }
        return try {
            currentWebView()?.url.orEmpty()
        } catch (t: Throwable) {
            KeydroidxLog.w(TAG, "读取 WebView url 失败", t)
            ""
        }
    }

    /**
     * 阅读页丢失后的恢复（2026-10-20 两次复现的根因：阅读页是 data 页，WebView 被池回收 /
     * Activity 重建后 `restoreState` 恢复出 about:blank，随后被 [isTabHome] 当成主页，
     * 阅读态丢失 —— 用户视角就是「文字消失 → 重载 → 退出阅读」）。
     *
     * 恢复动作：清阅读态 → 导航回会话记着的最后章节（真实页面，可正常冻结/恢复）→
     * 页面加载完成后自动重进阅读模式（[maybeReenterReading]）。用户最多损失「重新提取
     * 一次正文」的时间，不再被踢出阅读。
     */
    private fun recoverReaderPage(reason: String) {
        val tabId = selectedTabId() ?: return
        val session = readingModeTabs.remove(tabId) ?: return
        readerSettledAt = 0L
        updateBackPressedEnabled()
        updateTabMode()
        val target = session.lastUrl.ifBlank { session.entryUrl }
        pendingReadingReenterUrl = target
        KeydroidxLog.e(
            TAG,
            "【阅读现场】阅读页丢失，恢复到 $target reason=$reason " +
                "已读 ${session.chapterCount} 章 last=${session.lastUrl} ${describeReadingTab()}"
        )
        val webView = currentWebView()
        if (webView == null) {
            // WebView 也没了：交给渲染进程死亡回调链路重建，重进交给下次 progress==100
            KeydroidxLog.w(TAG, "【阅读现场】恢复时 WebView 不可用，等待重建后重进 target=$target")
            return
        }
        try {
            webView.loadUrl(target)
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "【阅读现场】恢复导航失败 target=$target", t)
        }
    }

    /**
     * 恢复导航落地后自动重进阅读模式（见 [recoverReaderPage]）。
     * 只在 displayUrl 已落到目标章节且加载完成时触发一次；提取失败只弹提示，不循环。
     */
    private fun maybeReenterReading(state: BrowserViewState) {
        val target = pendingReadingReenterUrl ?: return
        // 注意：不能用 state.displayUrl 比对——它是**地址栏文案**（加载中显示 URL、加载完显示标题，
        // 见 SearchBoxModel.getDisplayContent），不是 URL。之前用它做相等判断，导致自动重进
        // 几乎从不生效（阅读页丢失后只能停在真实章节页上）。这里改用标签的真实 URL。
        val selectedUrl = state.tabs.firstOrNull { it.isSelected }?.url.orEmpty()
        if (selectedUrl != target) {
            return
        }
        // 主页状态 / 已在阅读态：保留待重进标记，等用户回到该标签页再重进，
        // 不能在这里清掉（否则恢复导航期间短暂回主页就把机会吞掉了）
        if (isReadingMode() || isHomeSelected) {
            return
        }
        // 后台 / 熄屏（挂机键）：保留待重进标记，交给回前台（[onResume]）或熄屏后的采样
        // 补判定，不在不可见时往 WebView 里载入 data 外壳
        if (!isInForeground || !isScreenOn()) {
            KeydroidxLog.d(TAG, "阅读恢复：当前不可见，保留待重进标记 target=$target")
            return
        }
        pendingReadingReenterUrl = null
        KeydroidxLog.d(TAG, "阅读恢复：自动重进阅读模式 target=$target")
        enterReadingMode()
    }

    /**
     * Activity 被系统回收后，把阅读会话从落盘快照里恢复回来（见 [KeyphoneReadingSessionStore]）。
     *
     * 背景：挂机键回桌面/系统回收会导致 Activity 重建，`readingModeTabs` 是内存状态，重建即丢；
     * 而浏览内核记录的标签 URL 只会是玩壳的 baseUrl（**入口章**），于是重进后就是
     * 「阅读模式没了，而且章节错了」——已读到第 59 章，回到第 1 章。
     *
     * 命中条件：当前选中标签的 URL 与快照的入口章 / 最后章一致（且页面已加载完）。
     * 命中后：落盘快照只恢复一次（立即清掉，避免与用户主动退出打架）→ 导航到
     * 最后读到的章节 → 由 [maybeReenterReading] 在落地后自动重进阅读模式。
     */
    private fun maybeRestoreReadingSession(state: BrowserViewState) {
        if (isReadingMode() || isHomeSelected) return
        if (readingModeTabs.isNotEmpty()) return
        val tab = state.tabs.firstOrNull { it.isSelected } ?: return
        if (isTabHome(tab)) return
        val url = tab.url.trim()
        if (url.isEmpty()) return
        val snapshot = KeyphoneReadingSessionStore.load(this, url) ?: return
        val target = snapshot.lastUrl.ifBlank { snapshot.entryUrl }
        // 先清快照：恢复动作只有一次，不能因为重复取样反复导航
        clearPersistedReadingSession()
        pendingReadingReenterUrl = target
        KeydroidxLog.i(
            TAG,
            "【阅读现场】重建后恢复阅读会话: entry=${snapshot.entryUrl} target=$target" +
                " 已读=${snapshot.chapterCount} 章 当前=$url"
        )
        if (url == target) {
            // 已经停在这一章：直接重进
            maybeReenterReading(state)
            return
        }
        val webView = currentWebView()
        if (webView == null) {
            // 没 WebView 无法导航：放弃本次恢复（避免之后莫名其妙重进）
            pendingReadingReenterUrl = null
            KeydroidxLog.w(TAG, "【阅读现场】恢复阅读会话失败：WebView 不可用 target=$target")
            return
        }
        try {
            webView.loadUrl(target)
        } catch (t: Throwable) {
            pendingReadingReenterUrl = null
            KeydroidxLog.e(TAG, "【阅读现场】恢复阅读会话导航失败 target=$target", t)
        }
    }

    /**
     * 首次进入阅读模式时弹一次按键使用引导（见 [KeyphoneReadingPrefs.guideShown]）。
     *
     * 用 SDK 的确认弹窗承载而不是 Toast：这份说明有 5 行按键清单，Toast 会自动消失且会排队，
     * 弹窗能停住等用户看完（左软键「知道了」/确认键关闭，右软键同样关闭）。
     * 返回是否真的弹了，供调用方决定要不要再弹一条进模式提示。
     */
    private fun showReadingGuideIfNeeded(): Boolean {
        if (KeyphoneReadingPrefs.guideShown(this)) {
            return false
        }
        KeyphoneReadingPrefs.setGuideShown(this)
        return try {
            KeydroidxConfirmDialog(
                this,
                getString(R.string.keyphone_reading_guide_title),
                getString(R.string.keyphone_reading_guide_message)
            )
                .setPositiveButton(getString(R.string.keyphone_reading_guide_ok)) { }
                .setNegativeButton("") { }
                .show()
            KeydroidxLog.d(TAG, "首次进入阅读模式：展示按键使用引导")
            true
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "展示阅读模式按键引导失败", t)
            false
        }
    }

    /**
     * 进入阅读模式前注入外观偏好：脚本每次注入都会回到默认主题/字号，
     * 故把上次选择的背景与字号作为 `window.__keyphone_rm_init__` 与脚本同批执行（早于 enter()）。
     */
    private fun readingAppearanceInitJs(): String {
        val theme = KeyphoneReadingPrefs.themeIndex(this)
        val font = KeyphoneReadingPrefs.fontSize(this)
        return "window.__keyphone_rm_init__={theme:$theme,font:$font};\n"
    }

    /** 背景主题中文名（序号与 KeyphoneReadingMode.js 内 THEMES 表一致） */
    private fun readingThemeName(index: Int): String = when (index) {
        0 -> getString(R.string.keyphone_reading_theme_cream)
        1 -> getString(R.string.keyphone_reading_theme_sepia)
        2 -> getString(R.string.keyphone_reading_theme_green)
        3 -> getString(R.string.keyphone_reading_theme_night)
        4 -> getString(R.string.keyphone_reading_theme_black)
        else -> index.toString()
    }

    /**
     * 按键 1 / 3：减小 / 增大正文字号。
     *
     * 新字号由脚本**返回值**带回（不用 JS 桥，见 [probeReadingBridge]），宿主据此落盘并提示；
     * 已到档位边界时脚本返回原值，提示数字不再变化即为"到底了"。
     */
    private fun adjustReadingFont(delta: Int) {
        val webView = currentWebView()
        if (webView == null) {
            KeydroidxLog.w(TAG, "阅读模式调整字号跳过：WebView 不可用 delta=$delta")
            return
        }
        val script = "window.__keyphone_rm__ && window.__keyphone_rm__.font(" + delta + ");"
        try {
            webView.evaluateJavascript(script) { rawResult ->
                val size = decodeJsString(rawResult).toIntOrNull()
                if (size == null) {
                    KeydroidxLog.w(TAG, "阅读模式字号回传异常: $rawResult")
                    return@evaluateJavascript
                }
                KeyphoneReadingPrefs.setFontSize(this, size)
                showReadingToast(getString(R.string.keyphone_reading_font_size, size))
            }
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "阅读模式调整字号失败 delta=$delta", t)
        }
    }

    /**
     * 按键 7：轮换阅读背景（米白 → 护眼黄 → 护眼绿 → 夜间灰 → 夜间纯黑）。
     * 新主题序号由脚本返回值带回，主题自带配套字色，正文颜色随背景一起变。
     */
    private fun cycleReadingTheme() {
        val webView = currentWebView()
        if (webView == null) {
            KeydroidxLog.w(TAG, "阅读模式切换背景跳过：WebView 不可用")
            return
        }
        try {
            webView.evaluateJavascript(READING_JS_THEME) { rawResult ->
                val index = decodeJsString(rawResult).toIntOrNull()
                if (index == null) {
                    KeydroidxLog.w(TAG, "阅读模式背景回传异常: $rawResult")
                    return@evaluateJavascript
                }
                KeyphoneReadingPrefs.setThemeIndex(this, index)
                showReadingToast(getString(R.string.keyphone_reading_theme_switch, readingThemeName(index)))
            }
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG,  "阅读模式切换背景失败", t)
        }
    }

    /**
     * 退出阅读模式。
     *
     * 退出前先把拼页进展拉回来（见 [pullReadingProgress]），然后按**普通浏览**的语义导航到
     * 最后读到的那一章——地址栏、标签标题、URL 历史与「关掉浏览器下次启动恢复」随之都停在
     * 那一章。
     *
     * 渲染隔离后这里**没有「原地还原 DOM」分支了**：WebView 停在 data 页外壳上，只有显式
     * `loadUrl` 才能回到真实页面（只读了一章时 target == entryUrl，等价于重载一次源页面）。
     */
    private fun exitReadingMode() {
        val tabId = selectedTabId()
        val webView = currentWebView()
        if (tabId == null || webView == null || !readingModeTabs.containsKey(tabId)) {
            // 状态与界面不同步时也要把返回键 / 光标层恢复正常
            updateBackPressedEnabled()
            updateTabMode()
            return
        }
        // 拉取失败（WebView 异常）时的兜底落点：会话里记着的最后章节
        val fallbackTarget = readingModeTabs[tabId]?.let { it.lastUrl.ifBlank { it.entryUrl } }.orEmpty()
        val dispatched = pullReadingProgress(tabId, webView, ReadingModeJs.progress(diag = true)) { session ->
            // 拉取是异步的：期间用户可能已经切走标签或又退了一次
            if (readingModeTabs.remove(tabId) == null) {
                return@pullReadingProgress
            }
            // 用户主动退出：落盘会话也必须清，否则下次重建 Activity 会被当成「未读完」自动重进
            clearPersistedReadingSession()
            updateBackPressedEnabled()
            updateTabMode()
            KeydroidxLog.d(
                TAG,
                "退出阅读模式: 已读 ${session.chapterCount} 章 entry=${session.entryUrl}" +
                    " last=${session.lastUrl} trail=${session.trail.size} ${describeReadingTab()}"
            )
            val target = session.lastUrl.ifBlank { session.entryUrl }
            KeydroidxLog.d(TAG, "阅读模式退出：已读 ${session.chapterCount} 章，导航到 $target")
            pendingTrailReassert = session.trail
            pendingTrailTargetUrl = target
            pendingTrailDelayed = session.trail
            readingTrailExitAt = SystemClock.elapsedRealtime()
            readingTrailHandler.postDelayed(
                readingTrailDelayedWrite,
                READING_TRAIL_REASSERT_DELAY_MS
            )
            try {
                // 走 WebView 原生导航（等价于普通浏览打开这一章）：标签 URL / 历史 / 会话快照
                // 全部由既有链路接管，不做任何"暗改状态"
                KeydroidxLog.d(TAG, "退出阅读模式导航: -> $target")
                webView.loadUrl(target)
            } catch (t: Throwable) {
                KeydroidxLog.e(TAG, "阅读模式退出导航失败: $target", t)
                webView.evaluateJavascript(READING_JS_EXIT, null)
            }
            showReadingToast(
                getString(R.string.keyphone_reading_exit_chapters, session.chapterCount)
            )
        }
        if (!dispatched) {
            // 拉取都没能发出去（WebView 异常）：不能停在 data 页上，直接导航回会话里记着的章节
            readingModeTabs.remove(tabId)
            clearPersistedReadingSession()
            updateBackPressedEnabled()
            updateTabMode()
            if (fallbackTarget.isNotBlank()) {
                KeydroidxLog.w(TAG, "退出阅读模式：进展拉取失败，兜底导航到 $fallbackTarget")
                try {
                    webView.loadUrl(fallbackTarget)
                } catch (t: Throwable) {
                    KeydroidxLog.e(TAG, "退出阅读模式兜底导航失败: $fallbackTarget", t)
                }
            } else {
                KeydroidxLog.w(TAG, "退出阅读模式：无兜底落点，仅清理阅读态")
            }
            showReadingToast(getString(R.string.keyphone_reading_exit))
        }
    }

    /**
     * 页面即将被导航 / 刷新替换：只清 Kotlin 侧阅读状态。
     * 新页面 DOM 会覆盖旧内容，JS 侧备份随之失效，不能再调用 exit()。
     */
    private fun clearReadingModeOnNavigation() {
        if (readingModeTabs.isEmpty()) return
        val tabId = selectedTabId() ?: return
        if (readingModeTabs.remove(tabId) != null) {
            KeydroidxLog.w(
                TAG,
                "阅读态被导航清掉（页面开始加载）: tabId=$tabId ${describeReadingTab()}"
            )
            // 页面已被真实导航替换，落盘会话一并作废（否则重建后会自动重进）
            clearPersistedReadingSession()
            updateBackPressedEnabled()
        }
    }

    /**
     * 把 JS 侧已拼好的章节列表拉回 Kotlin（js 桥收不到事件，只能"宿主拉 + 脚本返回值"）。
     *
     * 拼页是异步的，脚本返回值即**此刻**的进度；拉到的新章节逐条写进 URL 历史，
     * 于是"回到主页看历史记录"能看到读过的每一章（第 39~59 章）。
     * 拉取点：每次翻页按键、退出阅读模式。
     */
    private fun pullReadingProgress(
        tabId: Int,
        webView: WebView,
        script: String = ReadingModeJs.progress(diag = false),
        onSynced: ((ReadingSession) -> Unit)? = null
    ): Boolean {
        if (!readingModeTabs.containsKey(tabId)) {
            return false
        }
        return try {
            webView.evaluateJavascript(script) { rawResult ->
                val session = readingModeTabs[tabId] ?: return@evaluateJavascript
                val (progressText, diag, style) = ReadingModeJs.split(decodeJsString(rawResult))
                if (diag.isNotEmpty()) {
                    KeydroidxLog.d(TAG, "阅读 JS 现场: $diag")
                }
                if (style.isNotEmpty()) {
                    KeydroidxLog.d(TAG, "阅读视觉现场: $style")
                }
                if (onSynced == null && progressText.isBlank() && diag.isBlank()) {
                    // 阅读页已经被替换（WebView 被回收/重建后恢复成真实章节页或 about:blank）：
                    // JS 侧的 __keyphone_rm__ 随页面一起消失。此时不能只是清掉阅读态了事——
                    // 那正是「文字消失 → 被踢出阅读」的故障链。这里恢复到最后读的章节并自动重进。
                    recoverReaderPage("按键拉取发现 __keyphone_rm__ 已消失 tabId=$tabId")
                    return@evaluateJavascript
                }
                val pages = parseReadingProgress(progressText)
                if (pages.isNotEmpty()) {
                    session.trail = pages
                }
                for (page in pages) {
                    session.lastUrl = page.url
                    session.lastTitle = page.title
                    if (session.recorded.add(page.url)) {
                        KeydroidxLog.d(TAG, "阅读模式章节入库: ${page.title} <- ${page.url}")
                        lifecycleScope.launch {
                            historyRepository.visitHistoryEntry(
                                page.url,
                                page.title.ifBlank { page.url }
                            )
                        }
                    }
                }
                if (pages.size > session.chapterCount) {
                    session.chapterCount = pages.size
                }
                persistReadingSession(session)
                onSynced?.invoke(session)
            }
            true
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "阅读模式进展同步失败 tabId=$tabId", t)
            false
        }
    }

    /** 延时兜底那一遍（新章节页面还没结束、或站点很晚才设标题时用）。 */
    private var pendingTrailDelayed: List<ReadingPage>? = null

    private val readingTrailDelayedWrite = Runnable {
        val trail = pendingTrailDelayed
        pendingTrailDelayed = null
        if (trail != null) {
            writeTrailHistory(trail, "延时兜底")
        }
    }

    /**
     * 按阅读顺序把章节轨迹写进 URL 历史。
     *
     * 同一条协程里顺序执行，写入时间戳随阅读顺序递增，主页历史列表的顺序
     * （最后读到的章节在最上）不会被补写打乱。
     */
    private fun writeTrailHistory(trail: List<ReadingPage>, reason: String) {
        if (trail.isEmpty()) return
        KeydroidxLog.d(TAG, "阅读模式补写历史（$reason）: ${trail.size} 章")
        lifecycleScope.launch {
            for (page in trail) {
                historyRepository.visitHistoryEntry(page.url, page.title.ifBlank { page.url })
            }
        }
    }

    /**
     * 退出阅读模式后的主补写：目标章节这一页已结束（进度 100）且确实停在那一章时，
     * 把整条轨迹覆盖一遍（原因见 [pendingTrailReassert]）。
     */
    private fun reassertTrailHistory() {
        val trail = pendingTrailReassert ?: return
        val target = pendingTrailTargetUrl ?: return
        val webView = currentWebView() ?: return
        if (webView.url != target || webView.progress != 100) return
        // 刚发起导航的那一瞬，WebView 可能已报出新 URL 而进度还是上一页的 100，
        // 用最小间隔把这个窗口兜开，保证补写在浏览内核的错配写入之后
        if (SystemClock.elapsedRealtime() - readingTrailExitAt < READING_TRAIL_MIN_GAP_MS) return
        pendingTrailReassert = null
        pendingTrailTargetUrl = null
        writeTrailHistory(trail, "页面结束")
    }

    /**
     * 解析脚本回传的进展文本：每行一章，`章节URL \t 章节标题`（标题可缺省）。
     * 非 http 行直接丢弃（未进入阅读态时脚本会返回 "null" 这类字面量）。
     */
    private fun parseReadingProgress(text: String): List<ReadingPage> {
        if (text.isBlank()) return emptyList()
        return text.split('\n').mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val sep = trimmed.indexOf('\t')
            val url = if (sep < 0) trimmed else trimmed.substring(0, sep)
            if (!url.startsWith("http")) return@mapNotNull null
            val title = if (sep < 0) "" else trimmed.substring(sep + 1).trim()
            ReadingPage(url, title)
        }
    }

    /** 挂载阅读模式 JS 桥（仅暴露 onEvent 一个方法，避免扩大 JS 可调用面） */
    private fun installReadingBridge(webView: WebView) {
        if (readingBridgeHosts.containsKey(webView)) return
        try {
            webView.addJavascriptInterface(KeyphoneReadingBridge(this), READING_BRIDGE_NAME)
            readingBridgeHosts[webView] = true
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "挂载阅读模式 JS 桥失败", t)
        }
    }

    /**
     * 诊断：读回页面里桥对象的可见性。
     *
     * 实测本机 WebView 对**已加载完成**的页面 `addJavascriptInterface` 不生效
     * （`window.__keyphoneReadingBridge__` 始终 undefined，事件全部静默丢失）。
     * 因此阅读模式的字号 / 背景改为「脚本回传 + evaluateJavascript 回调」，不依赖桥；
     * 这里保留一行日志，便于以后换设备/换 WebView 时定位桥是否可用。
     */
    private fun probeReadingBridge(webView: WebView) {
        try {
            webView.evaluateJavascript(
                "(function(){var b=window['" + READING_BRIDGE_NAME + "'];" +
                    "return b?typeof b.onEvent:'missing';})()"
            ) { rawResult -> KeydroidxLog.d(TAG, "阅读模式 JS 桥探测: ${decodeJsString(rawResult)}") }
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "阅读模式 JS 桥探测失败", t)
        }
    }

    /**
     * 视觉现场预检：进阅读模式（或怀疑出问题时）把主题 / 视口 / 阅读器几何打一次日志。
     *
     * 用户反馈「看着看着文字全部消失、只剩背景色」，而这条路原来只有拼页与滚动日志，
     * 与「字看不见」直接相关的主题、滚动横向偏移、视口尺寸、阅读器节点几何一条都没有；
     * 于是同样的问题复现后，日志里看不出到底是「内容没了」还是「内容被滚出了视口」还是
     * 「主题切成了深底灰字」。这里补上，且只读不改页面。
     *
     * 注意：本方法在进阅读模式之前也会调用，那时页面里还没有 `__keyphone_rm__`，
     * 打出来的是普通页面的状态（`theme=-1` 属正常）。
     */
    private fun probeReadingVisuals(webView: WebView, reason: String) {
        try {
            webView.evaluateJavascript(ReadingModeJs.styleProbe()) { rawResult ->
                KeydroidxLog.d(TAG, "阅读视觉预检（$reason）: ${decodeJsString(rawResult)}")
            }
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG,  "阅读视觉预检失败 reason=$reason", t)
        }
    }

    /** 阅读模式 JS 事件回调（已在主线程） */
    internal fun onReadingBridgeEvent(type: String, payload: String) {
        KeydroidxLog.d(TAG, "阅读桥事件: $type ${payload.take(120)}")
        when (type) {
            "enter" -> KeydroidxLog.d(TAG, "阅读模式已进入，正文 ${payload} 字")
            "append" -> {
                val page = payload.toIntOrNull() ?: 0
                KeydroidxLog.d(TAG, "阅读模式已拼接第 $page 页")
            }
            "end" -> KeydroidxLog.d(TAG, "阅读模式已到最后一页")
            "fail" -> KeydroidxLog.w(TAG, "阅读模式下一页加载失败: $payload")
            "link" -> {
                // 阅读模式不参与跳转：正文里的残留链接（书名 / 作者 / 目录）被点击时
                // 既不退出阅读态也不导航，避免误点后方向键回落到鼠标模式而"完全没反应"
                KeydroidxLog.d(TAG, "阅读模式忽略正文链接点击: $payload")
            }
            "font", "theme" ->
                // 字号 / 背景不走桥（见 adjustReadingFont）：桥事件在部分 WebView 上收不到，
                // 真收到说明该设备桥可用，此时忽略避免与脚本返回值重复提示
                KeydroidxLog.d(TAG, "阅读模式忽略桥上的外观事件: type=$type payload=$payload")
            "exit" -> KeydroidxLog.d(TAG, "阅读模式已退出")
            else -> KeydroidxLog.w(TAG, "未知的阅读模式事件: $type")
        }
    }

    /** evaluateJavascript 回调返回的是 JSON 字符串字面量（如 "\"ok\""），解成原始文本 */
    private fun decodeJsString(raw: String?): String {
        return try {
            JSONTokener(raw ?: "").nextValue() as? String ?: raw.orEmpty()
        } catch (t: Throwable) {
            KeydroidxLog.w(TAG, "阅读模式 JS 返回值解析失败: $raw")
            raw.orEmpty()
        }
    }

    /**
     * 阅读模式按键路由：
     * - 上/下方向键、数字键 2 / 8：整屏翻页（长按连翻）
     * - 数字键 1 / 3：减小 / 增大正文字号
     * - 数字键 7：轮换背景主题（米白 → 护眼黄 → 护眼绿 → 夜间灰 → 夜间纯黑）
     * - 左右方向键、确认键、其余数字键：直接消费（阅读模式下光标层已隐藏，热区跳转与"所见即点"无意义）
     *
     * 消费 DOWN 必须消费同键 UP/REPEAT，因此这里不写 [lastConsumedDownKeyCode]（与标定模式同做法）；
     * 而 1 / 3 / 7 属执行类动作，长按连发会一路冲到底，故过滤 repeat 只在首次 DOWN 生效。
     * 返回 false 的按键继续走既有分发链，保证 `*` / `#` 菜单、左右软键与返回键不受影响。
     */
    private fun handleReadingModeKeys(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val isUpKey = keyCode == KeyEvent.KEYCODE_DPAD_UP
        val isDownKey = keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        val isHorizontalKey = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        val isConfirmKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_5 ||
            keyCode == KeyEvent.KEYCODE_BUTTON_A
        val digit = keyDigitOf(keyCode)
        // 数字键 2 / 8 也可翻页：阅读模式没有光标，2 上翻、8 下翻比方向键更顺手
        val isDigitScroll = digit == 2 || digit == 8
        // 1 减小字号、3 增大字号、7 轮换背景
        val isFontKey = digit == 1 || digit == 3
        val isThemeKey = digit == 7
        val isScrollKey = isUpKey || isDownKey || isDigitScroll
        if (!isScrollKey && !isHorizontalKey && !isConfirmKey && digit !in 1..9) {
            // 阅读态下没被本函数消费的按键（软键 / 返回 / * # 菜单）会继续走分发链：
            // 出问题时能据此判断「按键到底进了哪条分支」
            KeydroidxLog.d(TAG, "阅读模式放行按键 keyCode=$keyCode repeat=${event.repeatCount}")
            return false
        }
        // 同键 UP/REPEAT 一律消费，避免穿透到 WebView 触发光标触摸或页面默认行为
        if (event.action != KeyEvent.ACTION_DOWN) {
            return true
        }
        if (isScrollKey) {
            val webView = currentWebView()
            if (webView == null) {
                KeydroidxLog.w(TAG, "阅读模式翻页跳过：WebView 不可用 keyCode=$keyCode")
                return true
            }
            // 渲染进程繁忙时跳过：scrollY/contentHeight/scrollBy 全是同步跨进程调用，
            // 页面把渲染进程占死时会堵主线程好几秒（页面反正也滚不动）
            if (isRendererLikelyBusy()) {
                KeydroidxLog.d(TAG, "渲染进程可能繁忙，阅读模式翻页跳过（同步读取会阻塞主线程）")
                return true
            }
            val tabId = selectedTabId()
            try {
                val t0 = SystemClock.elapsedRealtime()
                val step = (webView.height * READING_SCROLL_STEP_RATIO).toInt().coerceAtLeast(1)
                val scrollUp = isUpKey || digit == 2
                val fromY = webView.scrollY
                webView.scrollBy(0, if (scrollUp) -step else step)
                val contentPx = (webView.contentHeight * webView.scale).toInt()
                val remaining = contentPx - webView.scrollY - webView.height
                val syncMs = SystemClock.elapsedRealtime() - t0
                if (syncMs >= 100) {
                    KeydroidxLog.w(TAG, "【主线程耗时】阅读模式翻页同步读取 = ${syncMs}ms")
                }
                KeydroidxLog.d(
                    TAG,
                    "阅读模式翻页 keyCode=$keyCode up=$scrollUp from=$fromY to=${webView.scrollY}" +
                        " step=$step content=$contentPx viewport=${webView.height} remain=$remaining"
                )
                // 阅读模式诊断：WebView 侧（滚动 / 内容高度）与外层壳的状态必须能对上，
                // 黑屏事故第一件事就是确认「是页面滚不下去 / 拼页停了，还是壳本身出问题」
                KeydroidxLog.d(
                    TAG,
                    "阅读翻页 wv: from=$fromY to=${webView.scrollY} content=$contentPx" +
                        " viewport=${webView.height} remain=$remaining step=$step cur=${webView.url}" +
                        " ${describeReadingTab()}"
                )
                if (tabId != null) {
                    // 连续阅读：阅读页内部按章追加文字（超出窗口才丢弃最早的章），边翻边续。
                    // 方向键滚动（WebView.scrollBy）不派发页面 scroll 事件，所以接近页尾时
                    // 主动催一次追加；JS 侧有 loading / ended / turning 守卫，重复调用无害。
                    // 首次按键顺手把章节轨迹拉回来（历史记录与退出落点都依赖它）。
                    val diag = event.repeatCount == 0
                    // 页尾预读窗口与 JS 侧 PRE_READ_SCREENS 对齐（1.5 屏）
                    val nearTail = remaining <= (webView.height * READING_PRE_READ_RATIO).toInt()
                    when {
                        !scrollUp && nearTail -> {
                            try {
                                webView.evaluateJavascript(READING_JS_APPEND, null)
                            } catch (t: Throwable) {
                                KeydroidxLog.e(TAG, "阅读模式追加下一章失败", t)
                            }
                            pullReadingProgress(tabId, webView, ReadingModeJs.progress(diag = diag))
                        }

                        event.repeatCount == 0 ->
                            pullReadingProgress(tabId, webView, ReadingModeJs.progress(diag = diag))

                        else -> Unit
                    }
                }
            } catch (t: Throwable) {
                KeydroidxLog.e(TAG, "阅读模式翻页失败 keyCode=$keyCode", t)
            }
        } else if (event.repeatCount == 0 && (isFontKey || isThemeKey)) {
            // 执行类动作过滤长按连发：字体档位 / 主题数量都很有限，连发只会一闪而过
            when {
                digit == 1 -> adjustReadingFont(-1)
                digit == 3 -> adjustReadingFont(1)
                else -> cycleReadingTheme()
            }
        }
        return true
    }

    // endregion

    // region 快捷菜单（1 键页面菜单与 # 键全局菜单）

    private var cachedMenuItems: List<KeyphoneViews.QuickMenuItem> = emptyList()

    /** 按键 * 页面/窗口上下文菜单条目（0~7、* 项） */
    private fun buildPageMenuItems(): List<KeyphoneViews.QuickMenuItem> {
        return listOf(
            KeyphoneViews.QuickMenuItem("1", getString(R.string.keyphone_qm_refresh)) { refreshCurrentPage() },
            KeyphoneViews.QuickMenuItem("2", getString(R.string.keyphone_qm_add_bookmark)) { addCurrentPageToHome() },
            KeyphoneViews.QuickMenuItem("3", getString(R.string.keyphone_qm_copy_link)) { copyCurrentPageLink() },
            KeyphoneViews.QuickMenuItem("4", getString(R.string.keyphone_qm_share)) { shareCurrentPage() },
            KeyphoneViews.QuickMenuItem("5", getString(R.string.keyphone_qm_find)) { findInCurrentPage() },
            KeyphoneViews.QuickMenuItem(
                "6",
                getString(if (isReadingMode()) R.string.keyphone_qm_exit_reading else R.string.keyphone_qm_reading)
            ) { toggleReadingMode() },
            KeyphoneViews.QuickMenuItem("7", getString(R.string.keyphone_qm_clear_tabs)) { confirmClearAllTabs() },
            KeyphoneViews.QuickMenuItem("0", getString(R.string.keyphone_qm_new_tab)) { createNewWindow() },
            KeyphoneViews.QuickMenuItem("*", getString(R.string.keyphone_qm_close_tab)) { closeCurrentTabOrExit() }
        )
    }

    /** 按键 # 全局快捷工具菜单条目（1~6、*、# 项） */
    private fun buildGlobalMenuItems(): List<KeyphoneViews.QuickMenuItem> {
        return listOf(
            KeyphoneViews.QuickMenuItem("1", getString(R.string.keyphone_pm_input_url)) { openSearchEditor() },
            KeyphoneViews.QuickMenuItem("2", getString(R.string.keyphone_pm_search)) { openSearchEditor() },
            KeyphoneViews.QuickMenuItem("3", getString(R.string.keyphone_pm_history)) { showHistoryPage() },
            KeyphoneViews.QuickMenuItem("4", getString(R.string.keyphone_pm_jump_guard)) { showJumpGuardMenu() },
            KeyphoneViews.QuickMenuItem("5", getString(R.string.keyphone_pm_scroll_top_bottom)) { toggleScrollTopBottom() },
            KeyphoneViews.QuickMenuItem("6", getString(R.string.keyphone_pm_free_copy)) { startFreeCopyMode() },
            KeyphoneViews.QuickMenuItem("7", getString(R.string.keyphone_pm_site_keys)) { showSiteKeyUrlMenu() },
            KeyphoneViews.QuickMenuItem("*", getString(R.string.keyphone_pm_toggle_images)) { toggleBlockImages() },
            KeyphoneViews.QuickMenuItem("#", getString(R.string.keyphone_pm_toggle_fullscreen)) { toggleFullScreen() }
        )
    }

    private fun getActiveMenuItems(): List<KeyphoneViews.QuickMenuItem> = cachedMenuItems

    /** # 菜单 7：弹出候选站点键面板（纯净域名/域名+一级/二级路径），选中即进入专属按键适配 */
    private fun showSiteKeyUrlMenu() {
        if (isHomeSelected || currentWebView()?.url.isNullOrBlank() ||
            KeyphoneSiteKeyStore.candidatesOf(currentWebView()?.url).isEmpty()
        ) {
            Toast.makeText(this, R.string.keyphone_need_page, Toast.LENGTH_SHORT).show()
            return
        }
        showMenu(QuickMenuType.URL_MENU)
    }

    /** # 菜单 7 的候选站点键面板：从泛到具体逐条列出，最多三级（域名+两级路径） */
    private fun buildSiteKeyUrlMenuItems(): List<KeyphoneViews.QuickMenuItem> {
        val candidates = KeyphoneSiteKeyStore.candidatesOf(currentWebView()?.url)
        return candidates.mapIndexed { index, key ->
            KeyphoneViews.QuickMenuItem(
                (index + 1).toString(),
                getString(R.string.keyphone_site_key_item, index + 1, key)
            ) { startSiteKeyCalibrate(key) }
        }
    }

    private fun showPageMenu() {
        showMenu(QuickMenuType.PAGE_MENU)
    }

    // region 安全拦截（外部协议审批 / 站内跳转拦截 / 下载审批）

    /** 拦截提示去重：同一文案 2s 内只提示一次。 */
    private var lastGuardToastKey: String? = null
    private var lastGuardToastAt: Long = 0L

    /** 拦截引擎宿主：审批卡与拦截提示在此实现（onResume 注册 / onPause 注销）。 */
    private val guardHost: KeyphoneUrlGuard.Host by lazy {
        object : KeyphoneUrlGuard.Host {
            override fun requestSchemeApproval(
                sourceUrl: String?,
                targetUrl: String,
                mergedCount: Int,
                onDecision: (Boolean, Boolean?, Boolean) -> Unit
            ) {
                showSchemeApprovalDialog(sourceUrl, targetUrl, mergedCount, onDecision)
            }

            override fun requestDownloadApproval(
                sourceUrl: String?,
                targetUrl: String,
                fileName: String,
                contentLength: Long,
                onDecision: (Boolean, Boolean) -> Unit
            ) {
                showDownloadApprovalDialog(sourceUrl, targetUrl, fileName, contentLength, onDecision)
            }

            override fun notifyGuard(message: String) {
                showGuardToast(message)
            }
        }
    }

    // region 网页 JS 弹窗接管（alert / confirm / prompt / beforeunload）

    /** 上一条网页 alert 提示的时刻：广告站会弹窗风暴，提示必须节流。 */
    private var webAlertLastAt = 0L

    /** 待用户输入的网页 prompt 回调（null 表示当前没有）。 */
    private var webPromptPending: ((String?) -> Unit)? = null

    /** 待用户输入的网页 prompt 所在输入页，用于识别「未确认即被关闭」。 */
    private var webPromptFragment: KeydroidxTextInputFragment? = null

    /**
     * 网页 JS 弹窗宿主（onResume 注册 / onPause 注销，与 [guardHost] 同生命周期）。
     *
     * 三个回调都只用**宿主自己的键控组件**承载，绝不产生系统模态窗口——这是
     * 「页面卡住时菜单 / 光标 / 标签栏仍然可用」的前提，详见 [KeyphoneJsDialogs]。
     */
    private val jsDialogHost: KeyphoneJsDialogs.Host by lazy {
        object : KeyphoneJsDialogs.Host {
            override fun showWebAlert(url: String?, message: String) {
                showWebAlertNotice(message)
            }

            override fun showWebConfirm(
                url: String?,
                message: String,
                onDecision: (Boolean) -> Unit
            ) {
                showWebConfirmCard(message, onDecision)
            }

            override fun showWebPrompt(
                url: String?,
                message: String,
                defaultValue: String,
                onResult: (String?) -> Unit
            ) {
                openWebPromptInput(message, defaultValue, onResult)
            }
        }
    }

    /**
     * 网页 alert → 非模态提示条。
     *
     * 页面 JS 已被立即放行（见 [KeyphoneJsDialogs.handleAlert]），这里只负责告知用户。
     * 广告站常连着弹十几条 alert，按固定间隔节流，避免刷屏掩盖真正的拦截提示。
     */
    private fun showWebAlertNotice(message: String) {
        val text = message.replace('\n', ' ').trim()
        KeydroidxLog.d(TAG, "网页 alert: ${text.take(WEB_ALERT_LOG_CHARS)}")
        val now = SystemClock.elapsedRealtime()
        if (now - webAlertLastAt < WEB_ALERT_TOAST_GAP_MS) return
        webAlertLastAt = now
        val shown = if (text.isEmpty()) {
            getString(R.string.keyphone_js_alert_empty)
        } else {
            getString(R.string.keyphone_js_alert, text.take(WEB_ALERT_MAX_CHARS))
        }
        Toast.makeText(this, shown, Toast.LENGTH_SHORT).show()
    }

    /** 网页 confirm → SDK 键控确认框（软键左 / 确认键 = 确定，软键右 / 返回 = 取消）。 */
    private fun showWebConfirmCard(message: String, onDecision: (Boolean) -> Unit) {
        val text = message.replace('\n', ' ').trim()
            .ifBlank { getString(R.string.keyphone_js_confirm_empty) }
        KeydroidxLog.d(TAG, "网页 confirm: ${text.take(WEB_ALERT_LOG_CHARS)}")
        // 只回填一次：确认走 dismiss()、取消走 cancel()、返回键又触发 onCancelListener，
        // 三者可能叠加，必须去重——同一个 JsResult 回填两次 WebView 会抛异常
        var decided = false
        val decide: (Boolean) -> Unit = { allow ->
            if (!decided) {
                decided = true
                onDecision(allow)
            }
        }
        try {
            val dialog = KeydroidxConfirmDialog(
                this,
                getString(R.string.keyphone_js_confirm_title),
                text
            )
                .setPositiveButton(getString(R.string.keyphone_js_confirm_ok)) { decide(true) }
                .setNegativeButton(getString(R.string.keyphone_js_confirm_cancel)) { decide(false) }
            // SDK 弹窗只把软键右映射为取消，返回键走 Dialog 默认 dismiss、不触发 negativeButton；
            // 不补这一手，页面那次 confirm() 会永久悬挂
            dialog.setOnCancelListener { decide(false) }
            dialog.show()
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "展示网页 confirm 框失败", t)
            decide(false)
        }
    }

    /** 网页 prompt → SDK 键控输入页（预填网页给的默认值），确认回填文本、取消回填 null。 */
    private fun openWebPromptInput(
        message: String,
        defaultValue: String,
        onResult: (String?) -> Unit
    ) {
        if (webPromptPending != null) {
            // 同一时刻只允许一个输入请求，避免页面（或广告脚本）连续弹输入页劫持按键
            KeydroidxLog.d(TAG, "网页 prompt 已有待输入项，本次直接取消")
            onResult(null)
            return
        }
        val title = message.replace('\n', ' ').trim()
            .ifBlank { getString(R.string.keyphone_js_prompt_title) }
        val fragment = KeydroidxTextInputFragment.newInstance(
            title,
            defaultValue.ifBlank { null },
            getString(R.string.keyphone_js_prompt_hint),
            false,
            WEB_PROMPT_MAX_CHARS
        ).setRequired(false)
        webPromptPending = onResult
        webPromptFragment = fragment
        fragment.setOnConfirmListener { text ->
            // SDK 输入页会 trim；空串对网页 prompt 是合法的「确认空值」，不能当取消
            finishWebPrompt(text ?: "")
        }
        KeydroidxLog.d(TAG, "网页 prompt: 打开输入页 title=$title")
        openFragment(fragment)
    }

    /** 结束一次网页 prompt：回填结果并清状态（[text] 为 null 表示取消）。 */
    private fun finishWebPrompt(text: String?) {
        val callback = webPromptPending ?: return
        webPromptPending = null
        webPromptFragment = null
        try {
            callback(text)
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "回填网页 prompt 结果失败", t)
        }
    }

    /**
     * 网页 prompt 的兜底：SDK 输入页没有取消回调（BACK / 空内容软键右直接出栈），
     * 用 Fragment 生命周期识别「未确认即被关闭」并回填 null，否则那次 `prompt()` 会永久悬挂。
     * 确认路径不受影响：输入页先回调 `onConfirmListener` 再出栈。
     */
    private fun registerWebPromptLifecycle() {
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
                    if (f === webPromptFragment) {
                        KeydroidxLog.d(TAG, "网页 prompt 输入页未确认即关闭，回填 null")
                        finishWebPrompt(null)
                    }
                }
            },
            false
        )
    }

    // endregion

    /** # 菜单 4：站内跳转拦截（需停留在网页态且有可归一的域名）。 */
    private fun showJumpGuardMenu() {
        if (isHomeSelected || KeyphoneGuardStore.baseDomain(currentWebView()?.url) == null) {
            Toast.makeText(this, R.string.keyphone_need_page, Toast.LENGTH_SHORT).show()
            return
        }
        showMenu(QuickMenuType.JUMP_MENU)
    }

    /** # 菜单 4 级联：开 / 关（仅对当前域名生效），并展示当前域名与状态。 */
    private fun buildJumpGuardMenuItems(): List<KeyphoneViews.QuickMenuItem> {
        val domain = KeyphoneGuardStore.baseDomain(currentWebView()?.url) ?: return emptyList()
        val enabled = KeyphoneGuardStore.isJumpEnabled(this, domain)
        return listOf(
            KeyphoneViews.QuickMenuItem(
                "1",
                getString(R.string.keyphone_jump_enable) + if (enabled) "（已开启）" else ""
            ) { setJumpGuardEnabled(domain, true) },
            KeyphoneViews.QuickMenuItem(
                "2",
                getString(R.string.keyphone_jump_disable) + if (!enabled) "（已关闭）" else ""
            ) { setJumpGuardEnabled(domain, false) },
            KeyphoneViews.QuickMenuItem(
                "3",
                getString(R.string.keyphone_jump_current_domain, domain)
            ) { }
        )
    }

    /** 站内跳转拦截开关按域名持久化，开关即时生效。 */
    private fun setJumpGuardEnabled(domain: String, enabled: Boolean) {
        KeyphoneGuardStore.setJumpEnabled(this, domain, enabled)
        Toast.makeText(
            this,
            getString(
                if (enabled) R.string.keyphone_jump_enabled_toast else R.string.keyphone_jump_disabled_toast,
                domain
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 外部协议审批卡：默认焦点在「永久忽略」，RSK=本次拒绝。 */
    private fun showSchemeApprovalDialog(
        sourceUrl: String?,
        targetUrl: String,
        mergedCount: Int,
        onDecision: (Boolean, Boolean?, Boolean) -> Unit
    ) {
        val scheme = targetUrl.substringBefore(':', "")
        val sourceDomain = KeyphoneGuardStore.baseDomain(sourceUrl) ?: "-"
        val body = buildList {
            add(getString(R.string.keyphone_scheme_body_source, sourceDomain))
            add(targetUrl)
            if (mergedCount > 1) {
                add(getString(R.string.keyphone_scheme_merged, mergedCount))
            }
        }
        KeyphoneOptionSheetDialog(
            context = this,
            titleText = getString(R.string.keyphone_scheme_approval_title),
            bodyLines = body,
            options = listOf(
                KeyphoneViews.QuickMenuItem("1", getString(R.string.keyphone_scheme_allow_once)) {
                    onDecision(true, null, false)
                },
                KeyphoneViews.QuickMenuItem(
                    "2",
                    getString(R.string.keyphone_scheme_allow_forever, scheme)
                ) {
                    onDecision(true, true, false)
                },
                KeyphoneViews.QuickMenuItem(
                    "3",
                    getString(R.string.keyphone_scheme_deny_forever, scheme)
                ) {
                    onDecision(false, null, true)
                },
                KeyphoneViews.QuickMenuItem("4", getString(R.string.keyphone_scheme_deny_once)) {
                    onDecision(false, null, false)
                }
            ),
            defaultFocusIndex = 2,
            onSelected = { },
            onCancelled = { onDecision(false, null, false) }
        ).show()
    }

    /** 下载审批卡：所有下载一律先确认（凡下载必审批）。 */
    private fun showDownloadApprovalDialog(
        sourceUrl: String?,
        targetUrl: String,
        fileName: String,
        contentLength: Long,
        onDecision: (Boolean, Boolean) -> Unit
    ) {
        val sourceDomain = KeyphoneGuardStore.baseDomain(targetUrl)
            ?: KeyphoneGuardStore.baseDomain(sourceUrl) ?: "-"
        KeyphoneOptionSheetDialog(
            context = this,
            titleText = getString(R.string.keyphone_download_title),
            bodyLines = listOf(
                getString(R.string.keyphone_download_body, fileName),
                getString(R.string.keyphone_download_size, formatBytes(contentLength)),
                getString(R.string.keyphone_download_source, sourceDomain)
            ),
            options = listOf(
                KeyphoneViews.QuickMenuItem("1", getString(R.string.keyphone_download_allow)) {
                    onDecision(true, false)
                },
                KeyphoneViews.QuickMenuItem(
                    "2",
                    getString(R.string.keyphone_download_deny_forever)
                ) {
                    onDecision(false, true)
                },
                KeyphoneViews.QuickMenuItem("3", getString(R.string.keyphone_download_cancel)) {
                    onDecision(false, false)
                }
            ),
            defaultFocusIndex = 0,
            onSelected = { },
            onCancelled = { onDecision(false, false) }
        ).show()
    }

    private fun showGuardToast(message: String) {
        val now = SystemClock.elapsedRealtime()
        if (message == lastGuardToastKey && now - lastGuardToastAt < 2000) {
            return
        }
        lastGuardToastKey = message
        lastGuardToastAt = now
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0L -> getString(R.string.keyphone_size_unknown)
        bytes < 1024L -> "${bytes}B"
        bytes < 1024L * 1024L -> String.format("%.1fKB", bytes / 1024f)
        else -> String.format("%.1fMB", bytes / 1024f / 1024f)
    }

    // endregion



    private fun showGlobalMenu() {
        showMenu(QuickMenuType.GLOBAL_MENU)
    }

    private fun showMenu(type: QuickMenuType) {
        if (activeMenuType == type) return
        activeMenuType = type
        updateMouseCursorVisibility()
        cachedMenuItems = when (type) {
            QuickMenuType.PAGE_MENU -> buildPageMenuItems()
            QuickMenuType.GLOBAL_MENU -> buildGlobalMenuItems()
            QuickMenuType.URL_MENU -> buildSiteKeyUrlMenuItems()
            QuickMenuType.JUMP_MENU -> buildJumpGuardMenuItems()
            QuickMenuType.NONE -> emptyList()
        }
        // 菜单绘制在 shellOverlay 的独立 Surface 上（WebView 烧爆时依然即时弹出）；
        // 级联子面板（网址确认 / 跳转拦截）靠右弹出，常规菜单居中
        shellOverlay?.showMenu(
            rows = cachedMenuItems.map { KeyphoneShellOverlay.MenuRow(it.keyLabel, it.text) },
            focusIndex = 0,
            gravityRight = type == QuickMenuType.URL_MENU || type == QuickMenuType.JUMP_MENU
        )
        backPressedCallback.isEnabled = true
        quickMenuFocusIndex = 0
        if (isFullScreenMode) {
            findViewById<View>(CommonR.id.bottomPanel)?.visibility = View.VISIBLE
        }
        setSoftKeys(
            getString(R.string.keyphone_soft_select),
            null,
            getString(R.string.keyphone_soft_cancel)
        )
    }

    private fun hideQuickMenu() {
        if (activeMenuType == QuickMenuType.NONE) return
        activeMenuType = QuickMenuType.NONE
        cachedMenuItems = emptyList()
        updateBackPressedEnabled()
        shellOverlay?.hideMenu()
        if (isHomeSelected) {
            updateHomeSoftKeys()
        } else if (isFindMode) {
            updateFindSoftKeys()
        } else if (isFreeCopyMode) {
            updateFreeCopySoftKeys()
        } else {
            updateWebSoftKeys()
        }
        if (isFullScreenMode) {
            findViewById<View>(CommonR.id.bottomPanel)?.visibility = View.GONE
        }
        updateMouseCursorVisibility()
    }

    private fun closeCurrentTabOrExit() {
        if (isFindMode) {
            exitFindMode()
        }
        if (isFreeCopyMode) {
            exitFreeCopyMode()
        }
        val tabs = presenter.state.value.tabs
        var currentIndex = tabs.indexOfFirst { it.isSelected }
        if (currentIndex < 0 && tabs.isNotEmpty()) {
            currentIndex = tabs.indexOfFirst { !isTabHome(it) }.takeIf { it >= 0 } ?: 0
        }
        KeydroidxLog.i(TAG, "closeCurrentTabOrExit: tabsCount=${tabs.size}, currentIndex=$currentIndex")

        if (tabs.size > 1 && currentIndex >= 0) {
            closeTab(currentIndex)
        } else if (currentIndex >= 0 && !isTabHome(tabs[currentIndex])) {
            // 最后一个网页窗口：closeTab 关空后已自动新建主页窗口（退出浏览器走选项页/长按返回）
            closeTab(currentIndex)
        } else {
            // 已在主页窗口：刷新主页数据，不再弹退出确认
            switchToHome()
        }
    }

    private fun refreshCurrentPage() {
        if (isHomeSelected) {
            loadHomeData()
        } else if (isReadingMode()) {
            // 阅读页是 data 外壳：刷新它只会重放外壳（已拼的章节全丢，且仍停在 data 页上）。
            // 按普通浏览语义处理 —— 退出阅读模式并导航回真实章节页。
            KeydroidxLog.d(TAG, "阅读态刷新：改为退出阅读模式并回到真实章节页")
            exitReadingMode()
        } else {
            clearReadingModeOnNavigation()
            val realProgress = currentWebView()?.progress ?: presenter.state.value.progress
            KeydroidxLog.i(
                "KeyphoneProgress",
                "[Activity.refreshCurrentPage] realProgress=$realProgress state.progress=${presenter.state.value.progress}"
            )
            if (realProgress < 100) {
                // 正在加载时点刷新等价于「停止」，不能把进度条重新拉回起步位
                presenter.onEvent(BrowserUiEvent.RefreshOrStopClick)
            } else {
                progressBar?.startLoading(12)
                presenter.onEvent(BrowserUiEvent.RefreshOrStopClick)
            }
        }
    }

    private fun copyCurrentPageLink() {
        if (isHomeSelected) {
            Toast.makeText(this, R.string.keyphone_need_page, Toast.LENGTH_SHORT).show()
        } else {
            menuClick(MenuSelection.COPY_LINK)
        }
    }

    private fun shareCurrentPage() {
        if (isHomeSelected) {
            Toast.makeText(this, R.string.keyphone_need_page, Toast.LENGTH_SHORT).show()
        } else {
            menuClick(MenuSelection.SHARE)
        }
    }

    private fun findInCurrentPage() {
        if (isHomeSelected) {
            Toast.makeText(this, R.string.keyphone_need_page, Toast.LENGTH_SHORT).show()
            return
        }
        val fragment = KeydroidxTextInputFragment.newInstance(
            getString(R.string.keyphone_find_title),
            currentFindQuery.ifBlank { null },
            getString(R.string.keyphone_find_hint),
            false,
            MAX_INPUT_CHARS
        )
        fragment.setOnConfirmListener { text ->
            if (!text.isNullOrBlank()) {
                startFindInPage(text.trim())
            }
        }
        openFragment(fragment)
    }

    private fun startFindInPage(query: String) {
        if (isHomeSelected || query.isBlank()) return
        val webView = currentWebView()
        if (webView == null) {
            Toast.makeText(this, R.string.keyphone_need_page, Toast.LENGTH_SHORT).show()
            return
        }

        isFindMode = true
        currentFindQuery = query
        backPressedCallback.isEnabled = true
        updateMouseCursorVisibility()

        addressBarContainer?.visibility = View.GONE
        findBarContainer?.visibility = View.VISIBLE
        findQueryText?.text = "\"$query\""
        findCountText?.text = "..."
        updateFindSoftKeys()

        webView.setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
            runOnUiThread {
                if (!isFindMode) return@runOnUiThread
                if (numberOfMatches == 0) {
                    findCountText?.text = getString(R.string.keyphone_find_no_matches)
                } else {
                    findCountText?.text = getString(
                        R.string.keyphone_find_format,
                        activeMatchOrdinal + 1,
                        numberOfMatches
                    )
                }
            }
        }

        presenter.onEvent(BrowserUiEvent.FindInPage(query))
    }

    private fun findNextMatch() {
        if (!isFindMode) return
        presenter.onEvent(BrowserUiEvent.FindInPageNext)
    }

    private fun findPreviousMatch() {
        if (!isFindMode) return
        presenter.onEvent(BrowserUiEvent.FindInPagePrevious)
    }

    private fun exitFindMode() {
        if (!isFindMode) return
        isFindMode = false
        updateBackPressedEnabled()

        findBarContainer?.visibility = View.GONE
        val webView = currentWebView()
        webView?.clearMatches()
        webView?.setFindListener(null)
        presenter.onEvent(BrowserUiEvent.FindInPageDismissed)

        updateTabMode()
    }

    private fun updateFindSoftKeys() {
        setSoftKeys(
            getString(R.string.keyphone_find_prev),
            getString(R.string.keyphone_find_next),
            getString(R.string.keyphone_find_exit)
        )
        if (isFullScreenMode && activeMenuType == QuickMenuType.NONE) {
            findViewById<View>(CommonR.id.bottomPanel)?.visibility = View.VISIBLE
        }
    }

    // region 自由复制模式

    private fun startFreeCopyMode() {
        if (isHomeSelected) {
            Toast.makeText(this, R.string.keyphone_need_page, Toast.LENGTH_SHORT).show()
            return
        }
        val webView = currentWebView()
        if (webView == null) {
            Toast.makeText(this, R.string.keyphone_need_page, Toast.LENGTH_SHORT).show()
            return
        }

        if (isFindMode) {
            exitFindMode()
        }

        isFreeCopyMode = true
        isCopySelecting = false
        backPressedCallback.isEnabled = true
        updateMouseCursorVisibility()

        addressBarContainer?.visibility = View.GONE
        findBarContainer?.visibility = View.VISIBLE
        findQueryText?.text = getString(R.string.keyphone_copy_title)
        findCountText?.text = getString(R.string.keyphone_copy_start_hint)
        updateFreeCopySoftKeys()

        val initJs = """
            (function() {
                if (!window.__keyphone_copy__) {
                    window.__keyphone_copy__ = {
                        isSelecting: false,
                        caretEl: null,
                        init: function() {
                            // 1. 尝试解除网页对 user-select 与复制事件的拦截
                            var wasBlocked = false;
                            try {
                                var computed = window.getComputedStyle(document.body);
                                if (computed && (computed.userSelect === 'none' || computed.webkitUserSelect === 'none')) {
                                    wasBlocked = true;
                                }
                            } catch(e) {}

                            var unblockStyle = document.getElementById('__keyphone_unblock_copy__');
                            if (!unblockStyle) {
                                unblockStyle = document.createElement('style');
                                unblockStyle.id = '__keyphone_unblock_copy__';
                                unblockStyle.textContent = '* { -webkit-user-select: text !important; user-select: text !important; }';
                                document.head.appendChild(unblockStyle);
                            }

                            ['selectstart', 'copy', 'cut', 'contextmenu', 'select'].forEach(function(ev) {
                                document.addEventListener(ev, function(e) { e.stopPropagation(); }, true);
                                document['on' + ev] = null;
                                if (document.body) document.body['on' + ev] = null;
                            });

                            // 2. 检查页面是否存在可选文本节点
                            var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                            var hasText = false;
                            var firstText = null;
                            var n;
                            while ((n = walker.nextNode())) {
                                if (n.nodeValue && n.nodeValue.trim().length > 0) {
                                    hasText = true;
                                    if (!firstText) firstText = n;
                                    break;
                                }
                            }
                            if (!hasText) {
                                return JSON.stringify({ success: false, reason: "no_text" });
                            }

                            if (!this.caretEl) {
                                var el = document.createElement('div');
                                el.id = '__keyphone_caret_overlay__';
                                el.style.cssText = 'position:absolute; width:3px; background:#FF6600; border-radius:1px; z-index:2147483647; pointer-events:none; box-shadow:0 0 5px rgba(255,102,0,0.9); transition: left 0.04s linear, top 0.04s linear;';
                                document.body.appendChild(el);
                                this.caretEl = el;

                                var style = document.createElement('style');
                                style.id = '__keyphone_caret_style__';
                                style.textContent = '@keyframes __caret_blink__ { 0%,100%{opacity:1;} 50%{opacity:0.15;} } ' +
                                    '#__keyphone_caret_overlay__ { animation: __caret_blink__ 0.8s infinite; }';
                                document.head.appendChild(style);
                            }
                            this.caretEl.style.display = 'block';
                            this.isSelecting = false;

                            var sel = window.getSelection();
                            if (!sel || sel.rangeCount === 0 || (sel.isCollapsed && !sel.anchorNode)) {
                                var startY = Math.max(30, Math.min(window.innerHeight / 3, 120));
                                var range = document.caretRangeFromPoint ? document.caretRangeFromPoint(30, startY) : null;
                                if (range) {
                                    sel.removeAllRanges();
                                    sel.addRange(range);
                                } else if (firstText) {
                                    var r = document.createRange();
                                    r.setStart(firstText, 0);
                                    r.collapse(true);
                                    sel.removeAllRanges();
                                    sel.addRange(r);
                                }
                            }
                            this.updateCaret();
                            this.scrollIntoView();
                            return JSON.stringify({ success: true, unblocked: wasBlocked });
                        },
                        getCaretRect: function() {
                            var sel = window.getSelection();
                            if (!sel || sel.rangeCount === 0) return null;
                            var range = sel.getRangeAt(0).cloneRange();
                            if (this.isSelecting) {
                                range.collapse(false);
                            }

                            // 1. 尝试直接获取 ClientRects
                            var rects = range.getClientRects();
                            if (rects && rects.length > 0) {
                                for (var i = 0; i < rects.length; i++) {
                                    var r = rects[i];
                                    if (r.width > 0 || r.height > 0 || r.top > 0 || r.left > 0) {
                                        return r;
                                    }
                                }
                            }

                            // 2. 尝试 getBoundingClientRect
                            var bRect = range.getBoundingClientRect();
                            if (bRect && (bRect.width > 0 || bRect.height > 0 || bRect.top > 0 || bRect.left > 0)) {
                                return bRect;
                            }

                            // 3. 针对折叠 Range 在文本节点中的高精测距：临时扩展 1 字符测出物理像素
                            if (range.collapsed && range.startContainer && range.startContainer.nodeType === Node.TEXT_NODE) {
                                var textNode = range.startContainer;
                                var offset = range.startOffset;
                                var temp = range.cloneRange();
                                if (offset < textNode.length) {
                                    temp.setEnd(textNode, offset + 1);
                                    var tr = temp.getBoundingClientRect();
                                    if (tr && (tr.top > 0 || tr.left > 0 || tr.height > 0)) {
                                        return { left: tr.left, top: tr.top, height: tr.height || 18, width: 2 };
                                    }
                                } else if (offset > 0) {
                                    temp.setStart(textNode, offset - 1);
                                    var tr = temp.getBoundingClientRect();
                                    if (tr && (tr.top > 0 || tr.left > 0 || tr.height > 0)) {
                                        return { left: tr.right, top: tr.top, height: tr.height || 18, width: 2 };
                                    }
                                }
                            }

                            // 4. 降级方案：获取父容器元素坐标
                            var node = range.startContainer;
                            if (node && node.nodeType === Node.TEXT_NODE) {
                                node = node.parentNode;
                            }
                            if (node && node.getBoundingClientRect) {
                                var pr = node.getBoundingClientRect();
                                if (pr && (pr.top > 0 || pr.left > 0 || pr.height > 0)) {
                                    return { left: pr.left, top: pr.top, height: Math.min(pr.height || 18, 24), width: 2 };
                                }
                            }
                            return null;
                        },
                        move: function(dir) {
                            var sel = window.getSelection();
                            if (!sel) return;
                            if (sel.rangeCount === 0) {
                                this.init();
                                return;
                            }
                            var alter = this.isSelecting ? 'extend' : 'move';
                            try {
                                if (dir === 'up') {
                                    sel.modify(alter, 'backward', 'line');
                                } else if (dir === 'down') {
                                    sel.modify(alter, 'forward', 'line');
                                } else if (dir === 'left') {
                                    sel.modify(alter, 'left', 'character');
                                } else if (dir === 'right') {
                                    sel.modify(alter, 'right', 'character');
                                }
                            } catch (e) {
                                if (dir === 'left') sel.modify(alter, 'backward', 'character');
                                if (dir === 'right') sel.modify(alter, 'forward', 'character');
                            }
                            this.updateCaret();
                            this.scrollIntoView();
                        },
                        startSelect: function() {
                            this.isSelecting = true;
                            this.updateCaret();
                        },
                        resetSelect: function() {
                            this.isSelecting = false;
                            var sel = window.getSelection();
                            if (sel) {
                                sel.collapseToStart();
                            }
                            this.updateCaret();
                        },
                        getSelectedText: function() {
                            var sel = window.getSelection();
                            return sel ? sel.toString() : '';
                        },
                        updateCaret: function() {
                            if (!this.caretEl) return;
                            var rect = this.getCaretRect();
                            if (rect) {
                                var scrollX = window.pageXOffset || document.documentElement.scrollLeft || 0;
                                var scrollY = window.pageYOffset || document.documentElement.scrollTop || 0;
                                this.caretEl.style.left = (scrollX + rect.left) + 'px';
                                this.caretEl.style.top = (scrollY + rect.top) + 'px';
                                this.caretEl.style.height = Math.max(rect.height || 18, 16) + 'px';
                                this.caretEl.style.display = 'block';
                            }
                        },
                        scrollIntoView: function() {
                            var rect = this.getCaretRect();
                            if (!rect) return;
                            var margin = 50;
                            if (rect.top < margin) {
                                window.scrollBy(0, rect.top - margin);
                            } else if (rect.bottom > window.innerHeight - margin) {
                                window.scrollBy(0, rect.bottom - (window.innerHeight - margin));
                            }
                            if (rect.left < margin) {
                                window.scrollBy(rect.left - margin, 0);
                            } else if (rect.right > window.innerWidth - margin) {
                                window.scrollBy(rect.right - (window.innerWidth - margin), 0);
                            }
                        },
                        destroy: function() {
                            if (this.caretEl && this.caretEl.parentNode) {
                                this.caretEl.parentNode.removeChild(this.caretEl);
                            }
                            this.caretEl = null;
                            var style = document.getElementById('__keyphone_caret_style__');
                            if (style && style.parentNode) {
                                style.parentNode.removeChild(style);
                            }
                            var unblock = document.getElementById('__keyphone_unblock_copy__');
                            if (unblock && unblock.parentNode) {
                                unblock.parentNode.removeChild(unblock);
                            }
                            var sel = window.getSelection();
                            if (sel) {
                                sel.removeAllRanges();
                            }
                            this.isSelecting = false;
                        }
                    };
                }
                return window.__keyphone_copy__.init();
            })();
        """.trimIndent()

        webView.evaluateJavascript(initJs) { rawResult ->
            val jsonStr = try {
                org.json.JSONTokener(rawResult ?: "").nextValue() as? String ?: rawResult.orEmpty()
            } catch (e: Exception) {
                rawResult.orEmpty()
            }
            try {
                val json = org.json.JSONObject(jsonStr)
                val success = json.optBoolean("success", true)
                val unblocked = json.optBoolean("unblocked", false)
                if (!success) {
                    Toast.makeText(this@KeyphoneBrowserActivity, R.string.keyphone_copy_page_restricted, Toast.LENGTH_LONG).show()
                    exitFreeCopyMode()
                } else if (unblocked) {
                    Toast.makeText(this@KeyphoneBrowserActivity, R.string.keyphone_copy_unblocked_hint, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                // 忽略非 JSON 返回
            }
        }
    }

    private fun moveFreeCopyCaret(direction: String) {
        if (!isFreeCopyMode) return
        currentWebView()?.evaluateJavascript(
            "window.__keyphone_copy__ && window.__keyphone_copy__.move('$direction');",
            null
        )
    }

    private fun startFreeCopySelect() {
        if (!isFreeCopyMode || isCopySelecting) return
        isCopySelecting = true
        findCountText?.text = getString(R.string.keyphone_copy_select_hint)
        updateFreeCopySoftKeys()
        currentWebView()?.evaluateJavascript(
            "window.__keyphone_copy__ && window.__keyphone_copy__.startSelect();",
            null
        )
        Toast.makeText(this, R.string.keyphone_copy_select_hint, Toast.LENGTH_SHORT).show()
    }

    private fun resetFreeCopySelect() {
        if (!isFreeCopyMode || !isCopySelecting) return
        isCopySelecting = false
        findCountText?.text = getString(R.string.keyphone_copy_start_hint)
        updateFreeCopySoftKeys()
        currentWebView()?.evaluateJavascript(
            "window.__keyphone_copy__ && window.__keyphone_copy__.resetSelect();",
            null
        )
        Toast.makeText(this, R.string.keyphone_copy_start_hint, Toast.LENGTH_SHORT).show()
    }

    private fun performFreeCopy() {
        if (!isFreeCopyMode) return
        val webView = currentWebView() ?: return
        webView.evaluateJavascript(
            "window.__keyphone_copy__ ? window.__keyphone_copy__.getSelectedText() : '';"
        ) { rawResult ->
            val text = try {
                org.json.JSONTokener(rawResult ?: "").nextValue() as? String ?: ""
            } catch (e: Exception) {
                rawResult?.trim('"').orEmpty()
            }

            if (text.isBlank()) {
                Toast.makeText(this@KeyphoneBrowserActivity, R.string.keyphone_copy_empty, Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("text", text))
                Toast.makeText(this@KeyphoneBrowserActivity, R.string.keyphone_copy_success, Toast.LENGTH_SHORT).show()
                exitFreeCopyMode()
            }
        }
    }

    private fun exitFreeCopyMode() {
        if (!isFreeCopyMode) return
        isFreeCopyMode = false
        isCopySelecting = false
        updateBackPressedEnabled()

        findBarContainer?.visibility = View.GONE
        currentWebView()?.evaluateJavascript(
            "window.__keyphone_copy__ && window.__keyphone_copy__.destroy();",
            null
        )

        updateTabMode()
    }

    private fun updateFreeCopySoftKeys() {
        if (isCopySelecting) {
            setSoftKeys(
                getString(R.string.keyphone_copy_soft_reset),
                getString(R.string.keyphone_copy_soft_copy),
                getString(R.string.keyphone_copy_soft_exit)
            )
        } else {
            setSoftKeys(
                null,
                getString(R.string.keyphone_copy_soft_start),
                getString(R.string.keyphone_copy_soft_exit)
            )
        }
        if (isFullScreenMode && activeMenuType == QuickMenuType.NONE) {
            findViewById<View>(CommonR.id.bottomPanel)?.visibility = View.VISIBLE
        }
    }

    // endregion

    private fun toggleFullScreen() {
        isFullScreenMode = !isFullScreenMode
        updateTabMode()
        if (isFullScreenMode) {
            findViewById<View>(CommonR.id.bottomPanel)?.visibility = View.GONE
            Toast.makeText(this, R.string.keyphone_fullscreen_on, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.keyphone_fullscreen_off, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleBlockImages() {
        lifecycleScope.launch {
            try {
                val currentBlocked = userPreferencesDataStore.blockImagesEnabled.get()
                val newBlocked = !currentBlocked
                userPreferencesDataStore.blockImagesEnabled.set(newBlocked)
                currentWebView()?.settings?.apply {
                    // 双开关：loadsImagesAutomatically 管全部图片（含内联），blockNetworkImage 兜底网络图
                    loadsImagesAutomatically = !newBlocked
                    blockNetworkImage = newBlocked
                }
                Toast.makeText(
                    this@KeyphoneBrowserActivity,
                    if (newBlocked) R.string.keyphone_images_blocked else R.string.keyphone_images_loaded,
                    Toast.LENGTH_SHORT
                ).show()
            } catch (t: Throwable) {
                KeydroidxLog.e(TAG, "切换无图模式失败", t)
            }
        }
    }

    private fun toggleScrollTopBottom() {
        val webView = currentWebView()
        if (webView == null || isHomeSelected) {
            val scroll = homeScroll
            if (scroll != null) {
                if (isScrolledToBottom) {
                    scroll.smoothScrollTo(0, 0)
                    isScrolledToBottom = false
                    Toast.makeText(this, R.string.keyphone_scroll_top, Toast.LENGTH_SHORT).show()
                } else {
                    scroll.smoothScrollTo(0, homeRowsContainer?.height ?: 2000)
                    isScrolledToBottom = true
                    Toast.makeText(this, R.string.keyphone_scroll_bottom, Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        // 渲染进程繁忙时跳过：scrollTo/pageDown 是同步跨进程调用，会堵主线程
        if (isRendererLikelyBusy()) {
            KeydroidxLog.d(TAG, "渲染进程可能繁忙，跳过滚动到顶/底部（同步调用）")
            return
        }
        if (isScrolledToBottom) {
            webView.scrollTo(0, 0)
            isScrolledToBottom = false
            Toast.makeText(this, R.string.keyphone_scroll_top, Toast.LENGTH_SHORT).show()
        } else {
            webView.pageDown(true)
            isScrolledToBottom = true
            Toast.makeText(this, R.string.keyphone_scroll_bottom, Toast.LENGTH_SHORT).show()
        }
    }

    // endregion

    // region 主页数据与交互

    private fun loadHomeData() {
        lifecycleScope.launch {
            try {
                val newBookmarks = bookmarkRepository.getAllBookmarksSorted()
                val newHistory =
                    historyRepository.lastHundredVisitedHistoryEntries().take(MAX_HISTORY_ROWS)
                if (newBookmarks.isEmpty() && isBookmarkEditMode) {
                    exitBookmarkEditMode()
                }
                val changed = newBookmarks != bookmarks || newHistory != history
                bookmarks = newBookmarks
                history = newHistory
                if (changed || homeRowsContainer?.childCount == 0) {
                    rebuildHomeRows()
                }
            } catch (t: Throwable) {
                KeydroidxLog.e(TAG, "加载书签/历史失败", t)
            }
        }
    }

    private fun rebuildHomeRows() {
        val container = homeRowsContainer ?: return
        val helper = focusHelper ?: return
        val previousIndex = helper.focusIndex

        val children = ArrayList<View>()
        val focusItems = ArrayList<View>()

        val search = KeyphoneViews.searchRow(this, getString(R.string.keyphone_search_hint))
        search.tag = PAYLOAD_SEARCH
        children.add(search)
        focusItems.add(search)

        gridStart = -1
        gridCount = 0
        bookmarkCells = emptyList()
        if (bookmarks.isNotEmpty()) {
            children.add(KeyphoneViews.sectionLabel(this, getString(R.string.keyphone_section_bookmarks)))
            val grid = KeyphoneViews.bookmarkGrid(
                this,
                bookmarks.map { it.title.ifBlank { it.url } },
                GRID_COLUMNS
            )
            children.add(grid.container)
            gridStart = focusItems.size
            gridCount = grid.cells.size
            bookmarkCells = grid.cells
            grid.cells.forEachIndexed { index, cell ->
                cell.tag = bookmarks[index]
                cell.setEditMode(isBookmarkEditMode)
                cell.setOnLongClickListener {
                    if (!isBookmarkEditMode) {
                        enterBookmarkEditMode()
                    }
                    true
                }
                focusItems.add(cell)
            }
        }
        if (history.isNotEmpty()) {
            children.add(KeyphoneViews.sectionLabel(this, getString(R.string.keyphone_section_history)))
            history.forEach { entry ->
                val row = KeyphoneViews.iconRow(this, null, entry.title, entry.title, entry.url)
                row.tag = entry
                children.add(row)
                focusItems.add(row)
            }
        }

        container.removeAllViews()
        children.forEach { container.addView(it) }
        focusItems.forEachIndexed { index, view ->
            view.setOnClickListener {
                helper.setFocusIndex(index)
                if (isBookmarkEditMode && view.tag is Bookmark.Entry) {
                    deleteBookmark(view.tag as Bookmark.Entry)
                } else {
                    activateHomeItem(view)
                }
            }
        }

        helper.setItems(focusItems)
        if (focusItems.isNotEmpty()) {
            helper.setFocusIndex(previousIndex.coerceIn(0, focusItems.size - 1))
        }
    }

    private fun activateHomeItem(view: View?) {
        when (val payload = view?.tag) {
            PAYLOAD_SEARCH -> openSearchEditor()
            is Bookmark.Entry -> {
                if (isBookmarkEditMode) {
                    deleteBookmark(payload)
                } else {
                    openUrlInNewTab(payload.url)
                }
            }
            is HistoryEntry -> openUrlInNewTab(payload.url)
        }
    }

    private fun enterBookmarkEditMode() {
        if (isBookmarkEditMode || bookmarks.isEmpty()) return
        isBookmarkEditMode = true
        backPressedCallback.isEnabled = true
        bookmarkCells.forEach { it.setEditMode(true) }
        updateHomeSoftKeys()
    }

    private fun exitBookmarkEditMode() {
        if (!isBookmarkEditMode) return
        isBookmarkEditMode = false
        backPressedCallback.isEnabled = (activeMenuType != QuickMenuType.NONE)
        bookmarkCells.forEach { it.setEditMode(false) }
        updateHomeSoftKeys()
    }

    private fun updateHomeSoftKeys() {
        if (isBookmarkEditMode) {
            setSoftKeys(
                null,
                getString(R.string.keyphone_soft_delete),
                getString(R.string.keyphone_soft_back)
            )
        } else {
            setSoftKeys(
                getString(R.string.keyphone_soft_options),
                null,
                getString(R.string.keyphone_soft_exit)
            )
        }
        if (isFullScreenMode && activeMenuType == QuickMenuType.NONE) {
            findViewById<View>(CommonR.id.bottomPanel)?.visibility = View.GONE
        }
    }

    private fun updateWebSoftKeys() {
        setSoftKeys(
            getString(R.string.keyphone_soft_menu),
            null,
            getString(R.string.keyphone_soft_back)
        )
        if (isFullScreenMode && activeMenuType == QuickMenuType.NONE) {
            findViewById<View>(CommonR.id.bottomPanel)?.visibility = View.GONE
        }
    }

    // region 底部软键栏时间（中键空位）

    private val bottomClockFormat = SimpleDateFormat(BOTTOM_CLOCK_PATTERN, Locale.getDefault())

    /** 当前时间的文本；由 [refreshBottomClock] 维护，中键空位时显示 */
    private var bottomClockText: String = bottomClockFormat.format(Date())

    /** 最近一次软键内容：中键的「真实文案」与时间是互斥关系，必须分别记住 */
    private var lastSoftLeft: CharSequence? = null
    private var lastSoftCenter: CharSequence? = null
    private var lastSoftRight: CharSequence? = null

    private val bottomClockHandler = Handler(Looper.getMainLooper())
    private val bottomClockTick = object : Runnable {
        override fun run() {
            refreshBottomClock()
            scheduleBottomClock()
        }
    }

    /**
     * 软键栏中键在正常态是空位（左键「菜单 / 选项」、右键「返回 / 退出」），这里把空位用来显示
     * **24 小时制**时间 `HH:mm`：软键栏三栏等宽，中键正好在屏幕正中。
     *
     * 中键出现真实文案时（页内查找「下一个」、自由复制「复制」、书签编辑「删除」）文案优先，
     * 时间临时让位；文案清空后由下一次整分刷新自动补回（见 [refreshBottomClock]）。
     * 走 [KeydroidxBaseActivity.setSoftKeys] 而非直接设 TextView，是为了复用 SDK 的中键
     * 自适应字号逻辑（窄屏下自动缩小，不出现省略号）。
     */
    override fun setSoftKeys(left: CharSequence?, center: CharSequence?, right: CharSequence?) {
        lastSoftLeft = left
        lastSoftCenter = center
        lastSoftRight = right
        super.setSoftKeys(left, bottomCenterText(), right)
    }

    /** 中键当前该显示的文案：真实软键文案 > 时间 */
    private fun bottomCenterText(): CharSequence? =
        lastSoftCenter ?: bottomClockText

    /** 立即重设中键文案（繁忙状态切换 / 时间变化时用；见 [refreshBottomClock]） */
    private fun applyBottomCenter() {
        try {
            super.setSoftKeys(lastSoftLeft, bottomCenterText(), lastSoftRight)
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "刷新底部中键文案失败", t)
        }
    }

    /** 刷新中键时间（只在文本真的变化时重设，避免无谓重排） */
    private fun refreshBottomClock() {
        val text = bottomClockFormat.format(Date())
        if (text == bottomClockText) {
            return
        }
        bottomClockText = text
        // 菜单文案正占着中键，等它让位时由对应流程补回时间
        if (lastSoftCenter == null) {
            applyBottomCenter()
        }
    }

    /** 分钟粒度，无需每秒唤醒：对齐到下一个整分再刷新 */
    private fun scheduleBottomClock() {
        bottomClockHandler.removeCallbacks(bottomClockTick)
        val now = System.currentTimeMillis()
        bottomClockHandler.postDelayed(
            bottomClockTick,
            BOTTOM_CLOCK_INTERVAL_MS - now % BOTTOM_CLOCK_INTERVAL_MS + BOTTOM_CLOCK_SLACK_MS
        )
    }

    // endregion

    // region 页面无响应看门狗（保活提示 + 逃生口）

    private val pageBusyHandler = Handler(Looper.getMainLooper())
    private var pageBusy = false
    private var pagePingPending = false
    private var pagePingSentAt = 0L
    private var lastKeyDownAt = 0L

    private val pageBusyTick = object : Runnable {
        override fun run() {
            schedulePageBusyTick()
            checkPageResponsive()
        }
    }

    /**
     * 定时 ping 渲染进程：网页脚本把渲染进程占死时（例如站点一次性渲染上千个章节项），
     * `evaluateJavascript` 的回调会迟迟不回，宿主据此判定「页面繁忙」，把底部软键栏中键换成
     * 提示 —— 用户能立刻分辨是**页面**卡了还是浏览器死了。
     *
     * 全程只做提示：不弹窗口、不抢按键、不等渲染进程。宿主按键链路本来就不依赖 WebView
     * （菜单 / 光标 / 关标签都不等渲染进程），此刻 `*` 菜单照常可用，其中的
     * 「1 刷新 / * 关闭窗口」就是逃生口。
     */
    private fun schedulePageBusyTick() {
        // 彻底停用后台高频 ping，消除对 V8 管道的持续抢占
    }

    private fun checkPageResponsive() {
        // 空操作：避免并发 IPC 冲突
    }

    private fun setPageBusy(busy: Boolean) {
        if (busy == pageBusy) return
        pageBusy = busy
        KeydroidxLog.d(TAG, if (busy) "页面无响应" else "页面恢复响应")
    }

    /** 事前预判：不再随意拦截用户的输入与滚动，仅在显式标记时生效 */
    private fun isRendererLikelyBusy(): Boolean {
        return false
    }

    /** 主线程同步 WebView 调用耗时探针：任何超过 100ms 的调用都打日志，定位「单次卡死几秒」的元凶 */
    private fun traceMain(label: String, block: () -> Unit) {
        val t0 = SystemClock.elapsedRealtime()
        try {
            block()
        } finally {
            val dt = SystemClock.elapsedRealtime() - t0
            if (dt >= 100) {
                KeydroidxLog.w(TAG, "【主线程耗时】$label = ${dt}ms")
            }
        }
    }

    // endregion

    private fun deleteBookmark(bookmark: Bookmark.Entry) {
        lifecycleScope.launch {
            try {
                val success = bookmarkRepository.deleteBookmark(bookmark)
                if (success) {
                    Toast.makeText(this@KeyphoneBrowserActivity, R.string.keyphone_bookmark_deleted, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@KeyphoneBrowserActivity, R.string.keyphone_bookmark_delete_failed, Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                KeydroidxLog.e(TAG, "删除书签失败: ${bookmark.url}", t)
                Toast.makeText(this@KeyphoneBrowserActivity, R.string.keyphone_bookmark_delete_failed, Toast.LENGTH_SHORT).show()
            } finally {
                loadHomeData()
            }
        }
    }

    private fun openSearchEditor() {
        val fragment = KeydroidxTextInputFragment.newInstance(
            getString(R.string.keyphone_edit_title),
            null,
            getString(R.string.keyphone_edit_hint),
            false,
            MAX_INPUT_CHARS
        )
        fragment.setOnConfirmListener { text ->
            if (!text.isNullOrBlank()) {
                openUrlInNewTab(text)
            }
        }
        openFragment(fragment)
    }

    // endregion

    // region 顶栏地址栏

    private fun setUpAddressBar() {
        val container = addressBarContainer ?: return
        val input = KeyphoneViews.addressBar(this, getString(R.string.keyphone_search_hint))
        container.removeAllViews()
        container.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // 标定提示条：与地址框同槽位等高替换（标定时隐藏输入框显示提示，
        // 容器总高不变，不挤动 WebView 布局，保证标定坐标不漂移）
        val calibrateHint = KeyphoneViews.addressBarHint(this)
        calibrateHint.visibility = View.GONE
        container.addView(
            calibrateHint,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        calibrateStatusText = calibrateHint

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!settingAddressText) {
                    addressEditedByUser = true
                }
            }
        })
        input.setOnFocusChangeListener { _, hasFocus ->
            setAddressText(if (hasFocus) currentWebView()?.url ?: addressText else addressText)
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                submitAddress()
                true
            } else {
                false
            }
        }
        addressInput = input
    }

    private fun setAddressText(text: String) {
        val input = addressInput ?: return
        settingAddressText = true
        input.setText(text)
        input.setSelection(input.text?.length ?: 0)
        settingAddressText = false
        addressEditedByUser = false
    }

    private fun applyAddressBar() {
        if (getCurrentPage() != null || isHomeSelected) {
            return
        }
        val input = addressInput ?: return
        if (addressEditedByUser) {
            return
        }
        if (input.text?.toString() != addressText) {
            setAddressText(addressText)
        }
    }

    private fun submitAddress() {
        val input = addressInput ?: return
        val text = input.text?.toString()?.trim().orEmpty()
        input.clearFocus()
        if (text.isEmpty()) {
            applyAddressBar()
            return
        }
        openUrlInNewTab(text)
    }

    private fun updateTopBar() {
        val pageShown = getCurrentPage() != null
        if (isFullScreenMode) {
            tabBarScroll?.visibility = View.GONE
            addressBarContainer?.visibility = View.GONE
            findViewById<View>(CommonR.id.bottomPanel)?.visibility = View.GONE
        } else {
            tabBarScroll?.visibility = if (pageShown) View.GONE else View.VISIBLE
            findViewById<View>(CommonR.id.bottomPanel)?.visibility = View.VISIBLE
            if (!pageShown) {
                updateTabMode()
            }
        }
        // 页面 push/pop 都会走到这里：顺带刷新光标显隐，
        // 保证历史记录/标签页列表打开时方向键驱动的是列表焦点而不是光标
        updateMouseCursorVisibility()
    }

    // endregion

    // region 状态监听

    /** [observeState] 上次处理过的字段快照，用于字段级分流（progress 脉冲不碰重 UI） */
    private var lastObservedTabs: List<TabViewState>? = null
    private var lastObservedDisplayUrl: String? = null
    private var lastObservedProgress: Int = -1
    private var lastObservedIsPageFinished: Boolean = false

    /**
     * tabs 列表的语义比较：只看会影响 [updateTabMode] 的字段（id/标题/选中态/URL），
     * 引用不同但语义相同时返回 false，跳过整棵标签栏与内容区重排。
     */
    private fun tabsMeaningfullyChanged(prev: List<TabViewState>?, next: List<TabViewState>): Boolean {
        if (prev == null || prev.size != next.size) return true
        for (i in next.indices) {
            val a = prev[i]
            val b = next[i]
            if (a.id != b.id || a.title != b.title || a.isSelected != b.isSelected || a.url != b.url) {
                return true
            }
        }
        return false
    }

    private fun observeState() {
        lifecycleScope.launch {
            presenter.state.collectLatest { state ->
                // 主页标签已真正落定 → 解除在途短路（见 ensureHomeWindow）
                if (state.tabs.any { isTabHome(it) }) {
                    homeWindowDispatchAt = 0L
                }
                // 正在打开时 state.displayUrl 还是上一个主页标签的地址（甚至为空），
                // 直接覆盖会把点击瞬间写进去的目标地址抹掉，地址栏又退回占位文案。
                // pendingOpenUrl 期间一律显示用户刚点/刚输的那个。
                addressText = pendingOpenUrl ?: state.displayUrl
                // 退出阅读模式后的历史补写：等新章节页面加载完成（标题 / URL 都已上报）再覆盖
                if (state.progress == 100) {
                    reassertTrailHistory()
                    // 阅读页丢失恢复后的自动重进（见 [recoverReaderPage]）
                    maybeReenterReading(state)
                    // Activity 重建后的阅读会话恢复（见 [maybeRestoreReadingSession]）
                    maybeRestoreReadingSession(state)
                }
                if (state.tabs.isEmpty()) {
                    isHomeSelected = true
                }
                // 「正在打开」何时结束：WebView 已经 attach 进容器（用户看得见页面了），
                // 且 state 也确认当前标签不再是主页。两者都满足才交回 state 判定
                // isTabHome，否则清早了会闪回上一个主页标签。
                val hadPendingOpenUrl = pendingOpenUrl != null
                if (pendingOpenUrl != null) {
                    val selected = state.tabs.firstOrNull { it.isSelected }
                    if (currentWebView() != null && (selected == null || !isTabHome(selected))) {
                        KeydroidxLog.i(TAG, "页面已呈现，结束加载中: $pendingOpenUrl")
                        pendingOpenUrl = null
                    }
                }

                // 字段级分流：目录页这类资源密集页加载时，progress 每秒可达上百次脉冲。
                // progress-only 脉冲只允许更新进度条与加载遮罩；标签栏 / 地址栏 / 软键 /
                // 内容区可见性的全量刷新，只在 tabs / displayUrl 真正变化时执行。
                // （否则每次脉冲都走 updateTabMode → renderTabBar 全量重建，主线程消息队列
                // 被 UI 重建消息占满，按键事件长时间排队，表现为「按什么键都没用」。）
                val tabsChanged = tabsMeaningfullyChanged(lastObservedTabs, state.tabs)
                val displayUrlChanged = state.displayUrl != lastObservedDisplayUrl
                val progressChanged = state.progress != lastObservedProgress
                val pageFinishedChanged = state.isPageFinished != lastObservedIsPageFinished
                val pendingConsumed = hadPendingOpenUrl && pendingOpenUrl == null
                logReadingStatePulse(state, tabsChanged, displayUrlChanged, progressChanged)
                lastObservedTabs = state.tabs
                lastObservedDisplayUrl = state.displayUrl
                lastObservedProgress = state.progress
                lastObservedIsPageFinished = state.isPageFinished

                if (tabsChanged) {
                    updateTabMode()
                    applyProgress(state.progress, state.isPageFinished)
                } else if (progressChanged || pageFinishedChanged || displayUrlChanged || pendingConsumed) {
                    applyProgress(state.progress, state.isPageFinished)
                    updatePageLoading(currentWebView())
                }
                if (!isHomeSelected && (displayUrlChanged || tabsChanged)) {
                    applyAddressBar()
                    // 网页态预注入输入劫持 JS（幂等；按「标签:URL」去重，导航/换标签后重注入）
                    val selectedTab = state.tabs.firstOrNull { it.isSelected }
                    val key = "${selectedTab?.id}:${state.displayUrl}"
                    if (key != webInputInjectedKey) {
                        webInputInjectedKey = key
                        currentWebView()?.evaluateJavascript(webInputInstallJs, null)
                    }
                }
            }
        }
    }

    // region 阅读模式黑屏诊断

    /** 每帧状态脉冲最多打一条（tabs / url / progress 任一变化才算一帧，通常远低于 10 条/秒） */
    private fun logReadingStatePulse(
        state: BrowserViewState,
        tabsChanged: Boolean,
        displayUrlChanged: Boolean,
        progressChanged: Boolean
    ) {
        if (!tabsChanged && !displayUrlChanged && !progressChanged) return
        try {
            val selected = state.tabs.firstOrNull { it.isSelected }
            KeydroidxLog.d(
                TAG,
                "state: tabs=${state.tabs.size} tab=${state.tabs.indexOfFirst { it.isSelected }}" +
                    "(${selected?.id}) progress=${state.progress} display=${state.displayUrl}" +
                    " reading=${isReadingMode()} overlay=${activeMenuType}" +
                    " pending=$pendingOpenUrl changed[tabs=$tabsChanged url=$displayUrlChanged" +
                    " progress=$progressChanged]"
            )
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "打印阅读模式状态日志失败", t)
        }
    }

    /**
     * 阅读模式现场采样（每 [READING_WATCH_INTERVAL_MS] 一次，仅 debug）。
     *
     * 用户反馈：「阅读模式滚一段距离后黑屏、界面全空；退出阅读模式发现它在加载页面且一直加载不出来」。
     * 状态脉冲日志只有在 state 真的变化时才有输出，而事故现场很可能就是 WebView 卡在某个
     * 导航上不再上报——那种时候日志会完全静默，排查无从下手。这里在阅读态、或页面正在加载时
     * 定时采一次样：进度 / 遮罩 / 页面视图可见性 / WebView 的 url、progress、滚动位置、
     * 可见性全部落盘；加载连续超过 [READING_STALL_MS] 时再抓一次 WebView 相关线程栈，
     * 用来判断「一直加载不出来」卡在导航的哪一步。
     *
     * 另外 [detectBrokenReaderState] 会在识别出「阅读态无 WebView」「遮罩久挂」时立即打
     * 一次 ERROR 级完整快照（会落盘），不必等用户察觉异常。
     * release 构建不启动定时采样（与主线程看门狗同一策略）。
     */
    private fun installReadingWatchdog() {
        // 不再限制 debug：release 包同样需要「自动识别异常并落盘」——
        // 用户复现成本很高，不能因为包类型不同就丢掉现场。采样本身每 2 秒一次、只读几个
        // 已缓存字段（不做同步跨进程调用），开销可忽略；详细采样日志在 release 下不会落盘
        // （KeydroidxLog 默认只落 ERROR），但异常现场走 KeydroidxLog.e，一定会落盘。
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                handler.postDelayed(this, READING_WATCH_INTERVAL_MS)
                if (!isFinishing && !isDestroyed) {
                    try {
                        sampleReadingState()
                    } catch (t: Throwable) {
                        KeydroidxLog.e(TAG, "阅读模式现场采样失败", t)
                    }
                }
            }
        }
        handler.postDelayed(tick, READING_WATCH_INTERVAL_MS)
    }

    /** 一次阅读模式现场采样（见 [installReadingWatchdog]） */
    private fun sampleReadingState() {
        // 只看前台且屏幕点亮：后台 / 熄屏时 Surface 已销毁、合成器停产，帧回调必然不回来，
        // 继续采样只会把「不出帧」误判成「合成卡死」并触发 [recoverBrokenReader]
        // （挂机键回桌面 bug：ENDCALL 只熄屏，Activity 仍 resumed，见 [isInForeground]）。
        // 同时把帧心跳复位：熄屏期间挂着的那次回调不能把「熄屏时长」算成停滞。
        if (!isInForeground || !isScreenOn()) {
            readerFramePending = false
            readerFrameRequestedAt = 0L
            return
        }
        // 熄屏 / 后台期间错过的阅读自动重进（见 [maybeReenterReading]）：
        // 重新可见后补一次判定（熄屏时 onResume 不会重发，只能靠这里兜）
        if (pendingReadingReenterUrl != null) {
            maybeReenterReading(presenter.state.value)
        }
        val tabId = selectedTabId()
        val inReading = tabId != null && readingModeTabs.containsKey(tabId)
        val wv = currentWebView()
        val loading = wv?.let {
            try {
                // progress < 100 即「导航已提交但首帧还没画完」；不再去碰 isLoading 之类
                // 会跨进程同步取值的方法（弱机上这些同步调用本身就会堵主线程几秒）
                it.progress < 100
            } catch (t: Throwable) {
                KeydroidxLog.e(TAG, "读取 WebView 加载状态失败", t)
                false
            }
        }
        // 阅读态下**无条件**采样（不再只在「加载中」才采）：黑屏时往往进度停在 100、
        // WebView 也已不在，按旧判据会直接 return，恰恰错过要抓的现场
        if (!inReading && loading != true) return
        // 帧回调心跳：阅读态下即使日志里没有任何异常，也能发现「页面不再出帧」这种黑屏
        probeReaderFrame()
        val url = try {
            wv?.url.orEmpty()
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "读取 WebView url 失败", t)
            "<err>"
        }
        val detail = describeReadingTab()
        val overlay = if (pageLoading != null && pageLoading!!.visibility == View.VISIBLE) {
            "VISIBLE(${pageLoadingBusyMs()}ms)"
        } else {
            "GONE"
        }
        KeydroidxLog.d(
            TAG,
            "【阅读现场】$detail overlay=$overlay url=$url loading=$loading" +
                " wvProgress=${wv?.progress} wvVis=${wv?.visibility}" +
                " wvSize=${wv?.width}x${wv?.height} scrollY=${wv?.scrollY}" +
                " canGoBack=${wv?.canGoBack()} contentPx=${wv?.let { (it.contentHeight * it.scale).toInt() }}" +
                " host=${browserFrameHost?.visibility}"
        )
        // 只有「加载久挂」才抓线程栈（正常加载几秒内就结束，抓栈毫无意义且刷屏）：
        // 连续 loading 超过阈值抓一次，之后最多每 2 个阈值时长补抓一次
        if (loading == true) {
            if (readingLoadingSince == 0L) {
                readingLoadingSince = SystemClock.elapsedRealtime()
                readingUrlAtLoadStart = url
            }
            val loadingMs = SystemClock.elapsedRealtime() - readingLoadingSince
            if (loadingMs >= READING_STALL_MS &&
                SystemClock.elapsedRealtime() - lastStackTraceAt >= READING_STALL_MS * 2
            ) {
                lastStackTraceAt = SystemClock.elapsedRealtime()
                KeydroidxLog.e(
                    TAG,
                    "【阅读现场】加载已持续 ${loadingMs}ms 未结束 url=$url" +
                        " 起始=$readingUrlAtLoadStart，WebView 线程栈:\n\t" + dumpWebViewThreads()
                )
            }
        } else {
            readingLoadingSince = 0L
        }
        // 阅读页丢失的主动检测：阅读态下 WebView 一定停在 data 页（getUrl()==about:blank，
        // 见 [openReaderPage]）。若它变成了一个真实 http 页面（池回收/Activity 重建后恢复），
        // 说明阅读页已经没了——不等用户按键，直接恢复。
        if (inReading && wv != null && readerSettledAt != 0L &&
            SystemClock.elapsedRealtime() - readerSettledAt > READER_SETTLE_GUARD_MS
        ) {
            val wvUrl = try {
                wv.url.orEmpty()
            } catch (t: Throwable) {
                ""
            }
            if (wvUrl.startsWith("http")) {
                recoverReaderPage("看门狗发现 WebView 已离开 data 页 url=$wvUrl")
                return
            }
        }
        detectBrokenReaderState(inReading, wv, overlay)
    }

    /**
     * 自动识别「已经坏掉」的现场并立即打一次完整快照。
     *
     * 用户复现这个 bug 成本很高（要连续读很久），不能指望他察觉异常后再来喊人：
     * 只要出现下面任一**明确异常**，就在这里立刻落一条 ERROR 日志（`KeydroidxLog.e` 会同时
     * 落盘 + 生成待上传标记），把当时的全部状态固化下来，事后再看文件即可：
     *
     * 1. 处于阅读态但**当前标签已经没有 WebView** —— 渲染进程死亡后 WebView 被 destroy，
     *    内容区空白且 `updatePageLoading` 会显示「正在打开…」，正是用户描述的症状；
     * 2. 不透明遮罩（「正在打开…」）连续挂了超过 [BROKEN_OVERLAY_MS]；
     * 3. 阅读态下页面进度长期卡在 <100 且没有推进（导航卡死）。
     *
     * 每种情况每次「故障窗口」只报一次（[brokenReported]），避免刷屏与配额浪费。
     */
    private fun detectBrokenReaderState(inReading: Boolean, webView: WebView?, overlay: String) {
        val now = SystemClock.elapsedRealtime()
        val overlayVisible = overlay.startsWith("VISIBLE")
        val overlayMs = pageLoadingBusyMs()
        val reason = when {
            inReading && webView == null -> "阅读态但无 WebView（渲染进程可能已死亡）"
            overlayVisible && overlayMs >= BROKEN_OVERLAY_MS ->
                "「正在打开」遮罩已挂 ${overlayMs}ms"
            else -> null
        }
        if (reason == null) {
            brokenReported = false
            return
        }
        if (brokenReported) return
        brokenReported = true
        KeydroidxLog.e(
            TAG,
            "【阅读异常现场】$reason | ${describeReadingTab()} | overlay=$overlay" +
                " | 阅读会话=${readingModeTabs.keys} 章数=${readingModeTabs[selectedTabId()]?.chapterCount}" +
                " | 最近章节=${readingModeTabs[selectedTabId()]?.lastUrl}" +
                " | 遮罩=${pageLoading?.visibility} 内容区=${contentFrame?.childCount} 子View" +
                " | 主线程栈:\n\t" +
                Looper.getMainLooper().thread.stackTrace.take(READING_STACK_FRAMES)
                    .joinToString("\n\t")
        )
        // 现场再补一次 JS 侧状态（可能因 WebView 已死而失败，失败本身也是证据）
        currentWebView()?.let { wv ->
            try {
                wv.evaluateJavascript(ReadingModeJs.progress(diag = true)) { raw ->
                    val (_, diag, style) = ReadingModeJs.split(decodeJsString(raw))
                    KeydroidxLog.e(TAG, "【阅读异常现场】JS diag=$diag style=$style")
                }
            } catch (t: Throwable) {
                KeydroidxLog.w(TAG, "【阅读异常现场】取 JS 状态失败（WebView 可能已不可用）", t)
            }
        }
        // 记录本次异常发生时刻，供事后与系统侧日志（excessive cpu / 杀进程）对齐
        KeydroidxLog.e(TAG, "【阅读异常现场】时间戳=${System.currentTimeMillis()} uptime=$now")
        // 识别到故障后立即尝试自动恢复（见 recoverBrokenReader）：用户复现成本极高，
        // 不能只落日志等人工。恢复带冷却，避免循环。
        recoverBrokenReader(reason)
    }

    /**
     * 阅读模式故障自动恢复（最后一道防线）。
     *
     * 触发条件见 [detectBrokenReaderState] 与 [probeReaderFrame]：
     * - 渲染进程死亡后 WebView 未能重建（[webView] 修复后理论上不再发生，留作兜底）；
     * - 「正在打开」遮罩久挂；
     * - 帧回调长期不回（合成卡住，文字消失成背景色）—— 这是用户描述的头号症状，
     *   主线程活、按键响、DOM 在，就是不出帧，只能强制重载逼渲染管线重来。
     *
     * 恢复动作：先清掉阅读态（死态下 `exitReadingMode` 走不通，它要 evaluate JS），再决定
     * 导航去向：
     * - 有会话（读的是 data 外壳）→ 走 [recoverReaderPage] 同一条路径：导航回会话里最后读到
     *   的**真实章节页**，并用 [pendingReadingReenterUrl] 让加载完成后自动重进阅读模式。
     *   绝不能对 data 外壳 `reload`（[RefreshOrStopClick]）——重放 data 页只会重放空壳：
     *   背景色还在、拼进来的章节全丢（回到入口章），而且 Kotlin 侧已无阅读态，表现为
     *   「看着像阅读模式其实是普通浏览（光标出现、方向键移光标）」。
     * - 无会话且是普通网页 → 原地刷新，逼卡住的合成管线重新提交一帧；
     * - WebView 已死 → 新开一个标签载入最后读到的那一章（[Action.LoadUrl] 从池里重建）。
     * 冷却 [READER_RECOVER_COOLDOWN_MS]，防止反复触发开一堆标签。
     */
    private fun recoverBrokenReader(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReaderRecoverAt < READER_RECOVER_COOLDOWN_MS) {
            KeydroidxLog.w(
                TAG,
                "【阅读现场】自动恢复在冷却中，跳过 reason=$reason 距上次=${now - lastReaderRecoverAt}ms"
            )
            return
        }
        lastReaderRecoverAt = now
        val tabId = selectedTabId()
        val session = tabId?.let { readingModeTabs[it] }
        val lastUrl = session?.lastUrl?.takeIf { it.isNotBlank() } ?: session?.entryUrl
        // 重置帧心跳，避免恢复后旧回调没回仍报“停滞”
        readerFramePending = false
        readerFrameRequestedAt = 0L
        KeydroidxLog.e(
            TAG,
            "【阅读现场】触发自动恢复 reason=$reason tabId=$tabId" +
                " lastUrl=$lastUrl wv=${currentWebView() != null} ${describeReadingTab()}"
        )
        // 清阅读态：exitReadingMode 依赖 WebView，死态下会提前 return；这里直接清，
        // 让 back 键 / 标签模式恢复正常，并防止 sampleReadingState 继续按“阅读态”采样
        if (tabId != null) readingModeTabs.remove(tabId)
        readerSettledAt = 0L
        updateBackPressedEnabled()
        updateTabMode()
        val wv = currentWebView()
        if (lastUrl != null) {
            // 有章节落点：不碰 data 外壳，直接导航回最后读到的真实章节，落地后自动重进
            pendingReadingReenterUrl = lastUrl
            KeydroidxLog.d(TAG, "【阅读现场】恢复导航到最后章节 $lastUrl（不重放 data 外壳）")
            if (wv != null) {
                try {
                    wv.loadUrl(lastUrl)
                } catch (t: Throwable) {
                    KeydroidxLog.e(TAG, "【阅读现场】恢复导航失败 target=$lastUrl", t)
                }
            } else {
                // WebView 已死：新开标签载入最后读到的那一章，重进交给下次 progress==100
                presenter.onEvent(
                    BrowserUiEvent.NewAction(BrowserContract.Action.LoadUrl(lastUrl))
                )
            }
            return
        }
        if (wv == null) {
            // 既无 WebView 也无章节落点：刷新当前标签，交给渲染进程死亡回调链路自行重建
            presenter.onEvent(BrowserUiEvent.RefreshOrStopClick)
            return
        }
        val wvUrl = try {
            wv.url.orEmpty()
        } catch (t: Throwable) {
            KeydroidxLog.w(TAG, "【阅读现场】恢复时读取 WebView url 失败", t)
            ""
        }
        if (!wvUrl.startsWith("http")) {
            // 无会话又停在非真实页（data 外壳 / about:blank）：刷新只会重放空壳，
            // 既找不回章节又留下「假阅读模式」，宁可不动作
            KeydroidxLog.w(
                TAG,
                "【阅读现场】无阅读会话且当前非真实页面 url=$wvUrl，跳过刷新（避免重放 data 外壳）"
            )
            return
        }
        // WebView 还活着且是普通网页（多是合成卡住）：原地刷新，逼渲染管线重新提交一帧
        clearReadingModeOnNavigation()
        presenter.onEvent(BrowserUiEvent.RefreshOrStopClick)
    }

    /** 已就当前故障窗口报过一次异常现场（恢复后复位） */
    private var brokenReported = false

    /** 当前 WebView 连续处于 isLoading 的起点（0 = 没有在加载） */
    private var readingLoadingSince = 0L

    /** 本次「加载久挂」开始时的 URL（判断是否跳转到了别处） */
    private var readingUrlAtLoadStart = ""

    /** 上次抓线程栈的时刻（避免每 2 秒刷一遍栈） */
    private var lastStackTraceAt = 0L

    /** 把 WebView 相关线程（含 chromium 线程池）的栈抓出来，用于「一直加载不出来」定位 */
    private fun dumpWebViewThreads(): String {
        val builder = StringBuilder()
        for (thread in Thread.getAllStackTraces().entries) {
            val name = thread.key.name
            if (name.contains("chromium", ignoreCase = true) ||
                name.contains("JavaBridge", ignoreCase = true) ||
                name == Looper.getMainLooper().thread.name
            ) {
                builder.append("\n[thread] ").append(name).append('\n')
                thread.value.take(READING_STACK_FRAMES).forEach { frame ->
                    builder.append("\t").append(frame).append('\n')
                }
            }
        }
        return if (builder.isEmpty()) "<无 WebView 相关线程>" else builder.toString()
    }

    /**
     * WebView 帧回调心跳（API 23+ 的 `postVisualStateCallback`，回调运行在 UI 线程）。
     *
     * 「看着看着文字全部消失、只剩背景色」最麻烦的一种形态是：主线程活着、按键有响应、
     * 页面 DOM 也在，但**合成不出帧**——这时日志里一行异常都没有，只有屏幕是空的。
     * 这里每 2 秒让 WebView 出一帧并要求一次回调：回调超过 3 秒没回来就说明帧管线停了，
     * 直接落一条 ERROR 日志（单独 tag，方便从别的日志里一眼捞出来）。
     */
    private fun probeReaderFrame() {
        val webView = currentWebView() ?: return
        val now = SystemClock.elapsedRealtime()
        if (readerFramePending) {
            // 上一次请求还没回调：说明这段时间 WebView 没产出可用的帧
            val waited = now - readerFrameRequestedAt
            if (waited >= READER_FRAME_STALL_MS && now - readerFrameLastLogAt >= READER_FRAME_STALL_MS) {
                readerFrameLastLogAt = now
                KeydroidxLog.e(
                    TAG,
                    "【阅读现场】帧回调已等待 ${waited}ms 未返回（页面可能不再出帧 / 合成卡住）" +
                        " ${describeReadingTab()}"
                )
            }
            // 帧管线停得太久（远超“慢一帧”的合理范围）= 文字消失成背景色，强制恢复
            if (waited >= READER_FRAME_RECOVER_MS) {
                recoverBrokenReader("帧回调停滞 ${waited}ms 未返回")
            }
            return
        }
        readerFramePending = true
        readerFrameRequestedAt = now
        try {
            webView.postVisualStateCallback(
                now and 0x7FFFFFFF,
                object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        readerFramePending = false
                    }
                }
            )
        } catch (t: Throwable) {
            readerFramePending = false
            KeydroidxLog.e(TAG, "阅读模式帧回调请求失败", t)
        }
    }

    /** 是否有未回来的帧回调（未回来 = 这段时间内没有合成出帧） */
    private var readerFramePending = false

    /** 当前帧回调请求发出的时刻 */
    private var readerFrameRequestedAt = 0L

    /** 「帧停滞」日志的节流时间戳 */
    private var readerFrameLastLogAt = 0L

    /** 上次自动恢复触发时刻（[recoverBrokenReader] 冷却用） */
    private var lastReaderRecoverAt = 0L

    // endregion

    private fun applyProgress(progress: Int, isPageFinished: Boolean) {
        val bar = progressBar ?: return
        if (isHomeSelected) {
            bar.reset()
            return
        }
        val currentWv = currentWebView()
        val wvId = System.identityHashCode(currentWv)
        val realWvProgress = currentWv?.progress ?: 0
        KeydroidxLog.i(
            "KeyphoneProgress",
            "[Activity.applyProgress] state.progress=$progress isFinished=$isPageFinished wv#$wvId wv.progress=$realWvProgress url=${currentWv?.url}"
        )

        // 双闸门终场裁决：
        // 闸门 A：页面加载完成事件 (onPageFinished) 已触发并经 Presenter 上报
        // 闸门 B：底层 WebView 当前真实 progress == 100（与阅读模式准入条件物理同源）
        if (isPageFinished && realWvProgress >= 100) {
            bar.complete()
        } else {
            bar.setProgress(progress)
        }
    }

    // endregion

    // region 标签页管理

    private fun showTabsPage() {
        if (getCurrentPage() is KeyphoneTabsFragment) {
            return
        }
        lifecycleScope.launch {
            withTimeoutOrNull(TABS_WAIT_MS) {
                presenter.state.first { it.tabs.isNotEmpty() }
            }
            if (!isFinishing && !isDestroyed) {
                openFragment(KeyphoneTabsFragment())
            }
        }
    }

    /**
     * 打开按键机「历史记录」页。
     *
     * 独立 Activity（[KeyphoneHistoryActivity]，与安全拦截页同款骨架）：
     * 自带基类标题栏，观感与选项页 / 安全拦截页一致；打开链接经浏览页
     * singleTop 入口（intent data）新标签页加载。
     */
    private fun showHistoryPage() {
        try {
            startActivity(Intent(this, KeyphoneHistoryActivity::class.java))
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "打开历史记录失败", t)
        }
    }

    internal fun tabsSnapshot(): List<TabViewState> = presenter.state.value.tabs

    internal fun tabsFlow(): Flow<List<Int>> = presenter.state
        .map { state -> state.tabs.map { if (it.isSelected) -it.id else it.id } }
        .distinctUntilChanged()

    internal fun closeTab(index: Int) {
        val closingId = presenter.state.value.tabs.getOrNull(index)?.id
        presenter.onEvent(BrowserUiEvent.TabClose(index))
        if (closingId != null) {
            readingModeTabs.remove(closingId)
        }
        // 标签被清空时补主页。走幂等的 ensureHomeWindow：与 Presenter 侧
        // navigator.closeBrowser → onAllTabsClosed 的兜底撞车时也只会有一个主页。
        if (presenter.state.value.tabs.isEmpty()) {
            ensureHomeWindow()
        }
    }

    internal fun clearOtherTabs() {
        val currentId = presenter.state.value.tabs
            .firstOrNull { it.isSelected }?.id ?: return
        presenter.onEvent(
            BrowserUiEvent.CloseBrowser(currentId, BrowserContract.CloseTabEvent.CLOSE_OTHERS)
        )
    }

    internal fun clearAllTabs() {
        val currentId = presenter.state.value.tabs
            .firstOrNull { it.isSelected }?.id ?: 0
        readingModeTabs.clear()
        presenter.onEvent(
            BrowserUiEvent.CloseBrowser(currentId, BrowserContract.CloseTabEvent.CLOSE_ALL)
        )
        Toast.makeText(this, R.string.keyphone_tabs_cleared, Toast.LENGTH_SHORT).show()
        // 这里**不能**再本地建主页：CLOSE_ALL 在 Presenter 侧必然走
        // navigator.closeBrowser → onAllTabsClosed → ensureHomeWindow 补主页，
        // 两边都建就会多出一个主页窗口。而此刻本地 state 还是删除前的旧快照，
        // 若在此调 ensureHomeWindow/switchToTab，还会把 TabClick 排到 deleteAllTabs
        // 之后执行，onTabClick 里 state.value.tabs[index] 直接越界。
    }

    internal fun confirmClearAllTabs() {
        KeydroidxConfirmDialog(
            this,
            getString(R.string.keyphone_clear_tabs_title),
            getString(R.string.keyphone_clear_tabs_message)
        )
            .setPositiveButton(getString(R.string.keyphone_soft_clear)) { clearAllTabs() }
            .setNegativeButton(getString(R.string.keyphone_soft_cancel), null)
            .show()
    }

    /**
     * Presenter 侧 `navigator.closeBrowser()` / `backgroundBrowser()` 在 keyphone
     * 下的落点：标签已全部关闭，此处是补主页窗口的**权威**信号（幂等，见 [ensureHomeWindow]）。
     */
    internal fun onAllTabsClosed() {
        if (isFinishing || isDestroyed) return
        KeydroidxLog.i(TAG, "onAllTabsClosed: 标签已全部关闭，补主页窗口")
        ensureHomeWindow()
    }

    internal fun goHomeFromTabs() {
        supportFragmentManager.popBackStack()
        switchToHome()
    }

    internal fun closeTabsPage() {
        supportFragmentManager.popBackStack()
    }

    private fun addCurrentPageToHome() {
        // 阅读态下 WebView 的 getUrl() 是 about:blank，必须用会话里记着的真实章节 URL
        val url = currentRealUrl()
        if (url.isBlank() || url.isSpecialUrl()) {
            Toast.makeText(this, R.string.keyphone_add_home_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val title = presenter.state.value.tabs.firstOrNull { it.isSelected }?.title.orEmpty()
        lifecycleScope.launch {
            try {
                if (bookmarkRepository.isBookmark(url)) {
                    Toast.makeText(this@KeyphoneBrowserActivity, R.string.keyphone_already_home, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val minPosition = bookmarkRepository.getAllBookmarksSorted()
                    .minOfOrNull { it.position } ?: 0
                val added = bookmarkRepository.addBookmarkIfNotExists(
                    Bookmark.Entry(
                        url = url,
                        title = title.ifBlank { url },
                        position = minPosition - 1,
                        folder = Bookmark.Folder.Root
                    )
                )
                // 书签落库后必须立刻回灌主页内存数据与宫格 View，
                // 否则回到主页时仍渲染旧列表，重复添加就会命中「已在首页」。
                if (added) {
                    loadHomeData()
                }
                Toast.makeText(
                    this@KeyphoneBrowserActivity,
                    if (added) R.string.keyphone_added_home else R.string.keyphone_already_home,
                    Toast.LENGTH_SHORT
                ).show()
            } catch (t: Throwable) {
                KeydroidxLog.e(TAG, "添加到首页失败", t)
            }
        }
    }

    // endregion

    // region 按键分发

    /**
     * 动作键防重入：同一 keyCode 在 [ACTION_KEY_DEBOUNCE_MS] 内重复 DOWN 时返回 true，
     * 调用方应直接吞掉该事件（DOWN 与随后的 UP 都不再做任何动作）。
     *
     * 覆盖两种来源：
     * 1. 设备连击 —— 一次物理按下产生两个独立的 repeatCount=0 的 DOWN；
     * 2. 长按后松手再补发 —— 部分固件在长按结束时补一个「新按下」。
     * 正常长按的重复事件 repeatCount>0，本来就被 repeatCount==0 过滤，不会走到这里。
     */
    private fun shouldSwallowDuplicatedActionKey(keyCode: Int): Boolean {
        val now = SystemClock.elapsedRealtime()
        val duplicated = keyCode == actionKeyGuardCode && now < actionKeyGuardUntil
        actionKeyGuardCode = keyCode
        actionKeyGuardUntil = now + ACTION_KEY_DEBOUNCE_MS
        if (duplicated) {
            KeydroidxLog.d(TAG, "按键 key=$keyCode 在 ${ACTION_KEY_DEBOUNCE_MS}ms 内重复触发，已忽略（疑似设备连击）")
        }
        return duplicated
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 每次 DOWN 打一条流水：下一次 DOWN 的日志晚了多久，就说明上一次按键处理卡了多久
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val now = SystemClock.elapsedRealtime()
            val gap = if (lastKeyDownAt == 0L) -1L else now - lastKeyDownAt
            lastKeyDownAt = now
            KeydroidxLog.d(
                TAG, "按键 key=${event.keyCode} 距上次=$gap ms busy=${isRendererLikelyBusy()}"
            )
        }
        // 如果该按键的 DOWN 事件已经被快捷菜单/快捷键消费，则必须拦截并消费对应的 UP 事件，防止穿透到底层视图
        if (event.action == KeyEvent.ACTION_UP && lastConsumedDownKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
            if (event.keyCode == lastConsumedDownKeyCode) {
                lastConsumedDownKeyCode = KeyEvent.KEYCODE_UNKNOWN
                return true
            }
        }

        // 编辑页 / 选项页等 Fragment 打开时交给基类分发
        if (getCurrentPage() != null) {
            return super.dispatchKeyEvent(event)
        }

        // 地址栏持有焦点时：数字/字母输入正常透传给输入框
        val input = addressInput
        if (input != null && input.hasFocus()) {
            return super.dispatchKeyEvent(event)
        }

        // 快捷菜单展示态：全模态接管所有按键，严禁任何按键事件穿透至背景视图、书签或网页
        if (activeMenuType != QuickMenuType.NONE) {
            val isDown = event.action == KeyEvent.ACTION_DOWN
            val isFirstDown = isDown && event.repeatCount == 0

            if (isDown) {
                lastConsumedDownKeyCode = event.keyCode
            }

            // 1. 匹配 0~9、*、# 键直达触发
            val keyLabel = when (event.keyCode) {
                KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> "0"
                KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> "1"
                KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> "2"
                KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> "3"
                KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> "4"
                KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> "5"
                KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> "6"
                KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> "7"
                KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> "8"
                KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> "9"
                KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> "*"
                KeyEvent.KEYCODE_POUND -> "#"
                else -> null
            }

            if (keyLabel != null) {
                if (isFirstDown) {
                    lastConsumedDownKeyCode = event.keyCode
                    // 菜单刚弹出时，同一键的连击重影必须吞掉，否则会立刻被执行成菜单项
                    // （# 菜单的 # = 切换全屏；* 菜单的 * = 关闭窗口），表现为「按一下 # 却退出全屏」
                    if (shouldSwallowDuplicatedActionKey(event.keyCode)) {
                        return true
                    }
                    val items = getActiveMenuItems()
                    val matchedItem = items.firstOrNull { it.keyLabel == keyLabel }
                    if (matchedItem != null) {
                        hideQuickMenu()
                        matchedItem.action()
                    } else if (keyLabel == "#" && activeMenuType == QuickMenuType.PAGE_MENU) {
                        // 在 * 键菜单打开时按 # 直接切到全局 # 菜单
                        showGlobalMenu()
                    } else if (keyLabel == "1" && activeMenuType == QuickMenuType.GLOBAL_MENU) {
                        // 在 # 键菜单中按 1 直接切到 * 键页面菜单
                        showPageMenu()
                    }
                }
                return true
            }

            // 2. 菜单内导航与操作（全在 dispatchKeyEvent 内闭环，严禁 super.dispatchKeyEvent 穿透至底层 View）
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (isFirstDown) {
                        val items = getActiveMenuItems()
                        if (items.isNotEmpty()) {
                            quickMenuFocusIndex = (quickMenuFocusIndex - 1 + items.size) % items.size
                            shellOverlay?.updateMenuFocus(quickMenuFocusIndex)
                        }
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (isFirstDown) {
                        val items = getActiveMenuItems()
                        if (items.isNotEmpty()) {
                            quickMenuFocusIndex = (quickMenuFocusIndex + 1) % items.size
                            shellOverlay?.updateMenuFocus(quickMenuFocusIndex)
                        }
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_5,
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_SOFT_LEFT -> {
                    if (isFirstDown) {
                        val items = getActiveMenuItems()
                        if (quickMenuFocusIndex in items.indices) {
                            val action = items[quickMenuFocusIndex].action
                            hideQuickMenu()
                            action()
                        }
                    }
                    return true
                }
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.KEYCODE_SOFT_RIGHT,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (isFirstDown) {
                        hideQuickMenu()
                    }
                    return true
                }
                else -> {
                    // 菜单打开期间消费其余全部按键，防止底层视图误响应
                    return true
                }
            }
        }

        // 自由复制模式：优先于 WebView 拦截方向键与确认键/软键，防止按键被 WebView 空间导航吞掉
        if (isFreeCopyMode) {
            val isDown = event.action == KeyEvent.ACTION_DOWN
            val isFirstDown = isDown && event.repeatCount == 0

            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (isDown) {
                        lastConsumedDownKeyCode = event.keyCode
                        moveFreeCopyCaret("up")
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (isDown) {
                        lastConsumedDownKeyCode = event.keyCode
                        moveFreeCopyCaret("down")
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (isDown) {
                        lastConsumedDownKeyCode = event.keyCode
                        moveFreeCopyCaret("left")
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (isDown) {
                        lastConsumedDownKeyCode = event.keyCode
                        moveFreeCopyCaret("right")
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_5,
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (isDown) {
                        lastConsumedDownKeyCode = event.keyCode
                        if (isFirstDown) {
                            if (!isCopySelecting) {
                                startFreeCopySelect()
                            } else {
                                performFreeCopy()
                            }
                        }
                    }
                    return true
                }
                KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_MENU -> {
                    if (isDown) {
                        lastConsumedDownKeyCode = event.keyCode
                        if (isFirstDown && isCopySelecting) {
                            resetFreeCopySelect()
                        }
                    }
                    return true
                }
                KeyEvent.KEYCODE_SOFT_RIGHT, KeyEvent.KEYCODE_BACK -> {
                    if (isDown) {
                        lastConsumedDownKeyCode = event.keyCode
                        if (isFirstDown) {
                            exitFreeCopyMode()
                        }
                    }
                    return true
                }
            }
        }

        // 页内查找模式：优先于 WebView 拦截方向键与确认键/软键
        if (isFindMode) {
            val isDown = event.action == KeyEvent.ACTION_DOWN
            val isFirstDown = isDown && event.repeatCount == 0

            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (isDown) {
                        lastConsumedDownKeyCode = event.keyCode
                        findPreviousMatch()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (isDown) {
                        lastConsumedDownKeyCode = event.keyCode
                        findNextMatch()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_5,
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (isDown) {
                        lastConsumedDownKeyCode = event.keyCode
                        if (isFirstDown) {
                            findNextMatch()
                        }
                    }
                    return true
                }
                KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_MENU -> {
                    if (isDown) {
                        lastConsumedDownKeyCode = event.keyCode
                        if (isFirstDown) {
                            findPreviousMatch()
                        }
                    }
                    return true
                }
                KeyEvent.KEYCODE_SOFT_RIGHT, KeyEvent.KEYCODE_BACK -> {
                    if (isDown) {
                        lastConsumedDownKeyCode = event.keyCode
                        if (isFirstDown) {
                            exitFindMode()
                        }
                    }
                    return true
                }
            }
        }

        // 专属按键适配模式：数字键选键/保存，方向键移动光标，确认/左软键保存，右软键完成。
        // 全模态接管，严禁按键穿透至 WebView。
        // 注意：不能写 lastConsumedDownKeyCode——否则同键 UP 会在 dispatchKeyEvent
        // 顶部被吞掉，onDirectionUp 收不到，光标连发永不停止（一直滑到边）
        if (isCalibrateMode) {
            val isDown = event.action == KeyEvent.ACTION_DOWN
            val isFirstDown = isDown && event.repeatCount == 0

            val dir = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> KeyphoneShellOverlay.DIR_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> KeyphoneShellOverlay.DIR_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> KeyphoneShellOverlay.DIR_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> KeyphoneShellOverlay.DIR_RIGHT
                else -> KeyphoneShellOverlay.DIR_NONE
            }
            if (dir != KeyphoneShellOverlay.DIR_NONE) {
                if (isDown) {
                    mouseController?.onDirectionDown(dir)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    mouseController?.onDirectionUp(dir)
                }
                return true
            }

            val digit = keyDigitOf(event.keyCode)
            if (digit in 1..9) {
                if (isFirstDown) {
                    // 实时保存：切换键的瞬间自动保存上一键位置，再开始标定新键；
                    // 同键重复按下视为重新选键（光标跳回该键落点重新微调），无"双击保存"
                    if (calibratingDigit in 1..9 && calibratingDigit != digit) {
                        saveCalibratePoint()
                    }
                    beginCalibrateDigit(digit)
                }
                return true
            }

            when (event.keyCode) {
                // 这台设备左软键实际发 KEYCODE_MENU（同自由复制/页内查找的处理），必须并列
                KeyEvent.KEYCODE_SOFT_LEFT,
                KeyEvent.KEYCODE_MENU -> {
                    // 左软键「重置」：该键写回默认九宫格落点，光标同步跳过去
                    if (isFirstDown) {
                        resetCalibrateCursor()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_5,
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (isFirstDown && calibratingDigit in 1..9) {
                        saveCalibratePoint()
                    }
                    return true
                }
                KeyEvent.KEYCODE_SOFT_RIGHT,
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_ESCAPE -> {
                    if (isFirstDown) {
                        exitCalibrateMode()
                    }
                    return true
                }
                else -> return true
            }
        }

        // 阅读模式：方向键直接整屏翻页（长按连翻），不再走鼠标光标移动 + 贴边慢速滚动
        if (!isHomeSelected && isReadingMode() && handleReadingModeKeys(event)) {
            return true
        }

        // 网页态鼠标模式：方向键驱动光标移动（数值与连发节奏照抄 keydroidx-foucs）；
        // 消费了 DOWN 就必须消费同键 UP，防止抬起事件穿透
        if (isMouseModeActive()) {
            val dir = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> KeyphoneShellOverlay.DIR_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> KeyphoneShellOverlay.DIR_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> KeyphoneShellOverlay.DIR_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> KeyphoneShellOverlay.DIR_RIGHT
                else -> KeyphoneShellOverlay.DIR_NONE
            }

            // 1. 数字 5 键作为修饰键：按住 5 期间方向键改为基于光标当前位置的定向滚动
            if (event.keyCode == KeyEvent.KEYCODE_5) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) {
                        cursorScrollModifierHeld = true
                        cursorScrollModifierUsed = false
                        cursorScrollModifierDownAt = SystemClock.uptimeMillis()
                        KeydroidxLog.d(TAG, "5 键按下，进入光标定向滚动待命")
                    }
                    return true
                } else if (event.action == KeyEvent.ACTION_UP) {
                    val wasHeld = cursorScrollModifierHeld
                    val wasUsed = cursorScrollModifierUsed
                    val downAt = cursorScrollModifierDownAt
                    cursorScrollModifierHeld = false
                    cursorScrollModifierUsed = false
                    cursorScrollModifierDownAt = 0L
                    mouseController?.stopCursorScroll()
                    if (wasHeld && !wasUsed) {
                        val duration = SystemClock.uptimeMillis() - downAt
                        if (duration < ViewConfiguration.getLongPressTimeout()) {
                            // 短按 5 键且未触发过组合滚动：保留原有 5 号热区跳转
                            jumpCursorToZone(5)
                        }
                    }
                    return true
                }
            }

            // 2. 修饰键按住期间的方向键：驱动光标定向滚动（光标保持在原地不移动）
            if (cursorScrollModifierHeld && dir != KeyphoneShellOverlay.DIR_NONE) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    cursorScrollModifierUsed = true
                    cursorScrollDirectionKeyCode = event.keyCode
                    mouseController?.onCursorScrollDown(dir)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    if (cursorScrollDirectionKeyCode == event.keyCode) {
                        cursorScrollDirectionKeyCode = KeyEvent.KEYCODE_UNKNOWN
                    }
                    mouseController?.onCursorScrollUp(dir)
                }
                return true
            }

            // 3. 5 键已松开但方向键刚才由定向滚动消费了 DOWN，其随后的 UP 安全闭环消费
            if (cursorScrollDirectionKeyCode == event.keyCode && event.action == KeyEvent.ACTION_UP) {
                cursorScrollDirectionKeyCode = KeyEvent.KEYCODE_UNKNOWN
                if (dir != KeyphoneShellOverlay.DIR_NONE) {
                    mouseController?.onCursorScrollUp(dir)
                }
                return true
            }

            // 4. 普通方向键：移动虚拟光标（微移/加速/贴边）
            if (dir != KeyphoneShellOverlay.DIR_NONE) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    mouseController?.onDirectionDown(dir)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    mouseController?.onDirectionUp(dir)
                }
                return true
            }

            // 确认键：点击光标所在位置（所见即所点，同 foucs）。DOWN/UP 转为原生
            // 触摸事件派发给 WebView，由 WebView 自行识别单击/长按；过滤 repeat
            val isConfirmKey = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_5 ||
                event.keyCode == KeyEvent.KEYCODE_BUTTON_A
            if (isConfirmKey) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) {
                        dispatchCursorTouch(MotionEvent.ACTION_DOWN)
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    dispatchCursorTouch(MotionEvent.ACTION_UP)
                }
                return true
            }

            // 1~9 热区跳转：光标平滑移动到对应九宫格的角部/中心位置（只移动不点击，落点由确认键触发）
            val digit = keyDigitOf(event.keyCode)
            if (digit in 1..9) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    jumpCursorToZone(digit)
                }
                return true
            }
        }

        // UC 经典快捷键 0：循环切换标签页
        val isKey0 = event.keyCode == KeyEvent.KEYCODE_0 || event.keyCode == KeyEvent.KEYCODE_NUMPAD_0
        if (isKey0) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                lastConsumedDownKeyCode = event.keyCode
                if (event.repeatCount == 0 && !shouldSwallowDuplicatedActionKey(event.keyCode)) {
                    cycleNextTab()
                }
            }
            return true
        }

        // UC 经典快捷键 *：弹出页面/窗口操作菜单（刷新已并入菜单第 3 项；菜单打开时再按 * 关闭当前窗口）
        val isKeyStar = event.keyCode == KeyEvent.KEYCODE_STAR || event.keyCode == KeyEvent.KEYCODE_NUMPAD_MULTIPLY
        if (isKeyStar) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                lastConsumedDownKeyCode = event.keyCode
                if (event.repeatCount == 0 && !shouldSwallowDuplicatedActionKey(event.keyCode)) {
                    showPageMenu()
                }
            }
            return true
        }

        // UC 经典快捷键 #：弹出全局工具/常用动作菜单（全屏模式下同样弹出菜单）
        val isKeyPound = event.keyCode == KeyEvent.KEYCODE_POUND
        if (isKeyPound) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                lastConsumedDownKeyCode = event.keyCode
                if (event.repeatCount == 0 && !shouldSwallowDuplicatedActionKey(event.keyCode)) {
                    showGlobalMenu()
                }
            }
            return true
        }

        // 主页状态下的确认键短按/长按分发
        if (isHomeSelected) {
            val isCenterKey = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    event.keyCode == KeyEvent.KEYCODE_ENTER ||
                    event.keyCode == KeyEvent.KEYCODE_NUMPAD_5 ||
                    event.keyCode == KeyEvent.KEYCODE_BUTTON_A

            if (isCenterKey) {
                val helper = focusHelper
                val focused = helper?.focusedView
                val isBookmark = focused?.tag is Bookmark.Entry

                if (isBookmarkEditMode) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        val bookmark = focused?.tag as? Bookmark.Entry
                        if (bookmark != null) {
                            deleteBookmark(bookmark)
                        }
                    }
                    return true
                }

                if (isBookmark) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (event.repeatCount == 0) {
                            isSelectLongPressed = false
                            selectLongPressJob?.cancel()
                            selectLongPressJob = lifecycleScope.launch {
                                delay(ViewConfiguration.getLongPressTimeout().toLong())
                                isSelectLongPressed = true
                                enterBookmarkEditMode()
                            }
                        }
                        return true
                    } else if (event.action == KeyEvent.ACTION_UP) {
                        selectLongPressJob?.cancel()
                        if (isSelectLongPressed) {
                            isSelectLongPressed = false
                        } else {
                            activateHomeItem(focused)
                        }
                        return true
                    }
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onAction(action: Int): Boolean {
        if (getCurrentPage() != null) {
            return super.onAction(action)
        }

        if (activeMenuType != QuickMenuType.NONE) {
            return when (action) {
                KeydroidxKeyAction.UP -> {
                    val items = getActiveMenuItems()
                    if (items.isNotEmpty()) {
                        quickMenuFocusIndex = (quickMenuFocusIndex - 1 + items.size) % items.size
                        shellOverlay?.updateMenuFocus(quickMenuFocusIndex)
                    }
                    true
                }
                KeydroidxKeyAction.DOWN -> {
                    val items = getActiveMenuItems()
                    if (items.isNotEmpty()) {
                        quickMenuFocusIndex = (quickMenuFocusIndex + 1) % items.size
                        shellOverlay?.updateMenuFocus(quickMenuFocusIndex)
                    }
                    true
                }
                KeydroidxKeyAction.SELECT, KeydroidxKeyAction.SOFT_LEFT -> {
                    val items = getActiveMenuItems()
                    if (quickMenuFocusIndex in items.indices) {
                        hideQuickMenu()
                        items[quickMenuFocusIndex].action()
                    }
                    true
                }
                KeydroidxKeyAction.SOFT_RIGHT, KeydroidxKeyAction.RIGHT -> {
                    hideQuickMenu()
                    true
                }
                else -> true
            }
        }

        val input = addressInput
        if (input != null && input.hasFocus()) {
            return when (action) {
                KeydroidxKeyAction.SELECT -> {
                    submitAddress()
                    true
                }
                KeydroidxKeyAction.SOFT_RIGHT -> {
                    input.clearFocus()
                    applyAddressBar()
                    true
                }
                KeydroidxKeyAction.SOFT_LEFT -> {
                    openOptions()
                    true
                }
                else -> false
            }
        }

        if (isFindMode) {
            return when (action) {
                KeydroidxKeyAction.UP, KeydroidxKeyAction.LEFT, KeydroidxKeyAction.SOFT_LEFT -> {
                    findPreviousMatch()
                    true
                }
                KeydroidxKeyAction.DOWN, KeydroidxKeyAction.RIGHT, KeydroidxKeyAction.SELECT -> {
                    findNextMatch()
                    true
                }
                KeydroidxKeyAction.SOFT_RIGHT -> {
                    exitFindMode()
                    true
                }
                else -> super.onAction(action)
            }
        }

        if (isFreeCopyMode) {
            return when (action) {
                KeydroidxKeyAction.UP -> {
                    moveFreeCopyCaret("up")
                    true
                }
                KeydroidxKeyAction.DOWN -> {
                    moveFreeCopyCaret("down")
                    true
                }
                KeydroidxKeyAction.LEFT -> {
                    moveFreeCopyCaret("left")
                    true
                }
                KeydroidxKeyAction.RIGHT -> {
                    moveFreeCopyCaret("right")
                    true
                }
                KeydroidxKeyAction.SELECT -> {
                    if (!isCopySelecting) {
                        startFreeCopySelect()
                    } else {
                        performFreeCopy()
                    }
                    true
                }
                KeydroidxKeyAction.SOFT_LEFT -> {
                    if (isCopySelecting) {
                        resetFreeCopySelect()
                    }
                    true
                }
                KeydroidxKeyAction.SOFT_RIGHT -> {
                    exitFreeCopyMode()
                    true
                }
                else -> super.onAction(action)
            }
        }

        if (isCalibrateMode) {
            return when (action) {
                KeydroidxKeyAction.UP -> {
                    tapDirection(KeyphoneShellOverlay.DIR_UP)
                    true
                }
                KeydroidxKeyAction.DOWN -> {
                    tapDirection(KeyphoneShellOverlay.DIR_DOWN)
                    true
                }
                KeydroidxKeyAction.LEFT -> {
                    tapDirection(KeyphoneShellOverlay.DIR_LEFT)
                    true
                }
                KeydroidxKeyAction.RIGHT -> {
                    tapDirection(KeyphoneShellOverlay.DIR_RIGHT)
                    true
                }
                KeydroidxKeyAction.SELECT -> {
                    if (calibratingDigit in 1..9) {
                        saveCalibratePoint()
                    }
                    true
                }
                KeydroidxKeyAction.SOFT_LEFT -> {
                    resetCalibrateCursor()
                    true
                }
                KeydroidxKeyAction.SOFT_RIGHT -> {
                    exitCalibrateMode()
                    true
                }
                else -> true
            }
        }

        if (isHomeSelected) {
            val helper = focusHelper ?: return super.onAction(action)
            if (isBookmarkEditMode) {
                return when (action) {
                    KeydroidxKeyAction.UP, KeydroidxKeyAction.DOWN,
                    KeydroidxKeyAction.LEFT, KeydroidxKeyAction.RIGHT -> moveHomeFocus(action)
                    KeydroidxKeyAction.SELECT -> {
                        val focused = helper.focusedView
                        val bookmark = focused?.tag as? Bookmark.Entry
                        if (bookmark != null) {
                            deleteBookmark(bookmark)
                        }
                        true
                    }
                    KeydroidxKeyAction.SOFT_RIGHT -> {
                        exitBookmarkEditMode()
                        true
                    }
                    KeydroidxKeyAction.SOFT_LEFT -> true
                    else -> super.onAction(action)
                }
            }

            return when (action) {
                KeydroidxKeyAction.UP, KeydroidxKeyAction.DOWN,
                KeydroidxKeyAction.LEFT, KeydroidxKeyAction.RIGHT -> moveHomeFocus(action)
                KeydroidxKeyAction.SELECT -> {
                    activateHomeItem(helper.focusedView)
                    true
                }
                KeydroidxKeyAction.SOFT_LEFT -> {
                    openOptions()
                    true
                }
                KeydroidxKeyAction.SOFT_RIGHT -> {
                    confirmExit()
                    true
                }
                else -> super.onAction(action)
            }
        }

        return when (action) {
            KeydroidxKeyAction.UP -> scrollPage(up = true)
            KeydroidxKeyAction.DOWN -> scrollPage(up = false)
            KeydroidxKeyAction.SOFT_LEFT -> {
                openOptions()
                true
            }
            KeydroidxKeyAction.SOFT_RIGHT -> {
                handleBack()
                true
            }
            else -> super.onAction(action)
        }
    }

    private fun moveHomeFocus(action: Int): Boolean {
        val helper = focusHelper ?: return false
        val index = helper.focusIndex
        if (gridCount > 0 && index >= gridStart && index < gridStart + gridCount) {
            val position = index - gridStart
            val column = position % GRID_COLUMNS
            when (action) {
                KeydroidxKeyAction.LEFT -> {
                    if (column > 0) helper.setFocusIndex(index - 1)
                    return true
                }
                KeydroidxKeyAction.RIGHT -> {
                    if (column < GRID_COLUMNS - 1 && position + 1 < gridCount) {
                        helper.setFocusIndex(index + 1)
                    }
                    return true
                }
                KeydroidxKeyAction.UP -> {
                    if (position - GRID_COLUMNS >= 0) {
                        helper.setFocusIndex(index - GRID_COLUMNS)
                    } else {
                        if (gridStart - 1 >= 0) helper.setFocusIndex(gridStart - 1)
                    }
                    return true
                }
                KeydroidxKeyAction.DOWN -> {
                    if (position + GRID_COLUMNS < gridCount) {
                        helper.setFocusIndex(index + GRID_COLUMNS)
                    } else {
                        val target = gridStart + gridCount
                        if (target < helper.itemCount) helper.setFocusIndex(target)
                    }
                    return true
                }
            }
        }
        return when (action) {
            KeydroidxKeyAction.UP, KeydroidxKeyAction.DOWN -> helper.onDirection(action)
            KeydroidxKeyAction.LEFT, KeydroidxKeyAction.RIGHT -> true
            else -> false
        }
    }

    /** 鼠标模式是否生效：网页态且无快捷菜单/页内查找/自由复制等模态覆盖 */
    private fun isMouseModeActive(): Boolean {
        return !isHomeSelected &&
            activeMenuType == QuickMenuType.NONE &&
            !isFindMode &&
            !isFreeCopyMode &&
            !isCalibrateMode &&
            // 页面 Fragment（历史记录/标签页列表等）打开期间光标必须让位：
            // 方向键要驱动列表焦点，光标叠在列表上只会误导（看起来像在用光标操作）
            getCurrentPage() == null &&
            // 阅读模式：方向键改为整屏翻页，光标层隐藏，避免出现第二个滚动源
            !isReadingMode()
    }

    /** 刷新鼠标光标可见性：仅网页态显示，回主页/弹菜单/进查找复制态时隐藏并停连发 */
    private fun updateMouseCursorVisibility() {
        val cursor = cursorView ?: return
        if (isMouseModeActive() || isCalibrateMode) {
            mouseController?.ensurePositionValid()
            cursor.visibility = View.VISIBLE
        } else {
            if (mouseTouchDown) {
                dispatchCursorTouch(MotionEvent.ACTION_UP)
            }
            mouseController?.stopRepeat()
            resetCursorScrollModifierState()
            cancelWebInputProbe()
            cursor.visibility = View.GONE
        }
    }

    /** 复位按住 5 键组合滚动状态机 */
    private fun resetCursorScrollModifierState() {
        cursorScrollModifierHeld = false
        cursorScrollModifierUsed = false
        cursorScrollModifierDownAt = 0L
        cursorScrollDirectionKeyCode = KeyEvent.KEYCODE_UNKNOWN
        mouseController?.stopCursorScroll()
    }

    /**
     * 注入光标滚动脚本（幂等）：贴边滚动的目标由脚本按光标位置判定
     * （侧边抽屉 / 弹层滚自己，绝不去滚背后的整页）。
     *
     * 键 = 标签 id + URL：页面导航后自动重新注入；同一页重复调用是空操作。
     */
    private fun ensureCursorScrollModule(webView: WebView) {
        // 页面无响应时不碰 WebView：跨进程调用会把 UI 线程堵在等渲染进程上
        if (isRendererLikelyBusy()) return
        val key = "${selectedTabId() ?: -1}:${webView.url.orEmpty()}"
        if (key == scrollModuleKey) return
        try {
            traceMain("注入光标滚动脚本(${keyphoneScroll.provideJs().length}B)") {
                webView.evaluateJavascript(keyphoneScroll.provideJs(), null)
            }
            scrollModuleKey = key
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "注入光标滚动脚本失败: $key", t)
        }
    }

    private var mouseGestureDownTime = 0L

    /**
     * 把确认键转换为光标位置的原生触摸事件派发给 WebView：
     * DOWN/UP 配对（坐标即所见），严格对齐 downTime，由 WebView 自行识别单击/长按。
     */
    private fun dispatchCursorTouch(action: Int) {
        val controller = mouseController ?: return
        val webView = currentWebView() ?: return
        try {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    if (mouseTouchDown) return
                    val x = controller.posX
                    val y = controller.posY
                    if (x < 0 || y < 0) return
                    cursorView?.setPressedState(true)
                    try {
                        window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    } catch (_: Throwable) {}
                    val now = SystemClock.uptimeMillis()
                    mouseGestureDownTime = now
                    val down = MotionEvent.obtain(
                        now, now, MotionEvent.ACTION_DOWN,
                        x.toFloat(), y.toFloat(), 0
                    )
                    try {
                        traceMain("光标触摸派发 DOWN") { webView.dispatchTouchEvent(down) }
                    } finally {
                        down.recycle()
                    }
                    mouseTouchDown = true
                }
                MotionEvent.ACTION_UP -> {
                    cursorView?.setPressedState(false)
                    if (!mouseTouchDown) return
                    val now = SystemClock.uptimeMillis()
                    // 确保 DOWN 到 UP 至少保留 45ms 的有效接触时长，符合移动端标准 Tap 识别阈值
                    val eventTime = if (now - mouseGestureDownTime < 45L) mouseGestureDownTime + 45L else now
                    val up = MotionEvent.obtain(
                        mouseGestureDownTime, eventTime, MotionEvent.ACTION_UP,
                        controller.posX.toFloat(), controller.posY.toFloat(), 0
                    )
                    try {
                        traceMain("光标触摸派发 UP") { webView.dispatchTouchEvent(up) }
                    } finally {
                        up.recycle()
                    }
                    mouseTouchDown = false
                    scheduleWebInputProbe()
                }
            }
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "光标触摸派发失败 action=$action", t)
            cursorView?.setPressedState(false)
            mouseTouchDown = false
        }
    }

    // region 网页输入劫持（点中输入框 → SDK 输入页 → JS 回填）

    /**
     * 幂等安装的 JS 模块：capture 阶段监听 focusin/click，记录最近点中的可编辑元素
     * （input 按类型过滤，排除按钮类与 disabled/readonly）；poll() 消费一次并回传
     * JSON {focus,value,multiline,submit}；fill(text,submit) 写回并派发 input/change
     * 事件，submit=true 时模拟 Enter 键 + 触发表单提交（对应触屏 IME 的「搜索」action 键）。
     */
    private val webInputInstallJs = """
        (function() {
            if (!window.__keyphone_input__) {
                window.__keyphone_input__ = {
                    installed: false, el: null, pending: false,
                    editableOf: function(t) {
                        if (!t || t.nodeType !== 1 || !t.closest) return null;
                        var el = t.closest('textarea, input, [contenteditable="true"], [contenteditable="plaintext-only"]');
                        if (!el || el.disabled || el.readOnly) return null;
                        if (el.tagName === 'INPUT') {
                            var ty = (el.getAttribute('type') || 'text').toLowerCase();
                            var ok = ['text','search','email','url','tel','password','number','date','month','week','time','datetime-local'];
                            if (ok.indexOf(ty) < 0) return null;
                        }
                        return el;
                    },
                    onTarget: function(t) {
                        var el = this.editableOf(t);
                        if (el) { this.el = el; this.pending = true; }
                    },
                    init: function() {
                        if (this.installed) return;
                        var self = this;
                        document.addEventListener('focusin', function(e) { self.onTarget(e.target); }, true);
                        document.addEventListener('click', function(e) { self.onTarget(e.target); }, true);
                        this.installed = true;
                    },
                    poll: function() {
                        var p = this.pending; this.pending = false;
                        if (!p || !this.el || !document.contains(this.el)) return JSON.stringify({focus:false});
                        var el = this.el;
                        var isField = (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA');
                        var v = isField ? (el.value || '') : (el.innerText || '');
                        var ml = (el.tagName === 'TEXTAREA') || (el.isContentEditable === true);
                        var sub = this.shouldSubmit(el);
                        return JSON.stringify({focus:true, value:v, multiline:ml, submit:sub});
                    },
                    // 对齐 Chromium 的 IME action 推断：enterkeyhint / type=search / 表单隐式提交条件
                    shouldSubmit: function(el) {
                        try {
                            var hint = ((el.getAttribute && el.getAttribute('enterkeyhint')) || '').toLowerCase();
                            if (hint === 'search' || hint === 'go' || hint === 'send') return true;
                            if (hint) return false;
                            if (el.tagName === 'INPUT' && (el.getAttribute('type') || 'text').toLowerCase() === 'search') return true;
                            // textarea / contenteditable 的 Enter 是换行，不做表单隐式提交
                            if (el.tagName !== 'INPUT') return false;
                            var form = el.form;
                            if (!form) return false;
                            // form 内只有一个文本类输入（HTML 隐式提交条件）→ Enter 即提交
                            var fields = form.querySelectorAll('input, textarea');
                            var textTypes = ['text','search','email','url','tel','password','number','date','month','week','time','datetime-local'];
                            var textCount = 0;
                            for (var i = 0; i < fields.length; i++) {
                                var f = fields[i];
                                if (f.disabled || f.readOnly) continue;
                                var ty = (f.tagName === 'TEXTAREA') ? 'textarea' : (f.getAttribute('type') || 'text').toLowerCase();
                                if (textTypes.indexOf(ty) < 0) continue;
                                textCount++;
                                if (textCount > 1) return false;
                            }
                            return textCount === 1;
                        } catch (e) { return false; }
                    },
                    fill: function(text, submit) {
                        var el = this.el;
                        if (!el || !document.contains(el)) return JSON.stringify({success:false});
                        try {
                            if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') { el.value = text; }
                            else { el.textContent = text; }
                            el.dispatchEvent(new Event('input', {bubbles:true}));
                            el.dispatchEvent(new Event('change', {bubbles:true}));
                            if (submit) { this.submitEnter(el); }
                            return JSON.stringify({success:true});
                        } catch (e) { return JSON.stringify({success:false}); }
                    },
                    // 模拟真实 Enter：keydown 若被页面 preventDefault（SPA 自行处理无刷新搜索）则到此为止；
                    // 否则补齐 keypress/keyup 并触发 form 提交（requestSubmit 优先，老 WebView 走事件+submit() 兜底）
                    submitEnter: function(el) {
                        var kd = new KeyboardEvent('keydown', {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true, cancelable:true});
                        el.dispatchEvent(kd);
                        if (kd.defaultPrevented) return;
                        el.dispatchEvent(new KeyboardEvent('keypress', {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true, cancelable:true}));
                        el.dispatchEvent(new KeyboardEvent('keyup', {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true, cancelable:false}));
                        var form = el.form || (el.closest && el.closest('form'));
                        if (form) {
                            if (typeof form.requestSubmit === 'function') { form.requestSubmit(); }
                            else {
                                var se = new Event('submit', {bubbles:true, cancelable:true});
                                form.dispatchEvent(se);
                                if (!se.defaultPrevented && form.submit) { form.submit(); }
                            }
                        }
                    }
                };
            }
            window.__keyphone_input__.init();
        })();
    """.trimIndent()

    private val webInputProbeJs =
        "window.__keyphone_input__ ? window.__keyphone_input__.poll() : '{\"focus\":false}';"

    private val webInputProbeRunnable = Runnable { probeWebInput() }

    /** 确认键抬起后调用：延迟探测光标点击是否落在网页可编辑元素上 */
    private fun scheduleWebInputProbe() {
        inputProbeHandler.removeCallbacksAndMessages(null)
        webInputProbeAttempt = 0
        inputProbeHandler.postDelayed(webInputProbeRunnable, WEB_INPUT_PROBE_FIRST_MS)
    }

    private fun cancelWebInputProbe() {
        inputProbeHandler.removeCallbacksAndMessages(null)
        webInputProbeAttempt = 0
        webInputPendingSubmit = false
    }

    private fun probeWebInput() {
        val webView = currentWebView() ?: return
        // 渲染进程繁忙时不发 evaluateJavascript：跨进程调用会同步阻塞主线程
        if (isRendererLikelyBusy()) {
            KeydroidxLog.d(TAG, "输入探测跳过（渲染进程可能繁忙）attempt=$webInputProbeAttempt")
            return
        }
        webInputProbeAttempt++
        traceMain("输入探测 evaluateJavascript") {
            webView.evaluateJavascript(webInputProbeJs) { result ->
                val parsed = parseWebInputProbe(result)
                if (parsed != null) {
                    webInputPendingSubmit = parsed.submit
                    openWebInputEditor(parsed.value, parsed.multiline)
                } else if (webInputProbeAttempt < WEB_INPUT_PROBE_MAX_ATTEMPTS) {
                    inputProbeHandler.postDelayed(webInputProbeRunnable, WEB_INPUT_PROBE_RETRY_MS)
                }
            }
        }
    }

    /** 解析 poll() 的返回值；focus=false 或解析失败返回 null */
    private fun parseWebInputProbe(result: String?): WebInputProbe? {
        if (result.isNullOrBlank() || result == "null") return null
        return try {
            // evaluateJavascript 会把 JS 字符串结果再包一层 JSON 引号，先剥掉
            val stripped = JSONTokener(result).nextValue()
            val json = when (stripped) {
                is String -> JSONObject(stripped)
                is JSONObject -> stripped
                else -> return null
            }
            if (json.optBoolean("focus", false)) {
                WebInputProbe(
                    value = json.optString("value", ""),
                    multiline = json.optBoolean("multiline", false),
                    submit = json.optBoolean("submit", false)
                )
            } else {
                null
            }
        } catch (t: JSONException) {
            KeydroidxLog.e(TAG, "解析网页输入探测结果失败: $result", t)
            null
        }
    }

    /** 打开 SDK 输入页（预填当前值；textarea/contenteditable 开多行） */
    private fun openWebInputEditor(value: String, multiline: Boolean) {
        if (getCurrentPage() != null) return
        if (isFindMode || isFreeCopyMode || activeMenuType != QuickMenuType.NONE) return
        val fragment = KeydroidxTextInputFragment.newInstance(
            getString(R.string.keyphone_web_input_title),
            value,
            null,
            multiline,
            0
        )
        fragment.setOnConfirmListener { text -> fillWebInput(text, webInputPendingSubmit) }
        openFragment(fragment)
    }

    /**
     * 确认后把文本写回网页输入框并派发 input/change 事件（页面脚本可感知）；
     * submit=true 时 JS 侧继续模拟 Enter 键并触发表单提交（搜索框等场景）
     */
    private fun fillWebInput(text: String, submit: Boolean) {
        val webView = currentWebView() ?: return
        webInputPendingSubmit = false
        try {
            val js = "window.__keyphone_input__ && window.__keyphone_input__.fill(" +
                JSONObject.quote(text) + "," + submit + ");"
            webView.evaluateJavascript(js, null)
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "回填网页输入失败", t)
        }
    }

    // endregion

    // region 1~9 热区跳转（九宫格角部偏移定位）

    /**
     * 按 1~9：光标平滑跳到对应落点（只移动不点击，落点由确认键触发）。
     * 落点取 [zonePoint]：站点已标定用自定义比例，否则九宫格默认位置。
     */
    private fun jumpCursorToZone(zone: Int) {
        val controller = mouseController ?: return
        val host = browserFrameHost ?: return
        val w = host.width
        val h = host.height
        if (w <= 0 || h <= 0) return
        val point = zonePoint(zone) ?: return
        controller.animateCursorTo(Math.round(w * point.first), Math.round(h * point.second))
    }

    /**
     * 某键的目标落点（宿主宽高的比例坐标）：
     * 当前网址标定过该键则用自定义落点；否则九宫格默认位置——格子内偏向本键对应的角落：
     * 左右列在格内取 25%/75% 宽处，顶/底行取 25%/75% 高处，中间行列取格中心。
     * 顶栏按钮贴视口顶角、底栏按钮贴视口底角（如起点中文网顶栏左右菜单
     * 与底栏「菜单/书友圈/红按钮」），此偏移普遍能落到真实按钮上；纯比例计算，天然适配各种分辨率。
     */
    private fun zonePoint(zone: Int): Pair<Float, Float>? {
        val host = browserFrameHost ?: return null
        val w = host.width
        val h = host.height
        if (w <= 0 || h <= 0) return null
        // 站点专属键位优先：本次标定的内存映射（保存即生效）；
        // 标定中锁定所选层级（getExact），不被上层（更泛）配置覆盖，重置后即默认九宫格；
        // 日常跳转才按候选键从最具体到最泛做层级回退
        calibratePoints[zone]?.let { return it }
        if (isCalibrateMode) {
            KeyphoneSiteKeyStore.getExact(this, calibrateSiteKey, zone)?.let { return it }
        } else {
            KeyphoneSiteKeyStore.get(
                this,
                KeyphoneSiteKeyStore.candidatesOf(currentWebView()?.url),
                zone
            )?.let { return it }
        }
        return defaultZonePoint(zone, w, h)
    }

    /** 九宫格默认落点（比例坐标）：格子内偏向本键对应角落，中间行列取格中心 */
    private fun defaultZonePoint(zone: Int, w: Int, h: Int): Pair<Float, Float> {
        val col = (zone - 1) % 3
        val row = (zone - 1) / 3
        val fx = if (col == 0) 0.25f else if (col == 2) 0.75f else 0.5f
        val fy = if (row == 0) 0.25f else if (row == 2) 0.75f else 0.5f
        val tx = w * col / 3f + w / 3f * fx
        val ty = h * row / 3f + h / 3f * fy
        return tx / w to ty / h
    }

    /** 键值 → 数字 1~9（Numpad 5 保留为确认键，映射为 0）；非数字键返回 0 */
    private fun keyDigitOf(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> 1
        KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> 2
        KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> 3
        KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> 4
        KeyEvent.KEYCODE_5 -> 5
        KeyEvent.KEYCODE_NUMPAD_5 -> 0
        KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> 6
        KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> 7
        KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> 8
        KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> 9
        else -> 0
    }

    // endregion

    // region 专属按键适配（按网址自定义 1~9 键位）

    /** 进入标定模式：以选中的候选站点键为存取维度，光标进入自由移动状态 */
    private fun startSiteKeyCalibrate(siteKey: String) {
        if (isHomeSelected || siteKey.isEmpty()) {
            Toast.makeText(this, R.string.keyphone_need_page, Toast.LENGTH_SHORT).show()
            return
        }
        calibrateSiteKey = siteKey
        calibratingDigit = 0
        calibratePoints.clear()
        isCalibrateMode = true
        backPressedCallback.isEnabled = true
        // 地址栏槽位等高替换为提示条（不新增行高，避免布局位移影响标定）
        addressInput?.visibility = View.GONE
        calibrateStatusText?.visibility = View.VISIBLE
        updateCalibrateStatus(getString(R.string.keyphone_calibrate_hint))
        mouseController?.ensurePositionValid()
        updateMouseCursorVisibility()
        setSoftKeys(
            getString(R.string.keyphone_soft_reset),
            null,
            getString(R.string.keyphone_soft_back)
        )
        if (isFullScreenMode) {
            findViewById<View>(CommonR.id.bottomPanel)?.visibility = View.VISIBLE
        }
    }

    /** 选中要标定的数字键：光标先跳到该键落点（已标定用已存值，否则默认九宫格），再由方向键微调 */
    private fun beginCalibrateDigit(digit: Int) {
        calibratingDigit = digit
        mouseController?.ensurePositionValid()
        jumpCursorToZone(digit)
        updateCalibrateStatus(getString(R.string.keyphone_calibrate_digit, digit))
    }

    /** 把当前光标位置保存为 [calibratingDigit] 键在该网址下的落点（比例坐标，实时落盘） */
    private fun saveCalibratePoint() {
        val digit = calibratingDigit
        val controller = mouseController ?: return
        val host = browserFrameHost ?: return
        if (digit !in 1..9 || host.width <= 0 || host.height <= 0) return
        val fx = (controller.posX / host.width.toFloat()).coerceIn(0f, 1f)
        val fy = (controller.posY / host.height.toFloat()).coerceIn(0f, 1f)
        calibratePoints[digit] = fx to fy
        KeyphoneSiteKeyStore.put(this, calibrateSiteKey, digit, fx, fy)
        KeydroidxLog.d(
            TAG,
            "专属键位保存 digit=$digit siteKey=$calibrateSiteKey fx=$fx fy=$fy"
        )
        calibratingDigit = 0
        updateCalibrateStatus(
            getString(
                R.string.keyphone_calibrate_saved,
                digit,
                Math.round(fx * 100),
                Math.round(fy * 100)
            )
        )
    }

    /** 完成标定：立即生效（后续 1~9 跳转优先读自定义落点） */
    private fun exitCalibrateMode() {
        if (!isCalibrateMode) return
        // 收尾：还在标定中的键，右软键退出的瞬间自动保存
        if (calibratingDigit in 1..9) {
            saveCalibratePoint()
        }
        isCalibrateMode = false
        calibratingDigit = 0
        calibrateSiteKey = ""
        calibratePoints.clear()
        // 恢复地址栏
        calibrateStatusText?.visibility = View.GONE
        addressInput?.visibility = View.VISIBLE
        applyAddressBar()
        backPressedCallback.isEnabled =
            (activeMenuType != QuickMenuType.NONE || isBookmarkEditMode)
        updateMouseCursorVisibility()
        Toast.makeText(this, R.string.keyphone_calibrate_done, Toast.LENGTH_SHORT).show()
        if (isHomeSelected) {
            updateHomeSoftKeys()
        } else {
            updateWebSoftKeys()
        }
        if (isFullScreenMode && activeMenuType == QuickMenuType.NONE) {
            findViewById<View>(CommonR.id.bottomPanel)?.visibility = View.GONE
        }
    }

    /**
     * 左软键「重置」：把**全部 1~9 键**在所选站点键（[calibrateSiteKey]）上的落点
     * 一次性写回默认九宫格比例（内存+磁盘），显式遮蔽上层（更泛层级）的旧配置——
     * 只改本层，不动其他层级。光标跳回当前标定键的默认位（未选键则回中心）
     */
    private fun resetCalibrateCursor() {
        val host = browserFrameHost ?: return
        val w = host.width
        val h = host.height
        if (w <= 0 || h <= 0) return
        for (digit in 1..9) {
            val point = defaultZonePoint(digit, w, h)
            calibratePoints[digit] = point
            KeyphoneSiteKeyStore.put(this, calibrateSiteKey, digit, point.first, point.second)
        }
        KeydroidxLog.d(TAG, "重置键位 siteKey=$calibrateSiteKey -> 1~9 全部默认")
        if (calibratingDigit in 1..9) {
            jumpCursorToDefaultZone(calibratingDigit)
        } else {
            mouseController?.ensurePositionValid()
        }
        updateCalibrateStatus(getString(R.string.keyphone_calibrate_hint))
    }

    private fun jumpCursorToDefaultZone(zone: Int) {
        val controller = mouseController ?: return
        val host = browserFrameHost ?: return
        val w = host.width
        val h = host.height
        if (w <= 0 || h <= 0) return
        val point = defaultZonePoint(zone, w, h)
        controller.animateCursorTo(Math.round(w * point.first), Math.round(h * point.second))
    }

    private fun updateCalibrateStatus(text: String) {
        calibrateStatusText?.text = text
    }

    /** 软键路径的单步移动：按下即抬（无连发语义） */
    private fun tapDirection(dir: Int) {
        val controller = mouseController ?: return
        controller.onDirectionDown(dir)
        controller.onDirectionUp(dir)
    }

    // endregion

    private fun scrollPage(up: Boolean): Boolean {
        val webView = currentWebView() ?: return false
        return try {
            if (up) webView.pageUp(false) else webView.pageDown(false)
            true
        } catch (t: Throwable) {
            KeydroidxLog.w(TAG, "页面翻页失败", t)
            true
        }
    }

    private fun handleBack() {
        if (isFindMode) {
            exitFindMode()
            return
        }
        if (isFreeCopyMode) {
            exitFreeCopyMode()
            return
        }
        if (presenter.state.value.isBackEnabled) {
            presenter.onEvent(BrowserUiEvent.BackClick)
        } else {
            switchToHome()
        }
    }

    // endregion

    // region 菜单

    private fun openOptions() {
        try {
            optionsLauncher.launch(
                Intent(this, KeyphoneOptionsActivity::class.java)
                    .putExtra(KeyphoneOptionsActivity.EXTRA_PAGE_MODE, !isHomeSelected)
            )
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "打开选项页失败", t)
        }
    }

    private fun onPageMenuAction(action: String) {
        when (action) {
            KeyphoneOptionsActivity.ACTION_NEW_TAB -> switchToHome()
            KeyphoneOptionsActivity.ACTION_NEW_INCOGNITO_TAB ->
                menuClick(MenuSelection.NEW_INCOGNITO_TAB)
            // 统一走 refreshCurrentPage：阅读态下它会退出阅读模式并回到真实章节页，
            // 直接 RefreshOrStopClick 只会重放 data 外壳
            KeyphoneOptionsActivity.ACTION_REFRESH -> refreshCurrentPage()
            KeyphoneOptionsActivity.ACTION_STOP -> presenter.onEvent(BrowserUiEvent.RefreshOrStopClick)
            KeyphoneOptionsActivity.ACTION_SHARE -> menuClick(MenuSelection.SHARE)
            KeyphoneOptionsActivity.ACTION_COPY_LINK -> menuClick(MenuSelection.COPY_LINK)
            KeyphoneOptionsActivity.ACTION_ADD_HOME -> addCurrentPageToHome()
            KeyphoneOptionsActivity.ACTION_HISTORY -> showHistoryPage()
            KeyphoneOptionsActivity.ACTION_DOWNLOADS -> menuClick(MenuSelection.DOWNLOADS)
            KeyphoneOptionsActivity.ACTION_SHOW_TABS -> showTabsPage()
            KeyphoneOptionsActivity.ACTION_CLEAR_TABS -> confirmClearAllTabs()
            KeyphoneOptionsActivity.ACTION_SETTINGS -> menuClick(MenuSelection.SETTINGS)
            KeyphoneOptionsActivity.ACTION_EXIT -> confirmExit()
            else -> KeydroidxLog.w(TAG, "未知的页面菜单动作: $action")
        }
    }

    private fun menuClick(selection: MenuSelection) {
        if (selection == MenuSelection.HISTORY || selection == MenuSelection.DOWNLOADS) {
            isHomeSelected = false
            isScrolledToBottom = false
            updateTabMode()
        }
        presenter.onEvent(BrowserUiEvent.MenuClick(selection))
    }

    /** 黑屏排查：阅读模式下的帧心跳（见 [installReadingFrameWatchdog]） */
    private val readingFrameWatchdogHandler = Handler(Looper.getMainLooper())

    private val readingFrameWatchdogTick = object : Runnable {
        override fun run() {
            readingFrameWatchdogHandler.postDelayed(this, READER_WATCHDOG_INTERVAL_MS)
            try {
                if (!isFinishing && !isDestroyed && isReadingMode()) {
                    probeReaderFrame()
                }
            } catch (t: Throwable) {
                KeydroidxLog.e(TAG, "阅读帧心跳异常", t)
            }
        }
    }

    /**
     * 启动阅读模式帧心跳：阅读态下每 3 秒探一次 WebView 是否还在出帧。
     *
     * 「看着看着文字全部消失、只剩背景色」最棘手的一种形态是主线程活着、按键有响应、
     * DOM 也在，但**合成不出帧**——这种时候日志里没有任何异常，只有屏幕是空的。
     * 本心跳只在帧回调真的迟迟不返回时才落一条 ERROR 日志，平时没有任何日志输出。
     */
    private fun installReadingFrameWatchdog() {
        readingFrameWatchdogHandler.removeCallbacks(readingFrameWatchdogTick)
        readingFrameWatchdogHandler.postDelayed(readingFrameWatchdogTick, READER_WATCHDOG_INTERVAL_MS)
    }

    private fun confirmExit() {
        // 不弹确认框，直接退出（清理逻辑由 onDestroy 链路保证）
        finishAffinity()
    }

    // endregion

    private fun currentWebView(): WebView? {
        val frame = if (this::browserFrame.isInitialized) browserFrame else return null
        for (i in 0 until frame.childCount) {
            val child = frame.getChildAt(i)
            if (child is WebView) {
                // 按键机模式：屏蔽 WebView 自身拉起输入法，输入统一走 SDK 输入页
                if (child is ImeBlockableWebView) {
                    child.blockIme = true
                }
                return child
            }
        }
        return null
    }

    override fun showFileChooser(intent: Intent) {
        fileChooserLauncher.launch(intent)
    }

    /**
     * 阅读模式脚本片段。
     *
     * 进展与诊断**同一次** evaluateJavascript 取回：脚本返回 `进展\n\n@@diag@@ <诊断串>`，
     * 解析前先按分隔符把诊断行摘掉（[parseReadingProgress] 只认 http 开头的行，但标题里若出现
     * 换行仍会串行，故显式剥离）。诊断只读，不改变拼页行为，也没有副作用。
     */
    private object ReadingModeJs {

        private const val DIAG_MARK = "@@diag@@"

        /** 视觉现场标记：主题 / 视口 / 阅读器几何（见 [STYLE_FN]） */
        private const val STYLE_MARK = "@@style@@"

        /**
         * 进入阅读模式第 1 步：只装 API（`__keyphone_rm_noenter__`）不渲染，然后在源页面上做
         * 一次只读提取（见 [KeyphoneReadingMode.js] 的 `extract()`）。
         *
         * 返回值是一段 JSON 字符串（evaluateJavascript 会再套一层 JSON 字面量，调用方先 decode）。
         */
        fun extractScript(readerJs: String): String =
            "window.__keyphone_rm_noenter__=true;\n" + readerJs + "\n" + EXTRACT_CALL

        private const val EXTRACT_CALL =
            "(function(){var r=window.__keyphone_rm__;return r&&r.extract?r.extract():null;})()"

        /** 只回传章节轨迹（不改动页面） */
        fun progress(diag: Boolean): String =
            COLLECT_FN + STYLE_FN +
                "(function(){var r=window.__keyphone_rm__;return collect(r, $diag);})()"

        /**
         * 取数函数：拼页进展（每行一章）+ 可选的阅读模式诊断（见 [KeyphoneReadingMode.js] 的 diag()）。
         * 以函数声明写在脚本开头，等价于一个语句后再执行 IIFE，不污染全局变量。
         */
        private const val COLLECT_FN =
            "function collect(r,d){var p=r&&r.progress?r.progress():'';if(!d)return p;" +
                "var g=r&&r.diag?r.diag():'';" +
                "var s='';try{s=styleProbe();}catch(e){s='probe-error:'+e;}" +
                "return p+'\\n$DIAG_MARK '+g+'\\n$STYLE_MARK '+s;}\n"

        /**
         * 视觉现场取数：与「文字消失只剩背景色」直接相关、又不会出现在 logcat 里的状态。
         *
         * - 主题：夜间黑/夜间灰主题本身就是深色底 + 浅灰字，主题被切错时正文会「看起来消失」；
         * - 视口与滚动：阅读器用 `window.scrollTo/scrollBy` 翻页，横向滚走或页面尺寸异常时
         *   内容会被带出可视区，结果就是满屏背景色；
         * - 阅读器根节点与正文的几何：区分「DOM 真的没内容」和「有内容但不在视口里」。
         */
        private const val STYLE_FN =
            "function styleProbe(){var r=window.__keyphone_rm__;" +
                "var t=r&&r.themeIndex?r.themeIndex():-1;" +
                "var d=document.documentElement,b=document.body;" +
                "var bg='?';try{bg=window.getComputedStyle(b).backgroundColor;}catch(e){}" +
                "var st=document.getElementById('__keyrm_style__');" +
                "var m=/background:(#[0-9A-Fa-f]{3,6})/.exec(st?st.textContent:'');" +
                "var root=document.getElementById('__keyrm_root__');" +
                "var rr=root?root.getBoundingClientRect():null;" +
                "return 'theme='+t+' cssBg='+(m?m[1]:'-')+' bodyBg='+bg+" +
                "' scrollX='+Math.round(window.pageXOffset||0)+" +
                "' scrollY='+Math.round(window.pageYOffset||0)+" +
                "' vw='+d.clientWidth+' vh='+d.clientHeight+' docH='+d.scrollHeight+" +
                "' rootTop='+(rr?Math.round(rr.top):'-')+' rootH='+(rr?Math.round(rr.height):'-')+" +
                "' textLen='+(root?String(root.textContent||'').replace(/\\s+/g,'').length:'-');}\n"

        /** 单独取一次视觉现场（进阅读模式前预检用，见 [probeReadingVisuals]） */
        fun styleProbe(): String = STYLE_FN + "(function(){return styleProbe();})()"

        /** 从脚本返回值里拆出（进展, 诊断, 视觉现场）；返回值的引号由调用方先 decode */
        fun split(raw: String): Triple<String, String, String> {
            val diagIndex = raw.indexOf(DIAG_MARK)
            val progressText = if (diagIndex < 0) raw else raw.substring(0, diagIndex)
            val rest = if (diagIndex < 0) "" else raw.substring(diagIndex + DIAG_MARK.length)
            val styleIndex = rest.indexOf(STYLE_MARK)
            val diag = if (styleIndex < 0) rest else rest.substring(0, styleIndex)
            val style = if (styleIndex < 0) "" else rest.substring(styleIndex + STYLE_MARK.length)
            return Triple(progressText.trimEnd('\n'), diag.trim(), style.trim())
        }
    }

    companion object {
        private const val TAG = "KeyphoneBrowser"

        /**
         * 动作键去重窗口：同一 keyCode 在此时间内再次 DOWN，视为上一次按下的重影，直接吞掉。
         * 取值需大于设备连击间隔（实测抖动一般 <150ms），又要小于用户有意的二次按键间隔。
         */
        private const val ACTION_KEY_DEBOUNCE_MS = 400L

        const val EXTRA_SHOW_TABS = "keyphone_show_tabs"

        /** 「回首页」标志（见 [BrowserHomeActivity]）：复用已有实例时显式切到九宫格主页 */
        const val EXTRA_SHOW_HOME = "keyphone_show_home"
        private const val TABS_WAIT_MS = 3000L
        private const val GRID_COLUMNS = 3
        private const val MAX_HISTORY_ROWS = 10
        private const val MAX_INPUT_CHARS = 100

        /**
         * [ensureHomeWindow] 的在途短路窗口：新建主页的事件派发出去后，state 里要过
         * 几百毫秒才出现主页标签，这段时间内重复调用直接忽略。超时是为了兜住事件丢失
         * （否则一旦 onEvent 失败，主页就再也建不出来）。
         */
        private const val HOME_WINDOW_DISPATCH_TIMEOUT = 3000L

        /** 网页 alert：提示条节流间隔（弹窗风暴时避免刷屏，掩盖真正的拦截提示） */
        private const val WEB_ALERT_TOAST_GAP_MS = 1500L

        /** 网页 alert：提示条正文最大字符数（长广告文案只显示开头） */
        private const val WEB_ALERT_MAX_CHARS = 60

        /** 网页 alert：日志正文最大字符数 */
        private const val WEB_ALERT_LOG_CHARS = 200

        /** 网页 prompt：输入页字符上限（0 = 不限，与网页原生 prompt 行为一致） */
        private const val WEB_PROMPT_MAX_CHARS = 0

        /** 底部软键栏中键时间：24 小时制，分钟粒度（对齐整分刷新） */
        private const val BOTTOM_CLOCK_PATTERN = "HH:mm"
        private const val BOTTOM_CLOCK_INTERVAL_MS = 60_000L

        /** 整分定时的小余量，避免恰好卡在边界上被系统判为提前唤醒 */
        private const val BOTTOM_CLOCK_SLACK_MS = 200L

        /** 页面心跳间隔：每 tick 发一次 ping；上一次没回来时不重复发，不会堆积。
         *  加密到 400ms：把「事前预判」窗口压到亚秒级，让光标点击在渲染进程刚卡住时就被拦下 */
        private const val PAGE_BUSY_TICK_MS = 400L

        /** ping 超过此时长仍未回来，即判定「页面繁忙」（驱动软键栏中键提示） */
        private const val PAGE_BUSY_THRESHOLD_MS = 1500L

        /** 事前预判阈值：ping 发出后超过此时长未回，即视为渲染进程很可能繁忙，
         *  在它真正判忙之前就拦下会同步阻塞主线程的调用（dispatchTouchEvent / evaluateJavascript） */
        private const val PAGE_BUSY_PREEMPT_MS = 800L

        /** 心跳脚本：只求值、不改页面（副作用为零） */
        private const val PAGE_BUSY_PING_JS = "1"

        /** 主线程看门狗采样间隔 */
        private const val MAIN_WATCH_INTERVAL_MS = 500L

        /** 主线程连续非空闲多久算异常（早于 5s 的 ANR；实测单次阻塞 5.6s 的典型场景） */
        private const val MAIN_WATCH_MIN_BUSY_MS = 1000L

        /** 抓栈日志的最小间隔，避免刷屏 */
        private const val MAIN_WATCH_LOG_INTERVAL_MS = 1000L

        /** 卡住时最多打印多少帧栈 */
        private const val MAIN_WATCH_STACK_FRAMES = 30

        /** 阅读模式：JS 桥名（须与 KeyphoneReadingMode.js 内 BRIDGE_NAME 保持一致） */
        private const val READING_BRIDGE_NAME = "__keyphoneReadingBridge__"

        /** 阅读模式：退出脚本（等价 window.__keyphone_rm__.exit()） */
        private const val READING_JS_EXIT = "window.__keyphone_rm__ && window.__keyphone_rm__.exit();"

        /** 阅读模式：阅读页是否已经跑起来（见 [verifyReaderBoot]） */
        private const val READER_JS_IS_ACTIVE =
            "(function(){try{var r=window.__keyphone_rm__;return !!(r&&r.isActive&&r.isActive());}" +
                "catch(e){return false;}})()"

        /**
         * 干净阅读页外壳的 viewport（须与 `KeyphoneReadingMode.js` 的 `READER_VIEWPORT` 一致）。
         * 外壳里先摆上，避免脚本执行前页面按 980px 排版再缩放，出现一次闪烁。
         */
        private const val READER_VIEWPORT =
            "width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"

        /** 阅读页就绪检查的首次延时：外壳是本地页面，正常几百毫秒内脚本已经跑完 */
        private const val READER_BOOT_CHECK_MS = 350L

        /** 阅读页就绪检查最多重试次数，之后判定外壳启动失败并导航回章节页 */
        private const val READER_BOOT_RETRIES = 4

        /** 阅读页就绪后的静默窗口：窗口内不做「阅读页丢失」判定（避开局载入/渲染抖动） */
        private const val READER_SETTLE_GUARD_MS = 3000L

        /** 阅读模式：按键 7 轮换背景主题（脚本返回新序号，字号脚本按 Δ 现拼） */
        private const val READING_JS_THEME = "window.__keyphone_rm__ && window.__keyphone_rm__.theme();"

        /** 阅读模式：在页尾追加下一章文字（JS 侧有 loading / ended / turning 守卫） */
        private const val READING_JS_APPEND =
            "window.__keyphone_rm__ && window.__keyphone_rm__.append && window.__keyphone_rm__.append();"

        /** 页尾预读窗口（视口高度 × 该系数，须与 JS 侧 PRE_READ_SCREENS 一致） */
        private const val READING_PRE_READ_RATIO = 1.5f

        /** 阅读模式：退出后补写历史的最小间隔（避开导航刚开始那一瞬的 URL / 进度错位） */
        private const val READING_TRAIL_MIN_GAP_MS = 1000L

        /** 阅读模式：退出后补写历史的延时兜底时间点 */
        private const val READING_TRAIL_REASSERT_DELAY_MS = 3000L

        /** 阅读模式：方向键单次翻页步长（占 WebView 可见高度比例） */
        private const val READING_SCROLL_STEP_RATIO = 0.85f

        /** 「页面正在打开」超时兜底（见 [armPendingOpenWatchdog]）：正常 1.5s 内就能确认 */
        private const val PENDING_OPEN_TIMEOUT_MS = 6000L

        /** 阅读模式现场采样间隔（仅 debug，见 [installReadingWatchdog]） */
        private const val READING_WATCH_INTERVAL_MS = 2000L

        /** 导航超过此时长仍未结束即视为「卡在加载中」，抓一次线程栈 */
        private const val READING_STALL_MS = 8000L

        /** 抓线程栈时每个线程最多保留多少帧 */
        private const val READING_STACK_FRAMES = 20

        /** 帧回调超过此时长未返回即判定「页面不再出帧」（见 [probeReaderFrame]） */
        private const val READER_FRAME_STALL_MS = 3000L

        /** 帧回调停滞超过此时长即触发自动恢复（见 [recoverBrokenReader]）：
         *  远大于 [READER_FRAME_STALL_MS] 的告警阈值，避免把弱机偶发的慢帧当成“合成卡死” */
        private const val READER_FRAME_RECOVER_MS = 15000L

        /** 自动恢复的最短间隔（见 [recoverBrokenReader]）：防止故障现场反复触发开一堆标签 */
        private const val READER_RECOVER_COOLDOWN_MS = 30000L

        /** 「正在打开」遮罩挂超过此时长即判定异常并抓现场（见 [detectBrokenReaderState]） */
        private const val BROKEN_OVERLAY_MS = 10000L

        /** 阅读模式帧心跳间隔（见 [installReadingFrameWatchdog]） */
        private const val READER_WATCHDOG_INTERVAL_MS = 3000L

        /** 网页输入劫持探测参数：确认键抬起后 120ms 首探，失败最多重试 3 次（间隔 300ms） */
        private const val WEB_INPUT_PROBE_FIRST_MS = 120L
        private const val WEB_INPUT_PROBE_RETRY_MS = 300L
        private const val WEB_INPUT_PROBE_MAX_ATTEMPTS = 3

        private val PAYLOAD_SEARCH = Any()
    }
}

