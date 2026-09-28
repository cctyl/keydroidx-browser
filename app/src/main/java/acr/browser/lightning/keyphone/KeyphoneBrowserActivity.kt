package acr.browser.lightning.keyphone

import acr.browser.lightning.BrowserUiEvent
import acr.browser.lightning.R
import acr.browser.lightning.browser.BrowserContract
import io.github.cctyl.nokia.common.R as CommonR
import acr.browser.lightning.browser.BrowserPresenter
import acr.browser.lightning.browser.menu.MenuSelection
import acr.browser.lightning.browser.search.IntentExtractor
import acr.browser.lightning.browser.tab.TabPager
import acr.browser.lightning.browser.tab.TabViewState
import acr.browser.lightning.constant.SCHEME_BLANK
import acr.browser.lightning.constant.SCHEME_HOMEPAGE
import acr.browser.lightning.database.Bookmark
import acr.browser.lightning.database.HistoryEntry
import acr.browser.lightning.database.bookmark.BookmarkRepository
import acr.browser.lightning.database.history.HistoryRepository
import acr.browser.lightning.di.injector
import acr.browser.lightning.preference.UserPreferencesDataStore
import acr.browser.lightning.search.SearchEngineProvider
import acr.browser.lightning.utils.isSpecialUrl
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
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
import javax.inject.Inject

