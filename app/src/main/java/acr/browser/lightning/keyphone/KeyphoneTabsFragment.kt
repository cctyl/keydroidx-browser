package acr.browser.lightning.keyphone

import acr.browser.lightning.R
import acr.browser.lightning.browser.tab.TabModel
import acr.browser.lightning.browser.tab.TabViewState
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import io.github.cctyl.nokia.common.log.KeydroidxLog
import io.github.cctyl.nokia.common.model.KeydroidxKeyAction
import io.github.cctyl.nokia.common.ui.dialog.KeydroidxConfirmDialog
import io.github.cctyl.nokia.common.ui.page.KeydroidxListPageFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 按键机「标签页列表」页 —— 竖向列表，一行一个标签页（长方形卡片：左侧页面预览 + 右侧标题 + 删除按钮）。
 *
 * 作为**浏览页内的页面**（`KeyphoneBrowserActivity.openFragment` push 到 midPanel）存在，而不是独立 Activity：
 * 上游的标签页（`TabsRepository` / `BrowserPresenter`）是 `@BrowserScope` —— **每个浏览页 Activity 一份**，
 * 独立 Activity 拿不到同一批标签页。
 *
 * 按键（用户定的交互）：
 * - UP / DOWN：移动光标（基类强制循环导航）；**移动时自动收回**所有行的删除按钮；
 * - 方向左键：当前行**露出删除按钮**（待删态）；
 * - 方向右键：收回删除按钮（左右对称）；
 * - 确认键：待删态 → **删除该标签页**；非待删态 → 切到该标签页并返回浏览；
 * - 删除最后一个标签页后 → **回按键机首页**（用户定，沿用「新建标签页 = 首页」的会话语义）；
 * - RSK / BACK：返回浏览。
 *
 * 列表由浏览页的 `tabsFlow()` **状态驱动**重建：删除只通知浏览页关标签，列表随后按新状态重画，
 * 这样上游「删的是当前页就切相邻页」的结果能同步反映到「当前 ·」标记上（本地增量移除做不到）。
 *
 * 注意上游 `onTabClose` 是**两步更新**：先移除 tab（此时没有任何选中项）再选中相邻页。
 * 中间态若把焦点重置到首行，列表会先跳到顶部再弹回，故中间态一律**保持原焦点**。
 *
 * 红线：继承 core `KeydroidxListPageFragment`、行视图走 `KeyphoneViews`、图标/字号走 core、异常必落日志。
 */
class KeyphoneTabsFragment : KeydroidxListPageFragment() {

    private var container: LinearLayout? = null

    /** 与 [itemViews] 一一对应的行视图，用于控制各行删除按钮的显隐。 */
    private val rows = ArrayList<KeyphoneViews.TabRow>()

    override fun getLayoutRes(): Int = R.layout.keyphone_list

    override fun getPageTitle(): CharSequence = getString(R.string.keyphone_tabs_title)

    override fun getSoftLeftText(): CharSequence = getString(R.string.keyphone_soft_clear)

    override fun getSoftRightText(): CharSequence = getString(R.string.keyphone_soft_back)

    override fun onPageCreated(view: View, savedInstanceState: Bundle?) {
        // `midPanel` 是个裸 FrameLayout，且基类 `KeydroidxPageFragment.onViewCreated` 会
        // `setBackgroundResource(0)` 清掉 Fragment 背景 —— 不显式填色就会透出底下的 WebView。
        // 背景与主页容器（keyphone_browser.xml `#121D2C`）一致，避免页间切换背景跳变
        // （主题 darkColor 是顶栏渐变收尾色，与主页色肉眼可辨，会显得「和主题对不上」）。
        view.setBackgroundColor(HOME_BACKGROUND)
        listScroll = view.findViewById<ScrollView>(R.id.keyphone_scroll)
        val host = activity as? KeyphoneBrowserActivity ?: return
        val box = view.findViewById<LinearLayout>(R.id.keyphone_rows) ?: return
        container = box
        // 状态驱动：进入即渲染一次（StateFlow 立即发射当前值），之后标签页增删 / 选中项变化自动重建。
        viewLifecycleOwner.lifecycleScope.launch {
            host.tabsFlow().collectLatest { renderTabs(host.tabsSnapshot()) }
        }
    }

    /** 用最新快照重建整个列表。 */
    private fun renderTabs(tabs: List<TabViewState>) {
        if (!isAdded) return
        val box = container ?: return
        box.removeAllViews()
        rows.clear()

        if (tabs.isEmpty()) {
            box.addView(
                KeyphoneViews.sectionLabel(requireContext(), getString(R.string.keyphone_tabs_empty))
            )
            setItemList(emptyList())
            return
        }

        tabs.forEachIndexed { index, tab ->
            // 当前标签页用文字标记（红线：不得只靠颜色表达状态）
            val label = if (tab.isSelected) {
                getString(R.string.keyphone_tabs_current, tab.title)
            } else {
                tab.title
            }
            val row = KeyphoneViews.tabRow(requireContext(), label, tab.isSelected)
            // 触屏回退：点行 = 待删态下删除，否则切到该标签页
            row.root.setOnClickListener {
                setFocusIndex(index)
                if (row.delete.visibility == View.VISIBLE) deleteFocused() else switchTo(index)
            }
            box.addView(row.root)
            rows.add(row)
            loadPreview(row, tab)
        }
        setItemList(rows.map { it.root as View })
        val selected = tabs.indexOfFirst { it.isSelected }
        // 中间态（上游刚移除 tab、尚未选中新页）保持原焦点，避免列表先跳顶部再弹回。
        setFocusIndex(if (selected >= 0) selected else focusIndex.coerceIn(0, tabs.size - 1))
    }

