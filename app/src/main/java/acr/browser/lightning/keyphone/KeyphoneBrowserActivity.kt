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
import acr.browser.lightning.database.bookmark.BookmarkRepository
import acr.browser.lightning.di.injector
import acr.browser.lightning.utils.isSpecialUrl
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import io.github.cctyl.nokia.common.log.KeydroidxLog
import io.github.cctyl.nokia.common.model.KeydroidxKeyAction
import io.github.cctyl.nokia.common.ui.dialog.KeydroidxConfirmDialog
import io.github.cctyl.nokia.keycore.ui.KeydroidxBaseActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * 按键机浏览页（打开具体网页后的界面，四区规范）：
 *
 * ```
 * 顶栏      [ 可输入地址栏 ]                信号/电量   ← 普通浏览器地址栏（EditText，可直接输入）
 * 内容区    [ 2dp 加载进度线 ]
 *           <WebView>                                   ← 上游 WebViewFactory + pool 池化产出
 * 软键栏    菜单 |        |  返回                        ← 菜单栏（LSK 进选项页）
 * ```
 *
 * 复用上游全部浏览内核（`BrowserPresenter` / `TabsRepository` / `TabPager` / `WebViewFactory`），
 * 只把 **界面外壳** 换成按键机四区布局：上游 `BrowserActivity` 的 Compose 界面（触屏）不参与本页。
 *
 * 按键映射（浏览模式下内容区无焦点，方向键由本页主动驱动 WebView 翻页）：
 * - UP / DOWN：页面上 / 下翻页；
 * - CENTER：**点击**，本页不占用，透传给内容区（`WebView`）；
 * - LSK（菜单）：进入全屏选项页 `KeyphoneOptionsActivity`（页面组动作回传本页执行）；
 * - RSK / BACK：先按浏览历史后退，无历史时关闭本页返回按键机首页。
 *
 * 菜单里的「新建标签页」= **回按键机首页**（`BrowserHomeActivity`），不新建上游标签页；
 * 按键机这套 UI 没有标签页切换入口，多开标签页只会变成看不见的堆积。
 *
 * 地址栏编辑态（焦点在 EditText 上）：
 * - CENTER：提交并加载输入内容；RSK：退出编辑；LSK：菜单；其余按键（方向 / 数字字母）透传给输入框。
 * 进入编辑态的方式（方向键怎么选中）暂不在本页处理，触屏可直接点击。
 *
 * 红线：继承 keycore `KeydroidxBaseActivity`（#1）、禁写 keyCode（#4）、
 * 弹窗走 `KeydroidxConfirmDialog`（#9）、字号/图标走 core（#12/#13）、异常必落日志。
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

    /** WebView 宿主容器：由 DI 在构造组件时注入 [TabPager]，再挂进 content 布局占位。 */
    private lateinit var browserFrame: FrameLayout

    private var progressBar: View? = null

    /** 顶栏地址输入框（替换骨架里的纯文本标题）。 */
    private var addressInput: EditText? = null

    /** 当前网页应显示的地址文本（来自 presenter.state.displayUrl）。 */
    private var addressText: String = ""

    /** 是否由本页程序化写入地址栏文本（用于区分用户输入）。 */
    private var settingAddressText = false

    /** 用户是否手动改过地址栏内容；改过之后就不再被页面地址覆盖。 */
    private var addressEditedByUser = false

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { presenter.onEvent(BrowserUiEvent.FileChooserResult(it)) }

    /** 选项页（页面模式）回传的动作。 */
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
        // 必须在 super.onCreate 之前构建子组件：onInitViews 里就要把 browserFrame 挂进布局
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

        presenter.onViewAttached(this)
        tabPager.longPressListener = { id, longPress ->
            presenter.onEvent(BrowserUiEvent.PageLongPress(id, longPress))
        }
        // Fragment 页（标签页列表等）进出栈时切换顶栏形态
        supportFragmentManager.addOnBackStackChangedListener { updateTopBar() }
        observeState()

        if (intent?.getBooleanExtra(EXTRA_SHOW_TABS, false) == true) {
            showTabsPage()
        }
    }

    override fun onInitViews() {
        // 顶栏即地址栏：隐藏骨架的纯文本标题与图标，改成可输入的 EditText
        setTitleIcon(null)
        setStatusBarVisible(true)
        registerBatteryReceiver()
        setUpAddressBar()
        updateChrome()

        progressBar = findViewById<View>(R.id.keyphone_progress)?.apply { pivotX = 0f }
        findViewById<FrameLayout>(R.id.keyphone_browser_frame_host)?.addView(
            browserFrame,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    override fun onResume() {
        super.onResume()
        presenter.onViewShown()
    }

    override fun onPause() {
        super.onPause()
        presenter.onViewHidden()
    }

    override fun onDestroy() {
        super.onDestroy()
        presenter.onViewDetached()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intentExtractor.extractUrlFromIntent(intent)?.let {
            presenter.onEvent(BrowserUiEvent.NewAction(it))
        }
        if (intent.getBooleanExtra(EXTRA_SHOW_TABS, false)) {
            showTabsPage()
        }
    }

    // region 标签页列表页

    /** 打开标签页列表页；先等 presenter 初始化出标签页，否则列表会是空的。 */
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

    /** 已打开标签页的快照（供标签页列表页读取）。 */
    internal fun tabsSnapshot(): List<TabViewState> = presenter.state.value.tabs

    /**
     * 标签页列表变化流：仅在「标签页增删 / 顺序 / 选中项」变化时发射
     * （标题、加载进度等高频抖动不触发），供标签页列表页在删除后重建并修正「当前」标记。
     */
    internal fun tabsFlow(): Flow<List<Int>> = presenter.state
        .map { state -> state.tabs.map { if (it.isSelected) -it.id else it.id } }
        .distinctUntilChanged()

    /** 切换到第 [index] 个标签页。 */
    internal fun switchToTab(index: Int) {
        presenter.onEvent(BrowserUiEvent.TabClick(index))
    }

    /**
     * 关闭第 [index] 个标签页。上游 `onTabClose` 会自行处理后续：
     * 删的是当前页 → 切相邻页；删的是最后一个 → 关浏览器。
     */
    internal fun closeTab(index: Int) {
        presenter.onEvent(BrowserUiEvent.TabClose(index))
    }

    /** 除当前页外的标签页全部关闭（对应上游 `CloseTabEvent.CLOSE_OTHERS`）。 */
    internal fun clearOtherTabs() {
        val currentId = presenter.state.value.tabs
            .firstOrNull { it.isSelected }?.id ?: return
        presenter.onEvent(
            BrowserUiEvent.CloseBrowser(currentId, BrowserContract.CloseTabEvent.CLOSE_OTHERS)
        )
    }

    /** 标签页被删光：回按键机首页（首页 singleTask，会清掉本页）。 */
    internal fun goHomeFromTabs() {
        goHome()
    }

    /**
     * 把当前页添加到「首页」（首页书签宫格显示的就是书签）。
     *
     * 首页宫格取 `getAllBookmarksSorted().take(6)`，排序为 `folder, position ASC, title ASC`；
     * 新书签用「当前最小 position - 1」保证排到最前，否则可能挤不进那 6 个格子（表现为"加了看不到"）。
     */
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
                    toast(R.string.keyphone_already_home)
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
                toast(if (added) R.string.keyphone_added_home else R.string.keyphone_already_home)
            } catch (t: Throwable) {
                KeydroidxLog.e(TAG, "添加到首页失败", t)
            }
        }
    }

    private fun toast(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    /** 关闭标签页列表页，回到浏览。 */
    internal fun closeTabsPage() {
        supportFragmentManager.popBackStack()
    }

    /**
     * 顶栏形态：浏览态显示可输入地址栏；有前台 Fragment 页时让位给 `tvPageTitle`。
     * 页面标题与软键由基类 `refreshPageBar()` 从页面契约（`KeydroidxPage`）取，这里只决定显示哪一个。
     */
    private fun updateTopBar() {
        val pageShown = getCurrentPage() != null
        findViewById<TextView>(CommonR.id.tvPageTitle)?.visibility =
            if (pageShown) View.VISIBLE else View.GONE
        addressInput?.visibility = if (pageShown) View.GONE else View.VISIBLE
        if (!pageShown) {
            updateChrome()
        }
    }

    // endregion

    // region 顶栏地址栏

    /**
     * 把基类骨架顶栏（`R.id.topPanel`）里的纯文本标题换成真正的地址输入框：
     * 保留右侧信号 / 电量组，只在图标位与状态组之间插入 EditText。
     */
    private fun setUpAddressBar() {
        val titleBar = findViewById<LinearLayout>(CommonR.id.topPanel) ?: return
        val input = KeyphoneViews.addressBar(this, getString(R.string.keyphone_search_hint))
        val statusGroup = findViewById<View>(CommonR.id.layoutStatusBar)
        val insertAt = if (statusGroup != null) {
            titleBar.indexOfChild(statusGroup)
        } else {
            titleBar.childCount
        }
        // 高度用 WRAP_CONTENT：顶栏在 LinearLayout 里按权重测量，MATCH_PARENT 会把顶栏自然高度撑成整屏
        titleBar.addView(
            input,
            insertAt,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        // 原纯文本标题让位（本页不再用它展示地址）
        findViewById<TextView>(CommonR.id.tvPageTitle)?.visibility = View.GONE

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
            // 进入编辑：给出完整原始网址便于直接改写；离开编辑：还原成展示文本
            setAddressText(if (hasFocus) currentWebView()?.url ?: addressText else addressText)
        }
        // 触屏软键盘「前往」
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

    /** 程序化写入地址栏（光标置末尾，并清除「用户已编辑」标记）。 */
    private fun setAddressText(text: String) {
        val input = addressInput ?: return
        settingAddressText = true
        input.setText(text)
        input.setSelection(input.text?.length ?: 0)
        settingAddressText = false
        addressEditedByUser = false
    }

    /**
     * 刷新地址栏显示。
     *
     * 只在**用户没手动改过**时才用页面地址覆盖：此前用「是否持有焦点」判断，
     * 导致地址栏一旦保持焦点（如从选项页返回后系统恢复焦点）就再也不跟着页面更新，
     * 出现「内容是新页面、地址栏还是旧网址」。
     */
    private fun applyAddressBar() {
        if (getCurrentPage() != null) {
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

    /** 提交地址栏输入：退出编辑并加载（空输入忽略）。 */
    private fun submitAddress() {
        val input = addressInput ?: return
        val text = input.text?.toString()?.trim().orEmpty()
        input.clearFocus()
        if (text.isEmpty()) {
            applyAddressBar()
            return
        }
        presenter.onEvent(
            BrowserUiEvent.NewAction(
                BrowserContract.Action.LoadUrl(KeyphoneUrls.normalize(text))
            )
        )
    }

    // endregion

    // region 状态 → 地址栏 / 进度线

    private fun observeState() {
        lifecycleScope.launch {
            presenter.state.collectLatest { state ->
                addressText = state.displayUrl
                applyAddressBar()
                applyProgress(state.progress)
            }
        }
    }

    private fun applyProgress(progress: Int) {
        val bar = progressBar ?: return
        if (progress in 1..99) {
            bar.visibility = View.VISIBLE
            bar.scaleX = progress / 100f
        } else {
            bar.visibility = View.INVISIBLE
        }
    }

    /** 浏览态的顶栏 / 软键栏（菜单栏）：菜单 | 空 | 返回。 */
    private fun updateChrome() {
        setSoftKeys(
            getString(R.string.keyphone_soft_menu),
            null,
            getString(R.string.keyphone_soft_back)
        )
        applyAddressBar()
    }

    // endregion

    // region 按键

    override fun onAction(action: Int): Boolean {
        // 前台 Fragment 页面打开时，按键交给页面契约（基类按 KeydroidxPage 分发）
        if (getCurrentPage() != null) {
            return super.onAction(action)
        }

        val input = addressInput
        if (input != null && input.hasFocus()) {
            // 地址栏编辑态
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
                // 方向 / 数字 / 字母键透传给 EditText
                else -> false
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
            // CENTER = 点击：本页不占用，交回内容区（WebView）
            else -> super.onAction(action)
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

    /** 后退：历史可退则退一页，否则关闭本页回到按键机首页。 */
    private fun handleBack() {
        if (presenter.state.value.isBackEnabled) {
            presenter.onEvent(BrowserUiEvent.BackClick)
        } else {
            finish()
        }
    }

    // endregion

    // region 菜单

    /** LSK → 全屏选项页；以「页面模式」调起，页面组动作由本页执行。 */
    private fun openOptions() {
        try {
            optionsLauncher.launch(
                Intent(this, KeyphoneOptionsActivity::class.java)
                    .putExtra(KeyphoneOptionsActivity.EXTRA_PAGE_MODE, true)
            )
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "打开选项页失败", t)
        }
    }

    private fun onPageMenuAction(action: String) {
        when (action) {
            // 新建标签页 = 回按键机首页（用户定；首页即"新会话"的起点，不占用上游标签页机制）
            KeyphoneOptionsActivity.ACTION_NEW_TAB -> goHome()
            KeyphoneOptionsActivity.ACTION_NEW_INCOGNITO_TAB ->
                menuClick(MenuSelection.NEW_INCOGNITO_TAB)

            // 刷新 / 停止共用上游的布尔切换：加载中→停止，加载完→重新加载
            KeyphoneOptionsActivity.ACTION_REFRESH,
            KeyphoneOptionsActivity.ACTION_STOP -> presenter.onEvent(BrowserUiEvent.RefreshOrStopClick)

            KeyphoneOptionsActivity.ACTION_SHARE -> menuClick(MenuSelection.SHARE)
            KeyphoneOptionsActivity.ACTION_COPY_LINK -> menuClick(MenuSelection.COPY_LINK)
            // 「添加到首页」= 把当前页存为书签（首页宫格显示的就是书签）。
            // 上游 `ADD_TO_HOME` 是「给系统桌面建快捷方式」，语义不同且本机桌面不支持，故不走它。
            KeyphoneOptionsActivity.ACTION_ADD_HOME -> addCurrentPageToHome()
            KeyphoneOptionsActivity.ACTION_HISTORY -> menuClick(MenuSelection.HISTORY)
            KeyphoneOptionsActivity.ACTION_DOWNLOADS -> menuClick(MenuSelection.DOWNLOADS)
            // 标签页列表页只能由本页 push（标签页是 @BrowserScope，只有浏览页拿得到）
            KeyphoneOptionsActivity.ACTION_SHOW_TABS -> showTabsPage()
            KeyphoneOptionsActivity.ACTION_CLEAR_TABS -> clearOtherTabs()
            KeyphoneOptionsActivity.ACTION_SETTINGS -> menuClick(MenuSelection.SETTINGS)
            KeyphoneOptionsActivity.ACTION_EXIT -> confirmExit()
            else -> KeydroidxLog.w(TAG, "未知的页面菜单动作: $action")
        }
    }

    private fun menuClick(selection: MenuSelection) {
        presenter.onEvent(BrowserUiEvent.MenuClick(selection))
    }

    /** 回到按键机首页（首页为 `singleTask`，会把它之上的本页一并清掉）。 */
    private fun goHome() {
        try {
            startActivity(
                Intent(this, BrowserHomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "返回首页失败", t)
        }
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

    /** 当前标签页的 WebView（由 `TabPager` 直接挂进 [browserFrame]）。 */
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

    /**
     * @see BrowserContract.View.showFileChooser
     */
    override fun showFileChooser(intent: Intent) {
        fileChooserLauncher.launch(intent)
    }

    companion object {
        private const val TAG = "KeyphoneBrowser"

        /** 由「设置 → 标签页列表」入口拉起时置 true：把浏览页带到前台并直接打开标签页列表。 */
        const val EXTRA_SHOW_TABS = "keyphone_show_tabs"

        /** 等 presenter 初始化出标签页的上限，超时就打开列表（显示空态）。 */
        private const val TABS_WAIT_MS = 3000L
    }
}
