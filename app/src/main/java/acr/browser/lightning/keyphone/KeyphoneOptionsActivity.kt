package acr.browser.lightning.keyphone

import acr.browser.lightning.IncognitoBrowserActivity
import acr.browser.lightning.R
import acr.browser.lightning.constant.SCHEME_BOOKMARKS
import acr.browser.lightning.constant.SCHEME_HISTORY
import acr.browser.lightning.settings.activity.SettingsActivity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.annotation.StringRes
import io.github.cctyl.nokia.common.log.KeydroidxLog
import io.github.cctyl.nokia.common.model.KeydroidxKeyAction
import io.github.cctyl.nokia.common.ui.KeydroidxIcons
import io.github.cctyl.nokia.common.ui.dialog.KeydroidxConfirmDialog
import io.github.cctyl.nokia.common.ui.page.KeydroidxListFocusHelper
import io.github.cctyl.nokia.keycore.ui.KeydroidxBaseActivity

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

    private var focusHelper: KeydroidxListFocusHelper? = null

    /** true = 由浏览页以「页面模式」调起，条目动作回传给浏览页执行。 */
    private var pageMode = false

    override fun getContentLayoutRes(): Int = R.layout.keyphone_list

    override fun onInitViews() {
        pageMode = intent?.getBooleanExtra(EXTRA_PAGE_MODE, false) == true
        setPageTitle(getString(R.string.keyphone_options_title))
        setTitleIcon(KeydroidxIcons.MORE_VERT)
        setStatusBarVisible(true)
        registerBatteryReceiver()
        setSoftKeys(
            getString(R.string.keyphone_soft_confirm),
            null,
            getString(R.string.keyphone_soft_back)
        )

        val scroll = findViewById<ScrollView>(R.id.keyphone_scroll)
        val container = findViewById<LinearLayout>(R.id.keyphone_rows)

        val focusables = ArrayList<View>()
        buildOptions().forEach { entry ->
            when (entry) {
                // 分组标题与分隔线均为非焦点项，只进容器、不进焦点列表
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

        val helper = KeydroidxListFocusHelper(scroll)
        helper.setItems(focusables)
        if (focusables.isNotEmpty()) {
            helper.setFocusIndex(0)
        }
        focusHelper = helper
    }

    /** 选项全集（顺序即展示顺序）。 */
    private fun buildOptions(): List<OptionEntry> =
        if (pageMode) buildPageModeOptions() else buildHomeOptions()

    /** 页面模式全集：全部条目回传浏览页执行（浏览页才持有 BrowserPresenter）。 */
    private fun buildPageModeOptions(): List<OptionEntry> = listOf(
        OptionEntry.Section(R.string.keyphone_section_tabs),
        OptionEntry.Option(KeydroidxIcons.ICON_ADD, R.string.keyphone_opt_new_tab) {
            relay(ACTION_NEW_TAB)
        },
        OptionEntry.Option(KeydroidxIcons.ICON_LOCK, R.string.keyphone_opt_incognito) {
            relay(ACTION_NEW_INCOGNITO_TAB)
        },
        OptionEntry.Option(KeydroidxIcons.VIEW_LIST, R.string.keyphone_opt_tabs) {
            relay(ACTION_SHOW_TABS)
        },
        OptionEntry.Option(KeydroidxIcons.ICON_DELETE, R.string.keyphone_opt_clear_tabs) {
            relay(ACTION_CLEAR_TABS)
        },

        OptionEntry.Divider,
        OptionEntry.Section(R.string.keyphone_section_page),
        OptionEntry.Option(KeydroidxIcons.ICON_REFRESH, R.string.keyphone_opt_refresh) {
            relay(ACTION_REFRESH)
        },
        OptionEntry.Option(KeydroidxIcons.ICON_CLOSE, R.string.keyphone_opt_stop) {
            relay(ACTION_STOP)
        },
        OptionEntry.Option(KeydroidxIcons.SHARE, R.string.keyphone_opt_share) {
            relay(ACTION_SHARE)
        },
        OptionEntry.Option(KeydroidxIcons.ICON_URL, R.string.keyphone_opt_copy_link) {
            relay(ACTION_COPY_LINK)
        },
        OptionEntry.Option(KeydroidxIcons.ICON_SHORTCUTS, R.string.keyphone_opt_add_home) {
            relay(ACTION_ADD_HOME)
        },

        OptionEntry.Divider,
        OptionEntry.Section(R.string.keyphone_section_records),
        OptionEntry.Option(KeydroidxIcons.ICON_HISTORY, R.string.keyphone_opt_history) {
            relay(ACTION_HISTORY)
        },
        OptionEntry.Option(KeydroidxIcons.ICON_DOWNLOAD, R.string.keyphone_opt_downloads) {
            relay(ACTION_DOWNLOADS)
        },

        OptionEntry.Divider,
        OptionEntry.Section(R.string.keyphone_section_system),
        OptionEntry.Option(KeydroidxIcons.ICON_SETTINGS, R.string.keyphone_opt_settings) {
            relay(ACTION_SETTINGS)
        },
        OptionEntry.Option(KeydroidxIcons.ICON_POWER, R.string.keyphone_opt_exit) {
            relay(ACTION_EXIT)
        }
    )

    /** 把选中的动作回传给浏览页并结束本页。 */
    private fun relay(action: String) {
        setResult(android.app.Activity.RESULT_OK, Intent().putExtra(EXTRA_ACTION, action))
        finish()
    }

    /** 首页模式全集（无当前网页，「页面」组提示先打开网页）。 */
    private fun buildHomeOptions(): List<OptionEntry> = listOf(
        OptionEntry.Section(R.string.keyphone_section_tabs),
        OptionEntry.Option(KeydroidxIcons.ICON_ADD, R.string.keyphone_opt_new_tab) { goHome() },
        OptionEntry.Option(KeydroidxIcons.ICON_LOCK, R.string.keyphone_opt_incognito) { launchIncognito() },
        OptionEntry.Option(KeydroidxIcons.VIEW_LIST, R.string.keyphone_opt_tabs) { launchTabsList() },

        OptionEntry.Divider,
        OptionEntry.Section(R.string.keyphone_section_page),
        OptionEntry.Option(KeydroidxIcons.ICON_REFRESH, R.string.keyphone_opt_refresh) { requirePage() },
        OptionEntry.Option(KeydroidxIcons.ICON_CLOSE, R.string.keyphone_opt_stop) { requirePage() },
        OptionEntry.Option(KeydroidxIcons.ICON_SEARCH, R.string.keyphone_opt_find) { requirePage() },
        OptionEntry.Option(KeydroidxIcons.FAVORITE_BORDER, R.string.keyphone_opt_add_bookmark) { requirePage() },
        OptionEntry.Option(KeydroidxIcons.SHARE, R.string.keyphone_opt_share) { requirePage() },
        OptionEntry.Option(KeydroidxIcons.ICON_URL, R.string.keyphone_opt_copy_link) { requirePage() },
        OptionEntry.Option(KeydroidxIcons.ICON_SHORTCUTS, R.string.keyphone_opt_add_home) { requirePage() },

        OptionEntry.Divider,
        OptionEntry.Section(R.string.keyphone_section_records),
        OptionEntry.Option(KeydroidxIcons.ICON_FAVORITE, R.string.keyphone_opt_bookmarks) { launchBookmarks() },
        // 历史页：`SCHEME_HISTORY` 哨兵值由 `TabsRepository` 翻译成 `HistoryPageInitializer`（与书签页同套路）
        OptionEntry.Option(KeydroidxIcons.ICON_HISTORY, R.string.keyphone_opt_history) {
            launchBrowser(SCHEME_HISTORY, "打开历史记录失败")
        },
        OptionEntry.Option(KeydroidxIcons.ICON_DOWNLOAD, R.string.keyphone_opt_downloads) { todo() },

        OptionEntry.Divider,
        OptionEntry.Section(R.string.keyphone_section_system),
        OptionEntry.Option(KeydroidxIcons.ICON_SETTINGS, R.string.keyphone_opt_settings) { launchSettings() },
        OptionEntry.Option(KeydroidxIcons.ICON_POWER, R.string.keyphone_opt_exit) { confirmExit() }
    )

    /**
     * 新建标签页 = 回到按键机首页（`BrowserHomeActivity`：搜索框 + 书签宫格 + 历史）。
     *
     * 首页是 `singleTask`，从任意深度（本页 / 浏览页）拉起它会把它之上的 Activity 一并清掉，
     * 所以一句 `startActivity` 就能稳定落到首页，不必各自算 finish / 回退层数。
     */
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

    /** 无痕标签页：与上游 `BrowserNavigator.launchIncognito()` 同一入口。 */
    private fun launchIncognito() {
        try {
            IncognitoBrowserActivity.launch(this, null)
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "打开无痕浏览器失败", t)
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

    private fun launchSettings() {
        try {
            startActivity(Intent(this, SettingsActivity::class.java))
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "打开设置失败", t)
        }
    }

    /** 「页面」组条目：作用对象是当前网页，首页进入时无网页可操作。 */
    private fun requirePage() {
        Toast.makeText(this, R.string.keyphone_need_page, Toast.LENGTH_SHORT).show()
    }

    private fun todo() {
        Toast.makeText(this, R.string.keyphone_todo, Toast.LENGTH_SHORT).show()
    }

    /** 破坏性操作确认：core 唯一确认组件，左软键=肯定、右软键=否定（红线 #9）。 */
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

    override fun onAction(action: Int): Boolean {
        val helper = focusHelper ?: return super.onAction(action)
        return when (action) {
            KeydroidxKeyAction.UP, KeydroidxKeyAction.DOWN -> helper.onDirection(action)
            KeydroidxKeyAction.SELECT -> {
                helper.focusedView?.performClick()
                true
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
