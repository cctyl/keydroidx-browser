package acr.browser.lightning.keyphone

import acr.browser.lightning.R
import acr.browser.lightning.database.HistoryEntry
import acr.browser.lightning.database.history.HistoryRepository
import acr.browser.lightning.di.injector
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import io.github.cctyl.nokia.common.log.KeydroidxLog
import io.github.cctyl.nokia.common.model.KeydroidxKeyAction
import io.github.cctyl.nokia.common.ui.KeydroidxIcons
import io.github.cctyl.nokia.common.ui.dialog.KeydroidxConfirmDialog
import io.github.cctyl.nokia.common.ui.page.KeydroidxListFocusHelper
import io.github.cctyl.nokia.keycore.ui.KeydroidxBaseActivity
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * 按键机「历史记录」页（独立 Activity，与 [KeyphoneGuardListActivity] 同款骨架）。
 *
 * 为什么独立 Activity 而不是浏览页内 Fragment：独立 Activity 继承基类后自动获得
 * 顶部标题栏（基类 [KeydroidxBaseActivity.onInitViews] 绘制），与选项页 / 安全拦截页
 * 观感完全一致 —— 浏览页内 Fragment 会把顶栏一起藏掉，没有标题。
 *
 * 数据：[HistoryRepository] 走 AppComponent member injection（[AppComponent.inject]）。
 *
 * 按键（沿用安全拦截页 / 标签页列表的肌肉记忆）：
 * - UP / DOWN：移动光标（循环导航）；确认键：打开该条网址；
 * - 方向左键：删除当前条（确认弹窗）；
 * - LSK：清空全部历史（确认弹窗）；RSK / BACK：返回。
 */
class KeyphoneHistoryActivity : KeydroidxBaseActivity() {

    @Inject
    internal lateinit var historyRepository: HistoryRepository

    private var focusHelper: KeydroidxListFocusHelper? = null
    private var rowsContainer: LinearLayout? = null
    private var scroll: ScrollView? = null
    private val focusables = ArrayList<View>()
    private var entries: List<HistoryEntry> = emptyList()
    private var lastFocusIndex = 0

