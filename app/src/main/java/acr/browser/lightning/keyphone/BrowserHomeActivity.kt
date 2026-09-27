package acr.browser.lightning.keyphone

import acr.browser.lightning.R
import acr.browser.lightning.database.Bookmark
import acr.browser.lightning.database.HistoryEntry
import acr.browser.lightning.database.bookmark.BookmarkRepository
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
import io.github.cctyl.nokia.common.ui.KeydroidxTextInputFragment
import io.github.cctyl.nokia.common.ui.page.KeydroidxListFocusHelper
import io.github.cctyl.nokia.keycore.ui.KeydroidxBaseActivity
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 按键机浏览器首页（进入浏览器后的第一屏，三段式）。
 *
 * - 上：可聚焦的搜索框（焦点列表第 0 项），CENTER 进入全屏编辑页（KeydroidxTextInputFragment）；
 * - 中：书签宫格（3 列，显示全部书签，随 ScrollView 滚动）+ 历史记录；
 *   宫格内左右换列、上下换行，上下走到边界后**纵向**离开宫格，不会横向跑；CENTER 直接打开对应页面；
 * - 下：软键栏 选项 | 空 | 返回 —— LSK 进入全屏选项页（等价原浏览器右上角 ⋮ 菜单）。
 *
 * 红线：继承 keycore KeydroidxBaseActivity（#1）、禁写 keyCode（#4）、
 * 首尾循环走 KeydroidxListFocusHelper（#6）、日志走 KeydroidxLog。
 */
class BrowserHomeActivity : KeydroidxBaseActivity() {

    @Inject
    internal lateinit var bookmarkRepository: BookmarkRepository

    @Inject
    internal lateinit var historyRepository: HistoryRepository

    private var scroll: ScrollView? = null
    private var rowsContainer: LinearLayout? = null
    private var focusHelper: KeydroidxListFocusHelper? = null

    private var bookmarks: List<Bookmark.Entry> = emptyList()
    private var history: List<HistoryEntry> = emptyList()

    /** 书签宫格在焦点列表中的起止（-1 表示当前无书签，用于左右/上下二维导航换算） */
    private var gridStart = -1
    private var gridCount = 0

