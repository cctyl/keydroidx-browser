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
import acr.browser.lightning.database.Bookmark
import acr.browser.lightning.database.HistoryEntry
import acr.browser.lightning.database.bookmark.BookmarkRepository
import acr.browser.lightning.database.history.HistoryRepository
import acr.browser.lightning.di.injector
import acr.browser.lightning.utils.isSpecialUrl
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
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
 * - 顶部标签栏：横向滚动展示全部标签页及主页，按键 3（或 1）快速循环切换；
 * - 地址/搜索栏：浏览具体网页时展示（可直接输入/前往）；
 * - 加载进度线：2dp 横向拉伸；
 * - 内容区：主页（搜索框+书签宫格+历史记录）与 WebView 容器无缝切换；
 * - 软键栏：菜单/选项 | 动作 | 返回/退出。
 *
 * 按键 3 机制：
 * - 在非输入状态下，按下数字键 3 循环切换下一个标签页（包含主页）；
 * - 按下数字键 1 循环切换上一个标签页。
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

    /** 刷新 UC 风格顶部标签栏与主内容区可见性 */
    private fun updateTabMode() {
        val webTabs = presenter.state.value.tabs
        if (webTabs.isEmpty()) {
            isHomeSelected = true
        }

        // 内容区切换
        if (isHomeSelected) {
            homeScroll?.visibility = View.VISIBLE
            browserFrameHost?.visibility = View.GONE
            addressBarContainer?.visibility = View.GONE
            progressBar?.visibility = View.INVISIBLE
            updateHomeSoftKeys()
        } else {
            homeScroll?.visibility = View.GONE
            browserFrameHost?.visibility = View.VISIBLE
            addressBarContainer?.visibility = View.VISIBLE
            updateWebSoftKeys()
            applyAddressBar()
        }

        renderTabBar()
    }

    /** 渲染顶部标签栏：主页 + 打开的 Web 标签页 */
    private fun renderTabBar() {
        val container = tabBarContainer ?: return
        container.removeAllViews()

        val webTabs = presenter.state.value.tabs
        val items = ArrayList<KeyphoneViews.UcTabItem>()

        // 主页标签
        items.add(
            KeyphoneViews.UcTabItem(
                id = -1,
                title = getString(R.string.keyphone_tab_home),
                isSelected = isHomeSelected
            )
        )

        // Web 标签页
        webTabs.forEach { tab ->
            val title = tab.title.ifBlank { getString(R.string.keyphone_tab_untitled) }
            items.add(
                KeyphoneViews.UcTabItem(
                    id = tab.id,
                    title = title,
                    isSelected = (!isHomeSelected && tab.isSelected)
                )
            )
        }

        var selectedChildView: View? = null

        items.forEachIndexed { index, item ->
            val cell = KeyphoneViews.ucTabCell(this, item) { clickedItem ->
                if (clickedItem.id == -1) {
                    switchToHome()
                } else {
                    val targetIndex = webTabs.indexOfFirst { it.id == clickedItem.id }
                    if (targetIndex >= 0) {
                        switchToTab(targetIndex)
                    }
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

    /** 切换到主页 */
    private fun switchToHome() {
        isHomeSelected = true
        updateTabMode()
        loadHomeData()
    }

    /** 切换到指定 Web 标签页 */
    internal fun switchToTab(index: Int) {
        val webTabs = presenter.state.value.tabs
        if (index in webTabs.indices) {
            isHomeSelected = false
            presenter.onEvent(BrowserUiEvent.TabClick(index))
            updateTabMode()
        }
    }

    /** 按键 3：循环切换下一个标签（主页 -> 标签 1 -> 标签 2 -> 主页） */
    private fun cycleNextTab() {
        val webTabs = presenter.state.value.tabs
        if (webTabs.isEmpty()) {
            return
        }
        if (isHomeSelected) {
            switchToTab(0)
        } else {
            val currentIndex = webTabs.indexOfFirst { it.isSelected }
            if (currentIndex < 0 || currentIndex >= webTabs.size - 1) {
                switchToHome()
            } else {
                switchToTab(currentIndex + 1)
            }
        }
    }

    /** 按键 1：循环切换上一个标签 */
    private fun cyclePrevTab() {
        val webTabs = presenter.state.value.tabs
        if (webTabs.isEmpty()) {
            return
        }
        if (isHomeSelected) {
            switchToTab(webTabs.size - 1)
        } else {
            val currentIndex = webTabs.indexOfFirst { it.isSelected }
            if (currentIndex <= 0) {
                switchToHome()
            } else {
                switchToTab(currentIndex - 1)
            }
        }
    }

    /** 打开网址并自动转入 Web 模式 */
    private fun openUrlInNewTab(url: String) {
        isHomeSelected = false
        presenter.onEvent(
            BrowserUiEvent.NewAction(
                BrowserContract.Action.LoadUrl(KeyphoneUrls.normalize(url))
            )
        )
        updateTabMode()
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
        backPressedCallback.isEnabled = false
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
    }

    private fun updateWebSoftKeys() {
        setSoftKeys(
            getString(R.string.keyphone_soft_menu),
            null,
            getString(R.string.keyphone_soft_back)
        )
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
        tabBarScroll?.visibility = if (pageShown) View.GONE else View.VISIBLE
        if (!pageShown) {
            updateTabMode()
        }
    }

    // endregion

    // region 状态监听

    private fun observeState() {
        lifecycleScope.launch {
            presenter.state.collectLatest { state ->
                addressText = state.displayUrl
                if (!isHomeSelected) {
                    applyAddressBar()
                    applyProgress(state.progress)
                }
                renderTabBar()
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
            switchToHome()
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
        switchToHome()
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

    internal fun goHomeFromTabs() {
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
        // 编辑页 / 选项页等 Fragment 打开时交给基类分发
        if (getCurrentPage() != null) {
            return super.dispatchKeyEvent(event)
        }

        // 地址栏持有焦点时：数字/字母输入正常透传给输入框
        val input = addressInput
        if (input != null && input.hasFocus()) {
            return super.dispatchKeyEvent(event)
        }

        // UC 经典快捷键 3：循环切换标签页
        val isKey3 = event.keyCode == KeyEvent.KEYCODE_3 || event.keyCode == KeyEvent.KEYCODE_NUMPAD_3
        if (isKey3) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                cycleNextTab()
            }
            return true
        }

        // UC 经典快捷键 1：反向切换标签页
        val isKey1 = event.keyCode == KeyEvent.KEYCODE_1 || event.keyCode == KeyEvent.KEYCODE_NUMPAD_1
        if (isKey1) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                cyclePrevTab()
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
