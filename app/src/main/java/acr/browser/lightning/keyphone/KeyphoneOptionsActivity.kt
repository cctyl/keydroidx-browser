package acr.browser.lightning.keyphone

import acr.browser.lightning.DefaultBrowserActivity
import acr.browser.lightning.IncognitoBrowserActivity
import acr.browser.lightning.R
import acr.browser.lightning.constant.SCHEME_BOOKMARKS
import acr.browser.lightning.constant.SCHEME_HOMEPAGE
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
 * 条目分四组：标签 → 页面 → 收藏与记录 → 系统。
 * 「页面」组各条目作用对象是当前网页（原实现走 `BrowserPresenter.onMenuClick` 的 currentTab），
 * 本页目前从首页进入、尚无按键机浏览页，故统一提示「请先打开网页后再使用」；
 * 待按键机浏览界面落地后，这些条目改为由浏览页携带当前 URL 调起并回调浏览页执行。
 *
 * RSK/BACK 返回首页；「退出浏览器」走 KeydroidxConfirmDialog（红线 #9）。
 */
class KeyphoneOptionsActivity : KeydroidxBaseActivity() {

    private var focusHelper: KeydroidxListFocusHelper? = null

    override fun getContentLayoutRes(): Int = R.layout.keyphone_list

    override fun onInitViews() {
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
    private fun buildOptions(): List<OptionEntry> = listOf(
        OptionEntry.Section(R.string.keyphone_section_tabs),
        OptionEntry.Option(KeydroidxIcons.ICON_ADD, R.string.keyphone_opt_new_tab) { launchHome() },
        OptionEntry.Option(KeydroidxIcons.ICON_LOCK, R.string.keyphone_opt_incognito) { launchIncognito() },

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
        OptionEntry.Option(KeydroidxIcons.ICON_HISTORY, R.string.keyphone_opt_history) { todo() },
        OptionEntry.Option(KeydroidxIcons.ICON_DOWNLOAD, R.string.keyphone_opt_downloads) { todo() },

        OptionEntry.Divider,
        OptionEntry.Section(R.string.keyphone_section_system),
        OptionEntry.Option(KeydroidxIcons.ICON_SETTINGS, R.string.keyphone_opt_settings) { launchSettings() },
        OptionEntry.Option(KeydroidxIcons.ICON_POWER, R.string.keyphone_opt_exit) { confirmExit() }
    )

    private fun launchHome() {
        launchBrowser(SCHEME_HOMEPAGE, "打开浏览器失败")
    }

    private fun launchBookmarks() {
        launchBrowser(SCHEME_BOOKMARKS, "打开书签页失败")
    }

    private fun launchBrowser(url: String, failMessage: String) {
        try {
            val intent = Intent(this, DefaultBrowserActivity::class.java)
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
    }
}
