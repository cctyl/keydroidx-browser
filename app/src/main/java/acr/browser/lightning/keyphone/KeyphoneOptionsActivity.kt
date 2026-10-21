package acr.browser.lightning.keyphone

import acr.browser.lightning.R
import acr.browser.lightning.constant.SCHEME_BOOKMARKS
import acr.browser.lightning.di.injector
import acr.browser.lightning.settings.SettingsScreenStateProvider
import acr.browser.lightning.settings.framework.ClickableOnClick
import acr.browser.lightning.settings.framework.ClickableState
import acr.browser.lightning.settings.framework.ToggleState
import acr.browser.lightning.settings.navigation.SettingsNavigation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import io.github.cctyl.nokia.common.log.KeydroidxLog
import io.github.cctyl.nokia.common.model.KeydroidxKeyAction
import io.github.cctyl.nokia.common.ui.KeydroidxIcons
import io.github.cctyl.nokia.common.ui.dialog.KeydroidxConfirmDialog
import io.github.cctyl.nokia.common.ui.page.KeydroidxListFocusHelper
import io.github.cctyl.nokia.keycore.ui.KeydroidxBaseActivity
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.runBlocking

/**
 * 按键机全屏选项页（首页按 LSK 进入）。
 *
 * 内容 = 原浏览器右上角 ⋮ 溢出菜单（`browser/compose/BrowserOverflowMenu.kt`）全集，
 * 展开为全屏列表以便滚动承载；另补三项按键机刚需（原菜单没有、但按键机无工具栏按钮）：
 * 刷新页面 / 停止加载 / 退出浏览器。
 *
 * 条目分四组：标签 → 页面 → 收藏与记录 → 系统。两种进入方式：
 *
 * 1. **首页模式**（默认）：首页 LSK 进入，此时没有当前网页，「页面」组统一提示
 *    「请先打开网页后再使用」；
 * 2. **页面模式**（`EXTRA_PAGE_MODE = true`）：按键机浏览页 `KeyphoneBrowserActivity` LSK 进入，
 *    本页只负责「选了什么」，用 `setResult(EXTRA_ACTION)` 把动作回传给浏览页执行
 *    （浏览页持有 `BrowserPresenter`，可对当前标签页操作）。这样选项页无需感知浏览内核。
 *
 * RSK/BACK 返回上一层；「退出浏览器」走 KeydroidxConfirmDialog（红线 #9）。
 */
class KeyphoneOptionsActivity : KeydroidxBaseActivity() {

    @Inject
    internal lateinit var settingsScreenStateProvider: SettingsScreenStateProvider

    private var focusHelper: KeydroidxListFocusHelper? = null

    /** true = 由浏览页以「页面模式」调起，条目动作回传给浏览页执行。 */
    private var pageMode = false

    private enum class ScreenLevel {
        OPTIONS_ROOT,
        SETTINGS_CATEGORY
    }