/**
 * 按键机浏览页（UC 风格多标签架构）：
 *
 * 结构：
 * - 顶部标签栏：横向滚动展示全部标签页及主页，按键 3 快速循环切换；
 * - 页面快捷菜单（按键 1 弹出）：面向当前网页/窗口深度操作，按 1~9 直达执行；
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

    /** 顶部标签栏 */
    private var tabBarScroll: HorizontalScrollView? = null
    private var tabBarContainer: LinearLayout? = null

    /** 顶栏地址栏容器与输入框 */
    private var addressBarContainer: LinearLayout? = null
    private var addressInput: EditText? = null
    private var progressBar: View? = null

    /** 主页容器与列表焦点助手 */
    private var homeScroll: ScrollView? = null
    private var homeRowsContainer: LinearLayout? = null
    private var focusHelper: KeydroidxListFocusHelper? = null

    /** Web 宿主容器 */
    private var browserFrameHost: FrameLayout? = null
    private lateinit var browserFrame: FrameLayout

    /** 鼠标模式（仅网页态）：光标绘制层与移动引擎（数值照抄 keydroidx-foucs） */
    private var mouseCursorView: KeyphoneCursorView? = null
    private var mouseController: KeyphoneMouseController? = null

    /** 确认键已向 WebView 派发 DOWN 尚未派发 UP（防止触摸序列悬挂） */
    private var mouseTouchDown = false

    /** UC 风格数字快捷菜单 */
    private enum class QuickMenuType {
        NONE,
        PAGE_MENU,   // 1 键：页面/窗口操作
        GLOBAL_MENU  // # 键：全局工具导航
    }

    private var activeMenuType = QuickMenuType.NONE
    private var quickMenuOverlay: FrameLayout? = null
    private var quickMenuCard: LinearLayout? = null
    private var quickMenuFocusIndex = 0
    private var lastConsumedDownKeyCode: Int = KeyEvent.KEYCODE_UNKNOWN

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
            if (isFindMode) {
                exitFindMode()
                return
            }
            if (isFreeCopyMode) {
                exitFreeCopyMode()
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

        progressBar = findViewById<View>(R.id.keyphone_progress)?.apply { pivotX = 0f }

        homeScroll = findViewById(R.id.keyphone_home_scroll)
        homeRowsContainer = findViewById(R.id.keyphone_home_rows)
        focusHelper = homeScroll?.let { KeydroidxListFocusHelper(it) }

        browserFrameHost = findViewById(R.id.keyphone_browser_frame_host)
        browserFrameHost?.addView(
            browserFrame,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // 鼠标光标层：盖在 WebView 宿主之上、快捷菜单浮层之下，随网页态显隐
        val cursorView = KeyphoneCursorView(this).apply { visibility = View.GONE }
        browserFrameHost?.addView(
            cursorView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        mouseCursorView = cursorView
        mouseController = KeyphoneMouseController(
            host = browserFrameHost!!,
            cursorView = cursorView,
            webViewProvider = { currentWebView() }
        )

        quickMenuOverlay = findViewById(R.id.keyphone_quick_menu_overlay)
        quickMenuCard = findViewById(R.id.keyphone_quick_menu_card)
        val cardBg = GradientDrawable().apply {
            cornerRadius = KeydroidxDimens.dp(resources, 4f).toFloat()
            setColor(0xF01E2836.toInt())
            setStroke(KeydroidxDimens.dp(resources, 1f), 0xFF3D516B.toInt())
        }
        quickMenuCard?.background = cardBg
        quickMenuOverlay?.setOnClickListener { hideQuickMenu() }

        updateTabMode()
        loadHomeData()
    }

    override fun onResume() {
        super.onResume()
        presenter.onViewShown()
        if (isHomeSelected) {
            loadHomeData()
        }
    }

    override fun onPause() {
        super.onPause()
        selectLongPressJob?.cancel()
        if (mouseTouchDown) {
            dispatchCursorTouch(MotionEvent.ACTION_UP)
        }
        mouseController?.stopRepeat()
        presenter.onViewHidden()
    }

    override fun onDestroy() {
        super.onDestroy()
        presenter.onViewDetached()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intentExtractor.extractUrlFromIntent(intent)?.let { action ->
            isHomeSelected = false
            presenter.onEvent(BrowserUiEvent.NewAction(action))
            updateTabMode()
        }
        if (intent.getBooleanExtra(EXTRA_SHOW_TABS, false)) {
            showTabsPage()
        }
    }

    // region 顶部标签栏与模式切换

    /** 判断指定标签页是否处于主页空白状态 */
    private fun isTabHome(tab: TabViewState?): Boolean {
        if (tab == null) return true
        val url = tab.url.trim()
        return url.isBlank() || url.isSpecialUrl() || url.startsWith("about:") || url.startsWith("chrome://")
    }

    /** 刷新 UC 风格顶部标签栏与主内容区可见性 */
    private fun updateTabMode() {
        val currentTab = presenter.state.value.tabs.firstOrNull { it.isSelected }
        isHomeSelected = (currentTab == null || isTabHome(currentTab))

        // 内容区切换
        if (isHomeSelected) {
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
            progressBar?.visibility = View.INVISIBLE
            if (activeMenuType == QuickMenuType.NONE) {
                updateHomeSoftKeys()
            }
        } else {
            homeScroll?.visibility = View.GONE
            browserFrameHost?.visibility = View.VISIBLE
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
        updateMouseCursorVisibility()
    }

    /** 渲染顶部标签栏：所有打开的标签页（主页 / 各网页） */
    private fun renderTabBar() {
        val container = tabBarContainer ?: return
        container.removeAllViews()

        val tabs = presenter.state.value.tabs
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
            createNewWindow()
        }
    }

    /** 新建一个主页窗口（在顶栏新建一个标签页，底层 WebView 保持空白静止不发起网络请求） */
    private fun createNewWindow() {
        isScrolledToBottom = false
        presenter.onEvent(BrowserUiEvent.NewAction(BrowserContract.Action.LoadUrl("")))
        loadHomeData()
    }

    /** 切换到指定 Web 标签页 */
    internal fun switchToTab(index: Int) {
        val tabs = presenter.state.value.tabs
        if (index in tabs.indices) {
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

    /** 按键 3：循环切换下一个标签页 */
    private fun cycleNextTab() {
        val tabs = presenter.state.value.tabs
        if (tabs.size <= 1) return
        val currentIndex = tabs.indexOfFirst { it.isSelected }
        val nextIndex = if (currentIndex < 0 || currentIndex >= tabs.size - 1) 0 else currentIndex + 1
        switchToTab(nextIndex)
    }

    /** 打开网址：若当前标签是主页空白页直接在当前标签加载，否则新建标签页加载 */
    private fun openUrlInNewTab(url: String) {
        if (isFindMode) {
            exitFindMode()
        }
        if (isFreeCopyMode) {
            exitFreeCopyMode()
        }
        lifecycleScope.launch {
            try {
                val normalizedUrl = KeyphoneUrls.normalize(url, searchEngineProvider)
                isScrolledToBottom = false
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
            } catch (t: Throwable) {
                KeydroidxLog.e(TAG, "打开网址失败: $url", t)
            }
        }
    }

    // endregion

    // region 快捷菜单（1 键页面菜单与 # 键全局菜单）

    private var cachedMenuItems: List<KeyphoneViews.QuickMenuItem> = emptyList()

    /** 按键 1 页面/窗口上下文菜单条目（1~9 项） */
    private fun buildPageMenuItems(): List<KeyphoneViews.QuickMenuItem> {
        return listOf(
            KeyphoneViews.QuickMenuItem("1", getString(R.string.keyphone_qm_close_tab)) { closeCurrentTabOrExit() },
            KeyphoneViews.QuickMenuItem("2", getString(R.string.keyphone_qm_new_tab)) { createNewWindow() },
            KeyphoneViews.QuickMenuItem("3", getString(R.string.keyphone_qm_refresh)) { refreshCurrentPage() },
            KeyphoneViews.QuickMenuItem("4", getString(R.string.keyphone_qm_add_bookmark)) { addCurrentPageToHome() },
            KeyphoneViews.QuickMenuItem("5", getString(R.string.keyphone_qm_copy_link)) { copyCurrentPageLink() },
            KeyphoneViews.QuickMenuItem("6", getString(R.string.keyphone_qm_share)) { shareCurrentPage() },
            KeyphoneViews.QuickMenuItem("7", getString(R.string.keyphone_qm_find)) { findInCurrentPage() },
            KeyphoneViews.QuickMenuItem("8", getString(R.string.keyphone_qm_tabs)) { showTabsPage() },
            KeyphoneViews.QuickMenuItem("9", getString(R.string.keyphone_qm_clear_tabs)) { confirmClearAllTabs() }
        )
    }

    /** 按键 # 全局快捷工具菜单条目（1~6、*、# 项） */
    private fun buildGlobalMenuItems(): List<KeyphoneViews.QuickMenuItem> {
        return listOf(
            KeyphoneViews.QuickMenuItem("1", getString(R.string.keyphone_pm_input_url)) { openSearchEditor() },
            KeyphoneViews.QuickMenuItem("2", getString(R.string.keyphone_pm_search)) { openSearchEditor() },
            KeyphoneViews.QuickMenuItem("3", getString(R.string.keyphone_pm_bookmarks)) { menuClick(MenuSelection.HISTORY) },
            KeyphoneViews.QuickMenuItem("4", getString(R.string.keyphone_pm_downloads)) { menuClick(MenuSelection.DOWNLOADS) },
            KeyphoneViews.QuickMenuItem("5", getString(R.string.keyphone_pm_scroll_top_bottom)) { toggleScrollTopBottom() },
            KeyphoneViews.QuickMenuItem("6", getString(R.string.keyphone_pm_free_copy)) { startFreeCopyMode() },
            KeyphoneViews.QuickMenuItem("*", getString(R.string.keyphone_pm_toggle_images)) { toggleBlockImages() },
            KeyphoneViews.QuickMenuItem("#", getString(R.string.keyphone_pm_toggle_fullscreen)) { toggleFullScreen() }
        )
    }

    private fun getActiveMenuItems(): List<KeyphoneViews.QuickMenuItem> = cachedMenuItems

    private fun showPageMenu() {
        showMenu(QuickMenuType.PAGE_MENU)
    }

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
            QuickMenuType.NONE -> emptyList()
        }
        backPressedCallback.isEnabled = true
        quickMenuFocusIndex = 0
        quickMenuOverlay?.visibility = View.VISIBLE
        renderQuickMenu()
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
        backPressedCallback.isEnabled = (isBookmarkEditMode || isFindMode || isFreeCopyMode)
        quickMenuOverlay?.visibility = View.GONE
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

    private fun renderQuickMenu() {
        val card = quickMenuCard ?: return
        card.removeAllViews()
        val items = getActiveMenuItems()
        items.forEachIndexed { index, item ->
            val isFocused = (index == quickMenuFocusIndex)
            val row = KeyphoneViews.quickMenuItem(this, item, isFocused) {
                hideQuickMenu()
                item.action()
            }
            card.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun closeCurrentTabOrExit() {
        if (isFindMode) {
            exitFindMode()
        }
        if (isFreeCopyMode) {
            exitFreeCopyMode()
        }
        val tabs = presenter.state.value.tabs
        val currentIndex = tabs.indexOfFirst { it.isSelected }

        if (tabs.size > 1 && currentIndex >= 0) {
            closeTab(currentIndex)
        } else {
            confirmExit()
        }
    }

    private fun refreshCurrentPage() {
        if (isHomeSelected) {
            loadHomeData()
        } else {
            presenter.onEvent(BrowserUiEvent.RefreshOrStopClick)
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
        backPressedCallback.isEnabled = (activeMenuType != QuickMenuType.NONE || isBookmarkEditMode)

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
        backPressedCallback.isEnabled = (activeMenuType != QuickMenuType.NONE || isBookmarkEditMode || isFindMode)

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
                currentWebView()?.settings?.blockNetworkImage = newBlocked
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
    }

    // endregion

    // region 状态监听

    private fun observeState() {
        lifecycleScope.launch {
            presenter.state.collectLatest { state ->
                addressText = state.displayUrl
                if (state.tabs.isEmpty()) {
                    isHomeSelected = true
                }
                updateTabMode()
                if (!isHomeSelected) {
                    applyAddressBar()
                    applyProgress(state.progress)
                }
            }
        }
    }

    private fun applyProgress(progress: Int) {
        val bar = progressBar ?: return
        if (progress in 1..99 && !isHomeSelected) {
            bar.visibility = View.VISIBLE
            bar.scaleX = progress / 100f
        } else {
            bar.visibility = View.INVISIBLE
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

    internal fun tabsSnapshot(): List<TabViewState> = presenter.state.value.tabs

    internal fun tabsFlow(): Flow<List<Int>> = presenter.state
        .map { state -> state.tabs.map { if (it.isSelected) -it.id else it.id } }
        .distinctUntilChanged()

    internal fun closeTab(index: Int) {
        presenter.onEvent(BrowserUiEvent.TabClose(index))
        if (presenter.state.value.tabs.isEmpty()) {
            createNewWindow()
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
        presenter.onEvent(
            BrowserUiEvent.CloseBrowser(currentId, BrowserContract.CloseTabEvent.CLOSE_ALL)
        )
        Toast.makeText(this, R.string.keyphone_tabs_cleared, Toast.LENGTH_SHORT).show()
        createNewWindow()
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

    internal fun onAllTabsClosed() {
        if (isFinishing || isDestroyed) return
        createNewWindow()
    }

    internal fun goHomeFromTabs() {
        supportFragmentManager.popBackStack()
        switchToHome()
    }

    internal fun closeTabsPage() {
        supportFragmentManager.popBackStack()
    }

    private fun addCurrentPageToHome() {
        val url = currentWebView()?.url.orEmpty()
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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
                    val items = getActiveMenuItems()
                    val matchedItem = items.firstOrNull { it.keyLabel == keyLabel }
                    if (matchedItem != null) {
                        hideQuickMenu()
                        matchedItem.action()
                    } else if (keyLabel == "#" && activeMenuType == QuickMenuType.PAGE_MENU) {
                        // 在 1 键菜单打开时按 # 直接切到全局 # 菜单
                        showGlobalMenu()
                    } else if (keyLabel == "1" && activeMenuType == QuickMenuType.GLOBAL_MENU) {
                        // 在 # 键菜单中按 1 直接切到页面 1 菜单
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
                            renderQuickMenu()
                        }
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (isFirstDown) {
                        val items = getActiveMenuItems()
                        if (items.isNotEmpty()) {
                            quickMenuFocusIndex = (quickMenuFocusIndex + 1) % items.size
                            renderQuickMenu()
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

        // 网页态鼠标模式：方向键驱动光标移动（数值与连发节奏照抄 keydroidx-foucs）；
        // 消费了 DOWN 就必须消费同键 UP，防止抬起事件穿透
        if (isMouseModeActive()) {
            val dir = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> KeyphoneCursorView.DIR_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> KeyphoneCursorView.DIR_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> KeyphoneCursorView.DIR_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> KeyphoneCursorView.DIR_RIGHT
                else -> KeyphoneCursorView.DIR_NONE
            }
            if (dir != KeyphoneCursorView.DIR_NONE) {
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
        }

        // UC 经典快捷键 0：回到主页
        val isKey0 = event.keyCode == KeyEvent.KEYCODE_0 || event.keyCode == KeyEvent.KEYCODE_NUMPAD_0
        if (isKey0) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                lastConsumedDownKeyCode = event.keyCode
                if (event.repeatCount == 0) {
                    switchToHome()
                }
            }
            return true
        }

        // UC 经典快捷键 *：刷新当前页面
        val isKeyStar = event.keyCode == KeyEvent.KEYCODE_STAR || event.keyCode == KeyEvent.KEYCODE_NUMPAD_MULTIPLY
        if (isKeyStar) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                lastConsumedDownKeyCode = event.keyCode
                if (event.repeatCount == 0) {
                    refreshCurrentPage()
                }
            }
            return true
        }

        // UC 经典快捷键 1：弹出页面/窗口操作菜单
        val isKey1 = event.keyCode == KeyEvent.KEYCODE_1 || event.keyCode == KeyEvent.KEYCODE_NUMPAD_1
        if (isKey1) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                lastConsumedDownKeyCode = event.keyCode
                if (event.repeatCount == 0) {
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
                if (event.repeatCount == 0) {
                    showGlobalMenu()
                }
            }
            return true
        }

        // UC 经典快捷键 3：循环切换标签页
        val isKey3 = event.keyCode == KeyEvent.KEYCODE_3 || event.keyCode == KeyEvent.KEYCODE_NUMPAD_3
        if (isKey3) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                lastConsumedDownKeyCode = event.keyCode
                if (event.repeatCount == 0) {
                    cycleNextTab()
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
                        renderQuickMenu()
                    }
                    true
                }
                KeydroidxKeyAction.DOWN -> {
                    val items = getActiveMenuItems()
                    if (items.isNotEmpty()) {
                        quickMenuFocusIndex = (quickMenuFocusIndex + 1) % items.size
                        renderQuickMenu()
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
            !isFreeCopyMode
    }

    /** 刷新鼠标光标可见性：仅网页态显示，回主页/弹菜单/进查找复制态时隐藏并停连发 */
    private fun updateMouseCursorVisibility() {
        val cursor = mouseCursorView ?: return
        if (isMouseModeActive()) {
            mouseController?.ensurePositionValid()
            if (cursor.visibility != View.VISIBLE) {
                cursor.visibility = View.VISIBLE
            }
        } else {
            if (mouseTouchDown) {
                dispatchCursorTouch(MotionEvent.ACTION_UP)
            }
            mouseController?.stopRepeat()
            if (cursor.visibility != View.GONE) {
                cursor.visibility = View.GONE
            }
        }
    }

    /**
     * 把确认键转换为光标位置的原生触摸事件派发给 WebView：
     * DOWN/UP 配对（坐标即所见），由 WebView 自行识别单击/长按。
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
                    val now = SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(
                        now, now, MotionEvent.ACTION_DOWN,
                        x.toFloat(), y.toFloat(), 0
                    )
                    try {
                        webView.dispatchTouchEvent(down)
                    } finally {
                        down.recycle()
                    }
                    mouseTouchDown = true
                }
                MotionEvent.ACTION_UP -> {
                    if (!mouseTouchDown) return
                    val now = SystemClock.uptimeMillis()
                    val up = MotionEvent.obtain(
                        now, now, MotionEvent.ACTION_UP,
                        controller.posX.toFloat(), controller.posY.toFloat(), 0
                    )
                    try {
                        webView.dispatchTouchEvent(up)
                    } finally {
                        up.recycle()
                    }
                    mouseTouchDown = false
                }
            }
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "光标触摸派发失败 action=$action", t)
            mouseTouchDown = false
        }
    }

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
            KeyphoneOptionsActivity.ACTION_REFRESH,
            KeyphoneOptionsActivity.ACTION_STOP -> presenter.onEvent(BrowserUiEvent.RefreshOrStopClick)
            KeyphoneOptionsActivity.ACTION_SHARE -> menuClick(MenuSelection.SHARE)
            KeyphoneOptionsActivity.ACTION_COPY_LINK -> menuClick(MenuSelection.COPY_LINK)
            KeyphoneOptionsActivity.ACTION_ADD_HOME -> addCurrentPageToHome()
            KeyphoneOptionsActivity.ACTION_HISTORY -> menuClick(MenuSelection.HISTORY)
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

    private fun confirmExit() {
        KeydroidxConfirmDialog(
            this,
            getString(R.string.keyphone_exit_title),
            getString(R.string.keyphone_exit_message)
        )
            .setPositiveButton(getString(R.string.keyphone_exit_positive)) { finishAffinity() }
            .setNegativeButton(getString(R.string.keyphone_soft_cancel), null)
            .show()
    }

    // endregion

    private fun currentWebView(): WebView? {
        val frame = if (this::browserFrame.isInitialized) browserFrame else return null
        for (i in 0 until frame.childCount) {
            val child = frame.getChildAt(i)
            if (child is WebView) {
                return child
            }
        }
        return null
    }

    override fun showFileChooser(intent: Intent) {
        fileChooserLauncher.launch(intent)
    }

    companion object {
        private const val TAG = "KeyphoneBrowser"
        const val EXTRA_SHOW_TABS = "keyphone_show_tabs"
        private const val TABS_WAIT_MS = 3000L
        private const val GRID_COLUMNS = 3
        private const val MAX_HISTORY_ROWS = 10
        private const val MAX_INPUT_CHARS = 100
        private val PAYLOAD_SEARCH = Any()
    }
}
