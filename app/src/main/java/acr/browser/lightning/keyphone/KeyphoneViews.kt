package acr.browser.lightning.keyphone

import acr.browser.lightning.graphics.LetterImagePainter
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.cctyl.nokia.common.R
import io.github.cctyl.nokia.common.ui.KeydroidxFontManager
import io.github.cctyl.nokia.common.ui.KeydroidxIcons
import io.github.cctyl.nokia.common.ui.KeydroidxTheme
import io.github.cctyl.nokia.common.ui.drawable.KeydroidxDashedLineDrawable
import io.github.cctyl.nokia.common.util.KeydroidxDimens

/**
 * 按键机列表行视图工厂（首页 / 全屏选项页共用）。
 *
 * 规范依据：
 * - 红线 #15：含 TextView 的动态构建列表行，高度一律 WRAP_CONTENT + setMinimumHeight，
 *   禁止写死固定行高（大字号 sFontScale 下行高必须跟随缩放，否则文字被裁）；
 * - 红线 #8：行根 View 声明 focusable + focusableInTouchMode，防首键被吞；
 * - 红线 #11/#12：颜色取自 KeydroidxTheme，字号一律用 @dimen/keydroidx_font_* Token；
 * - 红线 #13：图标走 KeydroidxIcons 字体图标，无 PNG/XML。
 */
object KeyphoneViews {

    private fun dp(context: Context, v: Float): Int = KeydroidxDimens.dp(context.resources, v)

    /**
     * 动态建字统一入口（规范 §6.3）：禁止裸调 setTextSize，
     * 由 SDK 记录设计基准 px，重复应用不漂移。
     */
    private fun applyFontSize(view: TextView, dimenRes: Int) {
        KeydroidxFontManager.setTextSizeResource(view, dimenRes)
    }

    private fun applyRowBase(
        context: Context,
        row: LinearLayout,
        minHeightDp: Float
    ) {
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(context, minHeightDp)
        row.setPadding(
            dp(context, 8f), dp(context, 4f),
            dp(context, 8f), dp(context, 4f)
        )
        // 子 View 不抢焦点，焦点统一由 ListFocusHelper 持在行根 View 上
        row.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        row.isFocusable = true
        row.isFocusableInTouchMode = true
        row.isClickable = true
    }