    private var currentLevel = ScreenLevel.OPTIONS_ROOT
    private var currentCategory: SettingsNavigation? = null
    private var savedFocusIndexOptionsRoot = 0

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            handleBack()
        }
    }

    override fun getContentLayoutRes(): Int = R.layout.keyphone_list

    override fun onCreate(savedInstanceState: Bundle?) {
        injector.settingsComponentBuilder()
            .incognitoMode(false)
            .activity(this)
            .build()
            .inject(this)
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
    }

    override fun onInitViews() {
        pageMode = intent?.getBooleanExtra(EXTRA_PAGE_MODE, false) == true
        renderCurrentScreen()
    }

    private fun renderCurrentScreen(targetFocusIndex: Int? = null) {
        val scroll = findViewById<ScrollView>(R.id.keyphone_scroll) ?: return
        val container = findViewById<LinearLayout>(R.id.keyphone_rows) ?: return
        container.removeAllViews()

        setStatusBarVisible(false)
        setSoftKeys(
            getString(R.string.keyphone_soft_confirm),
            null,
            getString(R.string.keyphone_soft_back)
        )

        val focusables = ArrayList<View>()

        when (currentLevel) {
            ScreenLevel.OPTIONS_ROOT -> {
                setPageTitle(getString(R.string.keyphone_options_title))
                setTitleIcon(KeydroidxIcons.MORE_VERT)

                buildOptions().forEach { entry ->
                    when (entry) {
                        is OptionEntry.Section ->
                            container.addView(KeyphoneViews.sectionLabel(this, getString(entry.titleRes)))

                        OptionEntry.Divider -> container.addView(KeyphoneViews.divider(this))

                        is OptionEntry.Option -> {
                            val row = KeyphoneViews.iconRow(
                                this,
                                entry.iconCode,
                                null,
                                getString(entry.titleRes),
                                null
                            )
                            row.setOnClickListener {
                                focusHelper?.setFocusIndex(focusables.indexOf(row))
                                entry.action()
                            }
                            container.addView(row)
                            focusables.add(row)
                        }
                    }
                }
            }

            ScreenLevel.SETTINGS_CATEGORY -> {
                val category = currentCategory ?: SettingsNavigation.ROOT
                val categoryState = settingsScreenStateProvider.provideState(category)
                setPageTitle(categoryState.title)
                setTitleIcon(KeydroidxIcons.ICON_SETTINGS)

                val tabStyleTitle = getString(R.string.tab_style_title)
                categoryState.content.filter { option ->
                    when (option) {
                        is ClickableState -> option.title != tabStyleTitle
                        is ToggleState -> option.title != tabStyleTitle
                    }
                }.forEach { option ->
                    when (option) {
                        is ToggleState -> {
                            val isChecked = runBlocking { option.isChecked() }
                            val stateTag = if (isChecked) "[已开启]" else "[已关闭]"
                            val summary = runBlocking { option.summary?.invoke() }
                            val fullSummary = if (summary != null) "$stateTag  $summary" else stateTag
                            val icon = if (isChecked) KeydroidxIcons.ICON_CHECK else KeydroidxIcons.ICON_CLOSE
                            val row = KeyphoneViews.iconRow(
                                this,
                                icon,
                                null,
                                option.title,
                                fullSummary
                            )
                            row.setOnClickListener {
                                val focusIdx = focusables.indexOf(row)
                                focusHelper?.setFocusIndex(focusIdx)
                                val snackbar = runBlocking { option.onToggle(!isChecked) }
                                snackbar?.let {
                                    Toast.makeText(this, it.message, Toast.LENGTH_SHORT).show()
                                }
                                renderCurrentScreen(targetFocusIndex = focusIdx)
                            }
                            container.addView(row)
                            focusables.add(row)
                        }

                        is ClickableState -> {
                            val summary = runBlocking { option.summary?.invoke() }
                            val row = KeyphoneViews.iconRow(
                                this,
                                KeydroidxIcons.ICON_CHEVRON_RIGHT,
                                null,
                                option.title,
                                summary
                            )
                            row.setOnClickListener {
                                val focusIdx = focusables.indexOf(row)
                                focusHelper?.setFocusIndex(focusIdx)
                                handleClickable(option.onClick, focusIdx)
                            }
                            container.addView(row)
                            focusables.add(row)
                        }
                    }
                }
            }
        }

        val helper = KeydroidxListFocusHelper(scroll)
        helper.setItems(focusables)
        if (focusables.isNotEmpty()) {
            val desiredIndex = targetFocusIndex?.coerceIn(0, focusables.size - 1) ?: 0
            helper.setFocusIndex(desiredIndex)
        }
        focusHelper = helper
    }

    private fun openSettingsCategory(nav: SettingsNavigation) {
        if (currentLevel == ScreenLevel.OPTIONS_ROOT) {
            savedFocusIndexOptionsRoot = focusHelper?.focusIndex ?: 0
        }
        currentLevel = ScreenLevel.SETTINGS_CATEGORY
        currentCategory = nav
        backPressedCallback.isEnabled = true
        renderCurrentScreen(targetFocusIndex = 0)
    }

    private fun openOptionsRoot() {
        currentLevel = ScreenLevel.OPTIONS_ROOT
        currentCategory = null
        backPressedCallback.isEnabled = false
        renderCurrentScreen(targetFocusIndex = savedFocusIndexOptionsRoot)
    }

    private fun handleClickable(onClick: ClickableOnClick, currentFocusIndex: Int? = null) {
        when (onClick) {
            is ClickableOnClick.Navigate -> {
                openSettingsCategory(onClick.settingsNavigation)
            }

            is ClickableOnClick.Action -> {
                runBlocking { onClick.action() }
                renderCurrentScreen(targetFocusIndex = currentFocusIndex)
            }

            is ClickableOnClick.Snackbar -> {
                val snackbar = runBlocking { onClick.produceState() }
                Toast.makeText(this, snackbar.message, Toast.LENGTH_SHORT).show()
                renderCurrentScreen(targetFocusIndex = currentFocusIndex)
            }

            is ClickableOnClick.WebLink -> {
                try {
                    val intent = Intent(this, KeyphoneBrowserActivity::class.java).apply {
                        data = Uri.parse(onClick.url)
                    }
                    startActivity(intent)
                } catch (t: Throwable) {
                    KeydroidxLog.e(TAG, "打开链接失败: ${onClick.url}", t)
                }
            }

            is ClickableOnClick.Confirmation -> {
                val dialogState = runBlocking { onClick.produceState() }
                KeydroidxConfirmDialog(this, dialogState.title, dialogState.message)
                    .setPositiveButton(dialogState.positiveAction) {
                        val nextAction = runBlocking { onClick.onConfirmed(true) }
                        handleClickable(nextAction, currentFocusIndex)
                        renderCurrentScreen(targetFocusIndex = currentFocusIndex)
                    }
                    .setNegativeButton(dialogState.negativeAction) {
                        val nextAction = runBlocking { onClick.onConfirmed(false) }
                        handleClickable(nextAction, currentFocusIndex)
                    }
                    .show()
            }

            is ClickableOnClick.ItemSelector -> {
                val chooserState = runBlocking { onClick.produceState() }
                val options = chooserState.values.mapIndexed { index, entry ->
                    val isCurrent = index == chooserState.selected
                    val label = (index + 1).let { if (it in 1..9) it.toString() else "*" }
                    val text = if (isCurrent) "${entry.text}  [当前]" else entry.text
                    KeyphoneViews.QuickMenuItem(
                        keyLabel = label,
                        text = text
                    ) {
                        val nextAction = runBlocking { onClick.onSelected(index) }
                        handleClickable(nextAction, currentFocusIndex)
                        renderCurrentScreen(targetFocusIndex = currentFocusIndex)
                    }
                }
                KeyphoneOptionSheetDialog(
                    context = this,
                    titleText = chooserState.title,
                    bodyLines = emptyList(),
                    options = options,
                    defaultFocusIndex = chooserState.selected.coerceIn(0, (options.size - 1).coerceAtLeast(0)),
                    onSelected = {},
                    onCancelled = {}
                ).show()
            }

            is ClickableOnClick.TextSize -> {
                val currentSize = runBlocking { onClick.produceTextSize() }
                val labels = listOf(
                    getString(R.string.size_smallest),
                    getString(R.string.size_small),
                    getString(R.string.size_normal),
                    getString(R.string.size_large),
                    getString(R.string.size_largest),
                )
                val options = labels.mapIndexed { index, label ->
                    val isCurrent = index == currentSize
                    KeyphoneViews.QuickMenuItem(
                        keyLabel = (index + 1).toString(),
                        text = if (isCurrent) "$label  [当前]" else label
                    ) {
                        val nextAction = runBlocking { onClick.onSelected(index) }
                        handleClickable(nextAction, currentFocusIndex)
                        renderCurrentScreen(targetFocusIndex = currentFocusIndex)
                    }
                }
                KeyphoneOptionSheetDialog(
                    context = this,
                    titleText = getString(R.string.title_text_size),
                    bodyLines = emptyList(),
                    options = options,
                    defaultFocusIndex = currentSize.coerceIn(0, (options.size - 1).coerceAtLeast(0)),
                    onSelected = {},
                    onCancelled = {}
                ).show()
            }

            else -> Unit
        }
    }

    /** 选项全集（顺序即展示顺序）。 */
    private fun buildOptions(): List<OptionEntry> =
        if (pageMode) buildPageModeOptions() else buildHomeOptions()

    /** 页面模式全集：全部条目回传浏览页执行（浏览页才持有 BrowserPresenter）。 */
    private fun buildPageModeOptions(): List<OptionEntry> = listOf(
        OptionEntry.Section(R.string.keyphone_section_tabs),
        OptionEntry.Option(KeydroidxIcons.VIEW_LIST, R.string.keyphone_opt_tabs) {
            relay(ACTION_SHOW_TABS)
        },
        OptionEntry.Option(KeydroidxIcons.ICON_DELETE, R.string.keyphone_opt_clear_tabs) {
            relay(ACTION_CLEAR_TABS)
        },

        OptionEntry.Divider,
        OptionEntry.Section(R.string.keyphone_section_records),
        OptionEntry.Option(KeydroidxIcons.ICON_HISTORY, R.string.keyphone_opt_history) {
            relay(ACTION_HISTORY)
        },
        OptionEntry.Option(KeydroidxIcons.ICON_DOWNLOAD, R.string.keyphone_opt_downloads) {
            launchDownloadsPage()
        },

        OptionEntry.Divider,
        OptionEntry.Section(R.string.keyphone_section_system),
        OptionEntry.Option(KeydroidxIcons.ICON_LOCK, R.string.keyphone_opt_guard) {
            launchGuardRules()
        },
        OptionEntry.Option(KeydroidxIcons.ICON_SHIELD, R.string.settings_adblock) {
            openSettingsCategory(SettingsNavigation.ADBLOCK)
        },
        OptionEntry.Option(KeydroidxIcons.ICON_SETTINGS, R.string.settings_general) {
            openSettingsCategory(SettingsNavigation.GENERAL)
        },
        OptionEntry.Option(KeydroidxIcons.ICON_FAVORITE, R.string.bookmark_settings) {
            openSettingsCategory(SettingsNavigation.BOOKMARK)
        },
        OptionEntry.Option(KeydroidxIcons.ICON_DISPLAY, R.string.settings_display) {
            openSettingsCategory(SettingsNavigation.DISPLAY)
        },
        OptionEntry.Option(KeydroidxIcons.ICON_LOCK, R.string.settings_privacy) {
            openSettingsCategory(SettingsNavigation.PRIVACY)
        },
        OptionEntry.Option(KeydroidxIcons.ICON_ADVANCED, R.string.settings_advanced) {
            openSettingsCategory(SettingsNavigation.ADVANCED)
        },
        OptionEntry.Option(KeydroidxIcons.ICON_INFO, R.string.settings_about) {
            openSettingsCategory(SettingsNavigation.ABOUT)
        },
        OptionEntry.Option(KeydroidxIcons.ICON_HELP, R.string.keyphone_opt_help) { launchHelp() },
        OptionEntry.Option(KeydroidxIcons.ICON_POWER, R.string.keyphone_opt_exit) {
            relay(ACTION_EXIT)
        }
    )

    /** 把选中的动作回传给浏览页并结束本页。 */
    private fun relay(action: String) {
        setResult(android.app.Activity.RESULT_OK, Intent().putExtra(EXTRA_ACTION, action))
        finish()
    }

    /** 首页模式全集。 */
    private fun buildHomeOptions(): List<OptionEntry> = listOf(
        OptionEntry.Section(R.string.keyphone_section_tabs),
        OptionEntry.Option(KeydroidxIcons.VIEW_LIST, R.string.keyphone_opt_tabs) { launchTabsList() },
        OptionEntry.Option(KeydroidxIcons.ICON_DELETE, R.string.keyphone_opt_clear_tabs) { confirmClearTabsFromHome() },

        OptionEntry.Divider,
        OptionEntry.Section(R.string.keyphone_section_records),
        OptionEntry.Option(KeydroidxIcons.ICON_HISTORY, R.string.keyphone_opt_history) {
            launchHistoryPage()
        },
        OptionEntry.Option(KeydroidxIcons.ICON_DOWNLOAD, R.string.keyphone_opt_downloads) {
            launchDownloadsPage()
        },

        OptionEntry.Divider,
        OptionEntry.Section(R.string.keyphone_section_system),
        OptionEntry.Option(KeydroidxIcons.ICON_LOCK, R.string.keyphone_opt_guard) { launchGuardRules() },
        OptionEntry.Option(KeydroidxIcons.ICON_SHIELD, R.string.settings_adblock) {
            openSettingsCategory(SettingsNavigation.ADBLOCK)
        },
        OptionEntry.Option(KeydroidxIcons.ICON_SETTINGS, R.string.settings_general) {
            openSettingsCategory(SettingsNavigation.GENERAL)
        },
        OptionEntry.Option(KeydroidxIcons.ICON_FAVORITE, R.string.bookmark_settings) {
            openSettingsCategory(SettingsNavigation.BOOKMARK)
        },
        OptionEntry.Option(KeydroidxIcons.ICON_DISPLAY, R.string.settings_display) {
            openSettingsCategory(SettingsNavigation.DISPLAY)
        },
        OptionEntry.Option(KeydroidxIcons.ICON_LOCK, R.string.settings_privacy) {
            openSettingsCategory(SettingsNavigation.PRIVACY)
        },
        OptionEntry.Option(KeydroidxIcons.ICON_ADVANCED, R.string.settings_advanced) {
            openSettingsCategory(SettingsNavigation.ADVANCED)
        },
        OptionEntry.Option(KeydroidxIcons.ICON_INFO, R.string.settings_about) {
            openSettingsCategory(SettingsNavigation.ABOUT)
        },
        OptionEntry.Option(KeydroidxIcons.ICON_HELP, R.string.keyphone_opt_help) { launchHelp() },
        OptionEntry.Option(KeydroidxIcons.ICON_POWER, R.string.keyphone_opt_exit) { confirmExit() }
    )

    /**
     * 历史记录页（首页模式入口）。
     *
     * 独立 Activity（[KeyphoneHistoryActivity]，与安全拦截页同款骨架，自带标题栏），
     * 直接拉起即可，不再绕道浏览页。
     */
    private fun launchHistoryPage() {
        try {
            startActivity(Intent(this, KeyphoneHistoryActivity::class.java))
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "打开历史记录失败", t)
        }
    }

    /**
     * 下载记录页（独立 Activity，自带标题栏）。
     */
    private fun launchDownloadsPage() {
        try {
            startActivity(Intent(this, KeyphoneDownloadsActivity::class.java))
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "打开下载记录页失败", t)
        }
    }

    /** 书签页：`SCHEME_BOOKMARKS` 是「主页」哨兵值，由 `TabsRepository` 翻译成 `BookmarkPageInitializer`。 */
    private fun launchBookmarks() {
        launchBrowser(SCHEME_BOOKMARKS, "打开书签页失败")
    }

    private fun launchBrowser(url: String, failMessage: String) {
        try {
            val intent = Intent(this, KeyphoneBrowserActivity::class.java)
            intent.data = Uri.parse(url)
            startActivity(intent)
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, failMessage, t)
        }
    }

    /**
     * 标签页列表（首页模式入口）。
     *
     * 标签页（`TabsRepository` / `BrowserPresenter`）是 `@BrowserScope` —— **每个浏览页 Activity 一份**，
     * 首页没有浏览内核，就地列不出来。所以这里把浏览页拉到前台（`EXTRA_SHOW_TABS`），
     * 由它加载/恢复标签页后 push `KeyphoneTabsFragment`。
     */
    private fun launchTabsList() {
        try {
            startActivity(
                Intent(this, KeyphoneBrowserActivity::class.java)
                    .putExtra(KeyphoneBrowserActivity.EXTRA_SHOW_TABS, true)
            )
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "打开标签页列表失败", t)
        }
    }

    /** 安全拦截规则管理页（协议跳转 / 站内跳转 / 下载站点规则统一管理）。 */
    private fun launchGuardRules() {
        try {
            startActivity(Intent(this, KeyphoneGuardListActivity::class.java))
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "打开安全拦截规则页失败", t)
        }
    }

    /**
     * 帮助页（分类讲解按键功能）。
     *
     * 本页与模式无关：首页模式与页面模式都直接本地拉起，不需要回传浏览页执行
     * （对比「设置」在页面模式下由浏览页转发给 `SettingsActivity`）。
     */
    private fun launchHelp() {
        try {
            startActivity(Intent(this, KeyphoneHelpActivity::class.java))
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "打开帮助页失败", t)
        }
    }

    /** 「页面」组条目：作用对象是当前网页，首页进入时无网页可操作。 */
    private fun requirePage() {
        Toast.makeText(this, R.string.keyphone_need_page, Toast.LENGTH_SHORT).show()
    }

    private fun confirmClearTabsFromHome() {
        KeydroidxConfirmDialog(
            this,
            getString(R.string.keyphone_clear_tabs_title),
            getString(R.string.keyphone_clear_tabs_message)
        )
            .setPositiveButton(getString(R.string.keyphone_soft_clear)) {
                try {
                    val file = File(filesDir, "SAVED_TABS.parcel")
                    if (file.exists()) {
                        file.delete()
                    }
                    Toast.makeText(this, R.string.keyphone_tabs_cleared, Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    KeydroidxLog.e(TAG, "清空标签页失败", t)
                }
            }
            .setNegativeButton(getString(R.string.keyphone_soft_cancel), null)
            .show()
    }

    /** 退出浏览器：不弹确认框，直接退出（清理逻辑由 onDestroy 链路保证）。 */
    private fun confirmExit() {
        finishAffinity()
    }

    private fun handleBack(): Boolean {
        return when (currentLevel) {
            ScreenLevel.SETTINGS_CATEGORY -> {
                openOptionsRoot()
                true
            }

            ScreenLevel.OPTIONS_ROOT -> false
        }
    }

    override fun onAction(action: Int): Boolean {
        val helper = focusHelper ?: return super.onAction(action)
        return when (action) {
            KeydroidxKeyAction.UP, KeydroidxKeyAction.DOWN -> helper.onDirection(action)
            KeydroidxKeyAction.SELECT -> {
                helper.focusedView?.performClick()
                true
            }

            KeydroidxKeyAction.SOFT_RIGHT -> {
                if (handleBack()) {
                    true
                } else {
                    super.onAction(action)
                }
            }

            else -> super.onAction(action)
        }
    }

    /** 选项页条目：分组标题 / 分隔线 / 可聚焦选项。 */
    private sealed interface OptionEntry {
        data class Section(@StringRes val titleRes: Int) : OptionEntry
        data object Divider : OptionEntry
        data class Option(
            val iconCode: String,
            @StringRes val titleRes: Int,
            val action: () -> Unit
        ) : OptionEntry
    }

    companion object {
        private const val TAG = "KeyphoneOptions"

        /** 页面模式标记：由 `KeyphoneBrowserActivity` 调起时置 true。 */
        const val EXTRA_PAGE_MODE = "keyphone_page_mode"

        /** 页面模式下回传给浏览页的动作 id。 */
        const val EXTRA_ACTION = "keyphone_page_action"

        const val ACTION_NEW_TAB = "new_tab"
        const val ACTION_NEW_INCOGNITO_TAB = "new_incognito_tab"
        const val ACTION_REFRESH = "refresh"
        const val ACTION_STOP = "stop"
        const val ACTION_SHARE = "share"
        const val ACTION_COPY_LINK = "copy_link"
        const val ACTION_ADD_HOME = "add_home"

        /** 打开浏览页内的标签页列表页（由浏览页 push `KeyphoneTabsFragment`）。 */
        const val ACTION_SHOW_TABS = "show_tabs"

        /** 关闭除当前页外的所有标签页。 */
        const val ACTION_CLEAR_TABS = "clear_tabs"
        const val ACTION_HISTORY = "history"
        const val ACTION_DOWNLOADS = "downloads"
        const val ACTION_SETTINGS = "settings"
        const val ACTION_EXIT = "exit"
    }
}
