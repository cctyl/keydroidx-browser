package acr.browser.lightning.keyphone

import acr.browser.lightning.R
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.annotation.StringRes
import io.github.cctyl.nokia.common.log.KeydroidxLog
import io.github.cctyl.nokia.common.model.KeydroidxKeyAction
import io.github.cctyl.nokia.common.ui.KeydroidxIcons
import io.github.cctyl.nokia.common.ui.page.KeydroidxListFocusHelper
import io.github.cctyl.nokia.keycore.ui.KeydroidxBaseActivity

/**
 * 按键机帮助页（选项页 LSK 菜单「帮助」进入）。
 *
 * 结构分两级：本页只列**功能分类**，SELECT 进入 [KeyphoneHelpDetailActivity] 读该功能的按键与
 * 行为讲解。现在先有「阅读模式」，后续功能按 [Topic] 追加即可，不需要动页面结构。
 *
 * RSK/BACK 返回选项页。
 */
class KeyphoneHelpActivity : KeydroidxBaseActivity() {

    private var focusHelper: KeydroidxListFocusHelper? = null

    override fun getContentLayoutRes(): Int = R.layout.keyphone_list

    override fun onInitViews() {
        setPageTitle(getString(R.string.keyphone_help_title))
        setTitleIcon(KeydroidxIcons.ICON_HELP)
        setStatusBarVisible(false)
        setSoftKeys(null, null, getString(R.string.keyphone_soft_back))

        val scroll = findViewById<ScrollView>(R.id.keyphone_scroll)
        val container = findViewById<LinearLayout>(R.id.keyphone_rows)

        val focusables = ArrayList<View>()
        container.addView(
            KeyphoneViews.sectionLabel(this, getString(R.string.keyphone_help_section_topics))
        )
        topics().forEach { topic ->
            val row = KeyphoneViews.iconRow(
                this,
                KeydroidxIcons.ICON_HELP,
                null,
                getString(topic.titleRes),
                getString(topic.subtitleRes)
            )
            row.setOnClickListener {
                focusHelper?.setFocusIndex(focusables.indexOf(row))
                openTopic(topic)
            }
            container.addView(row)
            focusables.add(row)
        }

        val helper = KeydroidxListFocusHelper(scroll)
        helper.setItems(focusables)
        if (focusables.isNotEmpty()) {
            helper.setFocusIndex(0)
        }
        focusHelper = helper
    }

    /** 功能分类清单（顺序即展示顺序）。新增讲解只需往这里加一条。 */
    private fun topics(): List<Topic> = listOf(
        Topic(
            titleRes = R.string.keyphone_help_reading,
            subtitleRes = R.string.keyphone_help_reading_subtitle,
            bodyRes = R.string.keyphone_help_reading_body
        ),
        Topic(
            titleRes = R.string.keyphone_help_cursor,
            subtitleRes = R.string.keyphone_help_cursor_subtitle,
            bodyRes = R.string.keyphone_help_cursor_body
        ),
        Topic(
            titleRes = R.string.keyphone_help_menu,
            subtitleRes = R.string.keyphone_help_menu_subtitle,
            bodyRes = R.string.keyphone_help_menu_body
        )
    )

    private fun openTopic(topic: Topic) {
        try {
            startActivity(
                Intent(this, KeyphoneHelpDetailActivity::class.java)
                    .putExtra(KeyphoneHelpDetailActivity.EXTRA_TITLE_RES, topic.titleRes)
                    .putExtra(KeyphoneHelpDetailActivity.EXTRA_BODY_RES, topic.bodyRes)
            )
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "打开帮助详情失败: ${topic.titleRes}", t)
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

            else -> super.onAction(action)
        }
    }

    /** 帮助分类：列表标题 / 副标题 / 讲解正文（正文页按资源 id 取文案）。 */
    private data class Topic(
        @StringRes val titleRes: Int,
        @StringRes val subtitleRes: Int,
        @StringRes val bodyRes: Int
    )

    companion object {
        private const val TAG = "KeyphoneHelp"
    }
}