    private fun buildTextColumn(
        context: Context,
        title: CharSequence,
        titleColor: Int,
        subtitle: String?
    ): LinearLayout {
        val theme = KeydroidxTheme.getCurrentTheme(context)
        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER_VERTICAL

        val titleView = TextView(context)
        titleView.text = title
        titleView.setTextColor(titleColor)
        applyFontSize(titleView, R.dimen.keydroidx_font_body)
        titleView.includeFontPadding = false
        titleView.isSingleLine = true
        titleView.ellipsize = TextUtils.TruncateAt.END
        column.addView(
            titleView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        if (subtitle != null) {
            val subView = TextView(context)
            subView.text = subtitle
            subView.setTextColor(theme.subTextColor)
            applyFontSize(subView, R.dimen.keydroidx_font_caption)
            subView.includeFontPadding = false
            subView.isSingleLine = true
            subView.ellipsize = TextUtils.TruncateAt.END
            subView.setPadding(0, dp(context, 2f), 0, 0)
            column.addView(
                subView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        return column
    }

    private fun buildIconView(context: Context, iconCode: String): TextView {
        val theme = KeydroidxTheme.getCurrentTheme(context)
        val icon = TextView(context)
        KeydroidxIcons.setIcon(icon, iconCode)
        icon.gravity = Gravity.CENTER
        icon.setTextColor(theme.accentColor)
        icon.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(context, 20f).toFloat())
        icon.includeFontPadding = false
        return icon
    }

    private fun buildTagView(context: Context, tagText: String): TextView {
        val theme = KeydroidxTheme.getCurrentTheme(context)
        val tag = TextView(context)
        tag.text = tagText.take(1)
        tag.gravity = Gravity.CENTER
        tag.setTextColor(theme.accentColor)
        applyFontSize(tag, R.dimen.keydroidx_font_caption)
        tag.includeFontPadding = false
        val bg = GradientDrawable()
        bg.cornerRadius = dp(context, 3f).toFloat()
        bg.setColor((theme.accentColor and 0x00FFFFFF) or 0x33000000.toInt())
        tag.background = bg
        return tag
    }

    /**
     * 图标行 / 字母头像行（单行，40dp 起步；两行时 50dp 起步）。
     * iconCode 与 tagText 二选一：图标行走 KeydroidxIcons 字体图标，tagText 渲染首字符头像。
     */
    fun iconRow(
        context: Context,
        iconCode: String?,
        tagText: String?,
        title: CharSequence,
        subtitle: String?
    ): LinearLayout {
        val row = LinearLayout(context)
        applyRowBase(context, row, if (subtitle == null) 40f else 50f)
        require(iconCode != null || tagText != null) { "iconCode or tagText required" }

        if (iconCode != null) {
            row.addView(
                buildIconView(context, iconCode),
                LinearLayout.LayoutParams(dp(context, 22f), ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        } else {
            row.addView(
                buildTagView(context, tagText!!),
                LinearLayout.LayoutParams(dp(context, 20f), dp(context, 20f))
            )
        }

        val column = buildTextColumn(context, title, KeydroidxTheme.getCurrentTheme(context).textColor, subtitle)
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.marginStart = dp(context, 8f)
        row.addView(column, lp)
        return row
    }

    /** 首页顶部搜索框行：等价一个可聚焦的列表项，CENTER 进入全屏编辑页。 */
    fun searchRow(context: Context, hintText: CharSequence): LinearLayout {
        val theme = KeydroidxTheme.getCurrentTheme(context)
        val row = LinearLayout(context)
        applyRowBase(context, row, 44f)

        val bg = GradientDrawable()
        bg.cornerRadius = dp(context, 6f).toFloat()
        bg.setColor(0x14E8EEF5)
        bg.setStroke(dp(context, 1f), 0x80000000.toInt() or (theme.accentColor and 0x00FFFFFF))
        row.background = bg

        row.addView(
            buildIconView(context, KeydroidxIcons.ICON_SEARCH),
            LinearLayout.LayoutParams(dp(context, 22f), ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val hint = TextView(context)
        hint.text = hintText
        hint.setTextColor(theme.subTextColor)
        applyFontSize(hint, R.dimen.keydroidx_font_body)
        hint.includeFontPadding = false
        hint.isSingleLine = true
        hint.ellipsize = TextUtils.TruncateAt.END
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.marginStart = dp(context, 8f)
        row.addView(hint, lp)
        return row
    }

    /** 分组标签（书签 / 历史记录），非焦点项。字号按规范 §3 分组小标题栏 = small_title (11sp)。 */
    fun sectionLabel(context: Context, text: CharSequence): TextView {
        val theme = KeydroidxTheme.getCurrentTheme(context)
        val label = TextView(context)
        label.text = text
        label.setTextColor(theme.subTextColor)
        applyFontSize(label, R.dimen.keydroidx_font_small_title)
        label.includeFontPadding = false
        label.setPadding(
            dp(context, 10f), dp(context, 6f),
            dp(context, 6f), dp(context, 2f)
        )
        return label
    }

    /**
     * 分组分隔虚线（KeydroidxDashedLineDrawable，高 6dp）。
     * 注意：分隔线不声明 focusable，调用方不得把它放进焦点列表。
     */
    fun divider(context: Context): View {
        val theme = KeydroidxTheme.getCurrentTheme(context)
        val divider = View(context)
        // 必须显式指定高度：纯 View 在 WRAP_CONTENT + AT_MOST 测量下会被 getDefaultSize()
        // 撑满父容器剩余空间（TextView 按内容测量故无此问题），会把后续行全部挤成 0 高。
        // 无文字的 View 不受「动态列表行禁止写死行高」红线约束。
        divider.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(context, 6f)
        )
        divider.isFocusable = false
        divider.isFocusableInTouchMode = false
        divider.background = KeydroidxDashedLineDrawable(
            context.resources,
            theme.subTextColor,
            1f,
            3f
        )
        return divider
    }

    /**
     * 书签宫格：横向网格布局（每行 [columns] 个图标格），与「一条一条」的历史列表区分开。
     *
     * 规范依据：宫格卡片主文本走 subText Color、名称为九宫格应用名档位 caption (9sp)、
     * 卡片最小高 46dp（规范 §3 快捷宫格卡片）、格子图标为圆角方形字母图标
     * （配色沿用上游 LetterImagePainter.colorForCharacter，保证与原浏览器观感一致）。
     * 纯 View 的空位占位不受「动态列表行禁止写死行高」约束。
     *
     * @return [BookmarkGrid.container] 需加入页面；[BookmarkGrid.cells] 按传入顺序
     *         与书签一一对应，供调用方做焦点导航与点击绑定。
     */
    fun bookmarkGrid(context: Context, titles: List<CharSequence>, columns: Int): BookmarkGrid {
        require(columns > 0) { "columns must be > 0" }
        val container = LinearLayout(context)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(dp(context, 4f), 0, dp(context, 4f), 0)

        val cells = ArrayList<View>(titles.size)
        titles.chunked(columns).forEach { chunk ->
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            container.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            chunk.forEach { title ->
                val cell = buildBookmarkCell(context, title)
                row.addView(
                    cell,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                cells.add(cell)
            }
            // 末行补空位，保证每格等宽（空格不可聚焦，也不进焦点列表）
            repeat(columns - chunk.size) {
                val spacer = View(context)
                spacer.isFocusable = false
                spacer.isFocusableInTouchMode = false
                spacer.isClickable = false
                row.addView(spacer, LinearLayout.LayoutParams(0, dp(context, 1f), 1f))
            }
        }
        return BookmarkGrid(container, cells)
    }

    /** 单个书签图标格：圆角方形字母图标 + 名称（名称单行截断）。 */
    private fun buildBookmarkCell(context: Context, title: CharSequence): LinearLayout {
        val theme = KeydroidxTheme.getCurrentTheme(context)
        val cell = LinearLayout(context)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER_HORIZONTAL
        cell.minimumHeight = dp(context, 46f)
        cell.setPadding(dp(context, 2f), dp(context, 4f), dp(context, 2f), dp(context, 4f))
        cell.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        // 红线 #8：格子自身声明 focusable，防首键被吞
        cell.isFocusable = true
        cell.isFocusableInTouchMode = true
        cell.isClickable = true

        val letter = title.firstOrNull { !it.isWhitespace() } ?: '?'
        val avatar = TextView(context)
        avatar.text = letter.uppercaseChar().toString()
        avatar.gravity = Gravity.CENTER
        avatar.setTextColor(Color.WHITE)
        avatar.includeFontPadding = false
        applyFontSize(avatar, R.dimen.keydroidx_font_title)
        val avatarBg = GradientDrawable()
        avatarBg.cornerRadius = dp(context, 6f).toFloat()
        avatarBg.setColor(
            ContextCompat.getColor(context, LetterImagePainter.colorForCharacter(letter).resource)
        )
        avatar.background = avatarBg
        val avatarSize = dp(context, 28f)
        cell.addView(avatar, LinearLayout.LayoutParams(avatarSize, avatarSize))

        val name = TextView(context)
        name.text = title
        name.setTextColor(theme.textColor)
        name.gravity = Gravity.CENTER
        name.includeFontPadding = false
        name.isSingleLine = true
        name.ellipsize = TextUtils.TruncateAt.END
        name.setPadding(0, dp(context, 3f), 0, 0)
        applyFontSize(name, R.dimen.keydroidx_font_caption)
        cell.addView(
            name,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return cell
    }

    /** [bookmarkGrid] 的产物：容器 + 按顺序排列的图标格（与书签列表一一对应）。 */
    class BookmarkGrid internal constructor(
        val container: LinearLayout,
        val cells: List<View>
    )
}