    override fun getContentLayoutRes(): Int = R.layout.keyphone_list

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须先注入再 super：基类 onCreate 会回调 onInitViews() → rebuild() 读库，
        // 注入放后面会让首次 rebuild 撞上 lateinit 未初始化（实测报「历史记录读取失败」）
        injector.inject(this)
        super.onCreate(savedInstanceState)
    }

    override fun onInitViews() {
        setPageTitle(getString(R.string.keyphone_history_title))
        setTitleIcon(KeydroidxIcons.ICON_HISTORY)
        setStatusBarVisible(false)
        setSoftKeys(
            getString(R.string.keyphone_soft_clear),
            null,
            getString(R.string.keyphone_soft_back)
        )
        scroll = findViewById(R.id.keyphone_scroll)
        rowsContainer = findViewById(R.id.keyphone_rows)
        rebuild()
    }

    /** 从数据库重载整页（删除 / 清空后同样走这里重画，保留焦点位置）。 */
    private fun rebuild() {
        lifecycleScope.launch {
            try {
                render(historyRepository.lastHundredVisitedHistoryEntries())
            } catch (t: Throwable) {
                KeydroidxLog.e(TAG, "加载历史记录失败", t)
                Toast.makeText(this@KeyphoneHistoryActivity, R.string.keyphone_history_load_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun render(list: List<HistoryEntry>) {
        val container = rowsContainer ?: return
        entries = list
        container.removeAllViews()
        focusables.clear()

        if (list.isEmpty()) {
            container.addView(
                KeyphoneViews.sectionLabel(this, getString(R.string.keyphone_history_empty))
            )
            focusHelper = null
            return
        }

        // 按自然日分组：今天 / 昨天 / 更早按「M月d日」（跨年带年份）。
        // 数据库已按访问时间倒序返回（HistoryDatabase ORDER BY time DESC），这里只管切组。
        // 分组头走 sectionLabel + divider（非焦点项，不进 focusables，方向键只在高亮行间移动）。
        var currentDay = Long.MIN_VALUE
        list.forEach { entry ->
            val day = startOfDay(entry.lastTimeVisited)
            if (day != currentDay) {
                if (focusables.isNotEmpty()) {
                    container.addView(KeyphoneViews.divider(this))
                }
                container.addView(KeyphoneViews.sectionLabel(this, dayLabel(day)))
                currentDay = day
            }

            val title = entry.title.ifBlank { entry.url }
            val row = KeyphoneViews.iconRow(this, null, title, title, entry.url)
            row.tag = entry
            row.setOnClickListener {
                lastFocusIndex = focusables.indexOf(row).coerceAtLeast(0)
                focusHelper?.setFocusIndex(lastFocusIndex)
                openEntry(entry)
            }
            container.addView(row)
            focusables.add(row)
        }

        val helper = KeydroidxListFocusHelper(scroll ?: return)
        helper.setItems(focusables)
        helper.setFocusIndex(lastFocusIndex.coerceIn(0, focusables.size - 1))
        focusHelper = helper
    }

    /** 当天 0 点的时间戳（本地时区，java.util.Calendar 兼容 minSdk 24）。 */
    private fun startOfDay(timeMillis: Long): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = timeMillis
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /** 分组标题：今天 / 昨天 / M月d日（非今年补年份）。 */
    private fun dayLabel(dayStart: Long): String {
        val now = startOfDay(System.currentTimeMillis())
        return when (dayStart) {
            now -> getString(R.string.keyphone_history_today)
            now - DAY_MS -> getString(R.string.keyphone_history_yesterday)
            else -> {
                val sameYear = java.util.Calendar.getInstance().apply { timeInMillis = dayStart }
                    .get(java.util.Calendar.YEAR) ==
                    java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                java.text.SimpleDateFormat(
                    if (sameYear) "M月d日" else "yyyy年M月d日",
                    java.util.Locale.CHINESE
                ).format(java.util.Date(dayStart))
            }
        }
    }

    /** 打开该条网址：复用浏览页 singleTop 入口（与选项页 launchBrowser 同一模式）。 */
    private fun openEntry(entry: HistoryEntry) {
        try {
            startActivity(
                Intent(this, KeyphoneBrowserActivity::class.java).apply {
                    data = Uri.parse(entry.url)
                }
            )
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "打开历史记录失败: ${entry.url}", t)
        }
    }

    private fun confirmDeleteFocused() {
        val entry = entries.getOrNull(lastFocusIndex) ?: return
        KeydroidxConfirmDialog(
            this,
            getString(R.string.keyphone_delete_history_title),
            getString(R.string.keyphone_delete_history_message)
        )
            .setPositiveButton(getString(R.string.keyphone_soft_delete)) {
                lifecycleScope.launch {
                    try {
                        historyRepository.deleteHistoryEntry(entry.url)
                        Toast.makeText(this@KeyphoneHistoryActivity, R.string.keyphone_history_deleted, Toast.LENGTH_SHORT).show()
                        rebuild()
                    } catch (t: Throwable) {
                        KeydroidxLog.e(TAG, "删除历史记录失败: ${entry.url}", t)
                    }
                }
            }
            .setNegativeButton(getString(R.string.keyphone_soft_cancel), null)
            .show()
    }

    private fun confirmClearHistory() {
        if (entries.isEmpty()) return
        KeydroidxConfirmDialog(
            this,
            getString(R.string.keyphone_clear_history_title),
            getString(R.string.keyphone_clear_history_message)
        )
            .setPositiveButton(getString(R.string.keyphone_soft_clear)) {
                lifecycleScope.launch {
                    try {
                        historyRepository.deleteHistory()
                        Toast.makeText(this@KeyphoneHistoryActivity, R.string.keyphone_history_cleared, Toast.LENGTH_SHORT).show()
                        lastFocusIndex = 0
                        rebuild()
                    } catch (t: Throwable) {
                        KeydroidxLog.e(TAG, "清空历史记录失败", t)
                    }
                }
            }
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

            KeydroidxKeyAction.LEFT -> {
                confirmDeleteFocused()
                true
            }

            KeydroidxKeyAction.SOFT_LEFT -> {
                confirmClearHistory()
                true
            }

            else -> super.onAction(action)
        }
    }

    companion object {
        private const val TAG = "KeyphoneHistory"
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
