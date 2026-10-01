package acr.browser.lightning.keyphone

import acr.browser.lightning.R
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.cctyl.nokia.common.R as CommonR
import io.github.cctyl.nokia.common.model.KeydroidxKeyAction
import io.github.cctyl.nokia.common.ui.KeydroidxFontManager
import io.github.cctyl.nokia.common.ui.KeydroidxIcons
import io.github.cctyl.nokia.common.ui.KeydroidxTheme
import io.github.cctyl.nokia.common.util.KeydroidxDimens
import io.github.cctyl.nokia.keycore.ui.KeydroidxBaseActivity

/**
 * 帮助讲解页：一个功能一段说明文字（可滚动）。
 *
 * 正文按 `EXTRA_TITLE_RES` / `EXTRA_BODY_RES` 取资源（由 [KeyphoneHelpActivity] 传入），
 * 页面本身不含业务逻辑，新增讲解不需要改这里。
 *
 * 方向键按整屏步长滚动（与阅读模式同样的翻页手感，触屏也可直接拖动），RSK/BACK 返回分类列表。
 */
class KeyphoneHelpDetailActivity : KeydroidxBaseActivity() {

    private var scroll: ScrollView? = null

    override fun getContentLayoutRes(): Int = R.layout.keyphone_list

    override fun onInitViews() {
        val titleRes = intent?.getIntExtra(EXTRA_TITLE_RES, 0) ?: 0
        val bodyRes = intent?.getIntExtra(EXTRA_BODY_RES, 0) ?: 0
        setPageTitle(
            if (titleRes != 0) getString(titleRes) else getString(R.string.keyphone_help_title)
        )
        setTitleIcon(KeydroidxIcons.ICON_HELP)
        setStatusBarVisible(false)
        setSoftKeys(null, null, getString(R.string.keyphone_soft_back))

        scroll = findViewById(R.id.keyphone_scroll)
        val container = findViewById<LinearLayout>(R.id.keyphone_rows)
        container.addView(bodyText(if (bodyRes != 0) getString(bodyRes) else ""))
    }

    /** 讲解正文：单块文本（内部换行分段），行距放宽便于小屏逐行阅读。 */
    private fun bodyText(body: String): TextView {
        val theme = KeydroidxTheme.getCurrentTheme(this)
        val view = TextView(this)
        view.text = body
        view.setTextColor(theme.textColor)
        KeydroidxFontManager.setTextSizeResource(view, CommonR.dimen.keydroidx_font_body)
        view.setLineSpacing(KeydroidxDimens.dp(resources, 3f).toFloat(), 1f)
        view.setPadding(
            KeydroidxDimens.dp(resources, 10f), KeydroidxDimens.dp(resources, 6f),
            KeydroidxDimens.dp(resources, 10f), KeydroidxDimens.dp(resources, 10f)
        )
        return view
    }

    override fun onAction(action: Int): Boolean {
        val view = scroll
        if (view != null &&
            (action == KeydroidxKeyAction.UP || action == KeydroidxKeyAction.DOWN)
        ) {
            // 讲解正文没有可聚焦行，方向键直接整屏滚动最顺手
            val step = (view.height * HELP_SCROLL_STEP_RATIO).toInt().coerceAtLeast(1)
            view.smoothScrollBy(0, if (action == KeydroidxKeyAction.UP) -step else step)
            return true
        }
        return super.onAction(action)
    }

    companion object {
        const val EXTRA_TITLE_RES = "keyphone_help_title_res"
        const val EXTRA_BODY_RES = "keyphone_help_body_res"

        /** 方向键单次滚动步长（占可视高度的比例，与阅读模式整屏翻页一致） */
        private const val HELP_SCROLL_STEP_RATIO = 0.85f
    }
}