    override fun getContentLayoutRes(): Int = R.layout.keyphone_list

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前注入，onInitViews 里就要用仓库
        injector.inject(this)
        super.onCreate(savedInstanceState)
    }

    override fun onInitViews() {
        setPageTitle(getString(R.string.keyphone_home_title))
        setTitleIcon(KeydroidxIcons.ICON_EXPLORE)
        setStatusBarVisible(true)
        registerBatteryReceiver()
        setSoftKeys(
            getString(R.string.keyphone_soft_options),
            null,
            getString(R.string.keyphone_soft_back)
        )

        scroll = findViewById(R.id.keyphone_scroll)
        rowsContainer = findViewById(R.id.keyphone_rows)
        focusHelper = KeydroidxListFocusHelper(scroll)
        rebuildRows()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch {
            try {
                // 不截断：宫格在 ScrollView 里，书签多时整体变高，由方向键驱动滚动
                val newBookmarks = bookmarkRepository.getAllBookmarksSorted()
                val newHistory =
                    historyRepository.lastHundredVisitedHistoryEntries().take(MAX_HISTORY_ROWS)
                val changed = newBookmarks != bookmarks || newHistory != history
                bookmarks = newBookmarks
                history = newHistory
                if (changed) {
                    rebuildRows()
                }
            } catch (t: Throwable) {
                KeydroidxLog.e(TAG, "加载书签/历史失败", t)
            }
        }
    }

    private fun rebuildRows() {
        val container = rowsContainer ?: return
        val helper = focusHelper ?: return
        val previousIndex = helper.focusIndex

        val children = ArrayList<View>()   // 页面实际子视图（含分组标签、宫格容器等非焦点项）
        val focusItems = ArrayList<View>() // 焦点列表（扁平顺序，宫格格子按行优先顺序展开）

        val search = KeyphoneViews.searchRow(this, getString(R.string.keyphone_search_hint))
        search.tag = PAYLOAD_SEARCH
        children.add(search)
        focusItems.add(search)

        gridStart = -1
        gridCount = 0
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
            grid.cells.forEachIndexed { index, cell ->
                cell.tag = bookmarks[index]
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
                activate(view)
            }
        }

        helper.setItems(focusItems)
        if (focusItems.isNotEmpty()) {
            helper.setFocusIndex(previousIndex.coerceIn(0, focusItems.size - 1))
        }
    }

    private fun activate(view: View?) {
        when (val payload = view?.tag) {
            PAYLOAD_SEARCH -> openSearchEditor()
            is Bookmark.Entry -> launchUrl(payload.url)
            is HistoryEntry -> launchUrl(payload.url)
        }
    }

    /** CENTER/点击搜索框 → 全屏编辑页（KeydroidxTextInputFragment）。 */
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
                launchUrl(KeyphoneUrls.normalize(text))
            }
        }
        openFragment(fragment)
    }

    /** LSK 选项 → 全屏选项页（等价原浏览器右上角 ⋮ 溢出菜单）。 */
    private fun openOptions() {
        val intent = Intent(this, KeyphoneOptionsActivity::class.java)
        startActivity(intent)
    }

    private fun launchUrl(url: String) {
        try {
            val intent = Intent(this, KeyphoneBrowserActivity::class.java)
            intent.data = Uri.parse(url)
            startActivity(intent)
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "打开链接失败: $url", t)
            Toast.makeText(this, R.string.keyphone_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAction(action: Int): Boolean {
        // 编辑页等 Fragment 打开时，按键交给当前前台页面（基类按 KeydroidxPage 契约分发）
        if (getCurrentPage() != null) {
            return super.onAction(action)
        }
        val helper = focusHelper ?: return super.onAction(action)
        return when (action) {
            KeydroidxKeyAction.UP, KeydroidxKeyAction.DOWN,
            KeydroidxKeyAction.LEFT, KeydroidxKeyAction.RIGHT -> moveFocus(action)
            KeydroidxKeyAction.SELECT -> {
                activate(helper.focusedView)
                true
            }
            KeydroidxKeyAction.SOFT_LEFT -> {
                openOptions()
                true
            }
            else -> super.onAction(action)
        }
    }

    /**
     * 焦点导航：书签宫格内按二维走（左右换列、上下换行）。
     *
     * 上下键**始终纵向**：走到宫格首/末行后直接离开宫格（首行 → 宫格上方的搜索框，
     * 末行 → 宫格下方的历史记录），**不会**横向跑到行尾再跳出去 —— 这正是
     * 「按下键应该往下走，而不是横着走」的要求。左右键只在宫格内换列，到行首/行尾保持不动。
     */
    private fun moveFocus(action: Int): Boolean {
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
                        moveOutOfGrid(helper, gridStart - 1)
                    }
                    return true
                }
                KeydroidxKeyAction.DOWN -> {
                    if (position + GRID_COLUMNS < gridCount) {
                        helper.setFocusIndex(index + GRID_COLUMNS)
                    } else {
                        moveOutOfGrid(helper, gridStart + gridCount)
                    }
                    return true
                }
            }
        }
        return when (action) {
            KeydroidxKeyAction.UP, KeydroidxKeyAction.DOWN -> helper.onDirection(action)
            // 宫格外（搜索框 / 历史记录）不响应左右键，消费掉避免穿透
            KeydroidxKeyAction.LEFT, KeydroidxKeyAction.RIGHT -> true
            else -> false
        }
    }

    /** 离开书签宫格：把焦点移到 [target]（越界则保持不动）。 */
    private fun moveOutOfGrid(helper: KeydroidxListFocusHelper, target: Int) {
        if (target >= 0 && target < helper.itemCount) {
            helper.setFocusIndex(target)
        }
    }

    companion object {
        private const val TAG = "KeyphoneHome"
        /** 书签宫格列数（240dp 基准宽度下每格约 74dp，图标 28dp + caption 名称） */
        private const val GRID_COLUMNS = 3
        private const val MAX_HISTORY_ROWS = 10
        private const val MAX_INPUT_CHARS = 100
        private val PAYLOAD_SEARCH = Any()
    }
}