    /** 方向左键露出删除按钮、右键收回（由基类 `onDirection` 的 LEFT/RIGHT 分支转发到此）。 */
    override fun onLeftRight(direction: Int): Boolean {
        val index = focusIndex
        if (index < 0 || index >= rows.size) return true
        when (direction) {
            KeydroidxKeyAction.LEFT -> rows[index].delete.visibility = View.VISIBLE
            KeydroidxKeyAction.RIGHT -> rows[index].delete.visibility = View.GONE
        }
        return true
    }

    /** 光标移走后自动收回其他行的删除按钮（「上下移动即取消」）。 */
    override fun onFocusIndexChanged(index: Int) {
        rows.forEachIndexed { i, row ->
            if (i != index) row.delete.visibility = View.GONE
        }
    }

    /** 确认键：待删态 → 删除；否则切到该标签页。 */
    override fun onSelect(): Boolean {
        val index = focusIndex
        if (index < 0 || index >= rows.size) return true
        if (rows[index].delete.visibility == View.VISIBLE) {
            deleteFocused()
        } else {
            switchTo(index)
        }
        return true
    }

    override fun onBack(): Boolean {
        (activity as? KeyphoneBrowserActivity)?.closeTabsPage()
        return true
    }

    override fun onSoftLeft(): Boolean {
        confirmClearAllTabs()
        return true
    }

    private fun confirmClearAllTabs() {
        val host = activity as? KeyphoneBrowserActivity ?: return
        if (rows.isEmpty()) return
        KeydroidxConfirmDialog(
            requireContext(),
            getString(R.string.keyphone_clear_tabs_title),
            getString(R.string.keyphone_clear_tabs_message)
        )
            .setPositiveButton(getString(R.string.keyphone_soft_clear)) {
                host.clearAllTabs()
            }
            .setNegativeButton(getString(R.string.keyphone_soft_cancel), null)
            .show()
    }

    /**
     * 删除当前焦点标签页：只通知浏览页关标签，列表由 `tabsFlow` 驱动重建
     * （这样「当前」标记会随上游的相邻页切换自动修正）。
     * 删的是最后一个标签页 → 回按键机首页（用户定）。
     */
    private fun deleteFocused() {
        val host = activity as? KeyphoneBrowserActivity ?: return
        val index = focusIndex
        if (index < 0 || index >= rows.size) return
        val isLastTab = rows.size == 1
        host.closeTab(index)
        if (isLastTab) {
            host.goHomeFromTabs()
        }
    }

    private fun switchTo(index: Int) {
        val host = activity as? KeyphoneBrowserActivity ?: return
        host.switchToTab(index)
        host.closeTabsPage()
    }

    /**
     * 异步解码标签页预览图。预览是上游在页面加载完成时落盘到预览缓存目录的 PNG（文件名 = 标签页 id）。
     */
    private fun loadPreview(row: KeyphoneViews.TabRow, tab: TabViewState) {
        val image = tab.preview as? TabModel.Preview.Image ?: return
        val reqW = row.preview.layoutParams?.width ?: 0
        val reqH = row.preview.layoutParams?.height ?: 0
        if (reqW <= 0 || reqH <= 0) {
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeTopCropped(image.path, reqW, reqH) }
            if (bitmap != null) {
                row.preview.setImageBitmap(bitmap)
            }
        }
    }

    /**
     * 解码预览图并只保留顶部（对齐上游 `TopCropTransformation`：缩略图看页面头部最易辨认），
     * 同时按目标尺寸降采样，避免解码整页大图。
     */
    private fun decodeTopCropped(path: String, reqW: Int, reqH: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH)
            }
            val decoded = BitmapFactory.decodeFile(path, options)
            if (decoded == null) {
                null
            } else {
                val targetHeight = (decoded.width.toLong() * reqH / reqW).toInt().coerceAtLeast(1)
                val cropHeight = minOf(decoded.height, targetHeight)
                if (cropHeight >= decoded.height) {
                    decoded
                } else {
                    val cropped = Bitmap.createBitmap(decoded, 0, 0, decoded.width, cropHeight)
                    decoded.recycle()
                    cropped
                }
            }
        }
    } catch (t: Throwable) {
        KeydroidxLog.w(TAG, "预览图解码失败: $path", t)
        null
    }

    /** 2 的幂降采样，保证解码结果不小于目标尺寸。 */
    private fun sampleSize(outW: Int, outH: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        while (outW / (sample * 2) >= reqW && outH / (sample * 2) >= reqH) {
            sample *= 2
        }
        return sample
    }

    companion object {
        private const val TAG = "KeyphoneTabs"

        /** 与主页容器同色（keyphone_browser.xml `#121D2C`），避免页间切换时背景跳变。 */
        private const val HOME_BACKGROUND = 0xFF121D2C.toInt()
    }
}
