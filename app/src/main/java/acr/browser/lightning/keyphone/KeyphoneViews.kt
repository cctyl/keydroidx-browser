package acr.browser.lightning.keyphone

import acr.browser.lightning.graphics.LetterImagePainter
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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

    /**
     * 浏览页顶栏地址输入框（普通浏览器地址栏语义，可直接输入/编辑网址）。
     *
     * 与首页搜索框同一视觉（半透明圆角 + accent 描边），但本体是 [EditText]：
     * 单行、URI 输入类型（不触发联想/纠错）、不叠默认下划线背景，字号走 `keydroidx_font_body` Token。
     * 高度由调用方通过 LayoutParams 决定（顶栏高度有限，故用 WRAP_CONTENT 而非 MATCH_PARENT）。
     */
    fun addressBar(context: Context, hintText: CharSequence): EditText {
        val theme = KeydroidxTheme.getCurrentTheme(context)
        val input = EditText(context)
        input.hint = hintText
        input.setTextColor(theme.textColor)
        input.setHintTextColor(theme.subTextColor)
        input.includeFontPadding = false
        input.isSingleLine = true
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        input.imeOptions = EditorInfo.IME_ACTION_GO
        applyFontSize(input, R.dimen.keydroidx_font_body)
        input.setPadding(dp(context, 6f), dp(context, 2f), dp(context, 6f), dp(context, 2f))

        val bg = GradientDrawable()
        bg.cornerRadius = dp(context, 6f).toFloat()
        bg.setColor(0x14E8EEF5)
        bg.setStroke(dp(context, 1f), 0x80000000.toInt() or (theme.accentColor and 0x00FFFFFF))
        input.background = bg

        // 触屏场景：点击即可进入编辑（物理按键的选中方式暂不在本页处理）
        input.isFocusable = true
        input.isFocusableInTouchMode = true
        return input
    }

    /**
     * 地址栏槽位的专属按键适配提示条（标定模式时等高替换地址输入框）。
     * 字号/内边距与 [addressBar] 完全一致：替换前后容器总高不变，不挤动下方 WebView 布局，
     * 保证标定的比例坐标在标定前后落在同一物理位置。
     */
    fun addressBarHint(context: Context): TextView {
        val hint = TextView(context)
        hint.includeFontPadding = false
        hint.isSingleLine = true
        hint.ellipsize = TextUtils.TruncateAt.END
        hint.setTextColor(0xFFF39800.toInt())
        applyFontSize(hint, R.dimen.keydroidx_font_body)
        hint.setPadding(dp(context, 6f), dp(context, 2f), dp(context, 6f), dp(context, 2f))
        return hint
    }

    /**
     * 标签页列表行（长方形卡片）：左侧页面预览缩略图 + 右侧标题。
     *
     * 注意卡片背景只能放在**行内的子 View** 上：`KeydroidxListPageFragment` 用行根 View 承载焦点高亮，
     * 失焦时会 `setBackgroundResource(0)` 把背景清掉，背景放在行根会被清没。
     *
     * @return [TabRow.preview] 由调用方异步解码预览图后 `setImageBitmap`。
     */
    fun tabRow(context: Context, title: CharSequence, isCurrent: Boolean): TabRow {
        // title 由调用方给出最终展示文案（「当前 · 」前缀在调用方拼，这里不依赖 app 的 R）
        val theme = KeydroidxTheme.getCurrentTheme(context)
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(context, 54f)
        // 红线 #8：行根必须可聚焦，防首键被吞
        row.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        row.isFocusable = true
        row.isFocusableInTouchMode = true
        row.isClickable = true

        val card = LinearLayout(context)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.minimumHeight = dp(context, 46f)
        card.setPadding(dp(context, 4f), dp(context, 4f), dp(context, 6f), dp(context, 4f))
        val cardBg = GradientDrawable()
        cardBg.cornerRadius = dp(context, 4f).toFloat()
        cardBg.setColor(0x14E8EEF5)
        cardBg.setStroke(
            dp(context, if (isCurrent) 2f else 1f),
            if (isCurrent) theme.accentColor else 0x80000000.toInt() or (theme.subTextColor and 0x00FFFFFF)
        )
        card.background = cardBg
        row.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(context, 2f)
                bottomMargin = dp(context, 2f)
            }
        )

        // 页面预览缩略图（竖版页面截图，按顶部裁切显示）
        val preview = ImageView(context)
        preview.scaleType = ImageView.ScaleType.CENTER_CROP
        preview.setBackgroundColor(0x33000000)
        card.addView(
            preview,
            LinearLayout.LayoutParams(dp(context, 40f), dp(context, 38f))
        )

        val label = TextView(context)
        label.text = title
        label.setTextColor(if (isCurrent) theme.accentColor else theme.textColor)
        applyFontSize(label, R.dimen.keydroidx_font_body)
        label.includeFontPadding = false
        label.isSingleLine = true
        label.ellipsize = TextUtils.TruncateAt.END
        card.addView(
            label,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(context, 6f)
            }
        )

        // 删除按钮：默认收起（GONE），由列表页在「待删态」下露出（方向左键）。
        // 图标走 KeydroidxIcons 字体图标（红线 #13），配色取自主题（红线 #11）。
        val delete = buildIconView(context, KeydroidxIcons.ICON_DELETE)
        delete.setTextColor(theme.darkColor)
        val deleteBg = GradientDrawable()
        deleteBg.cornerRadius = dp(context, 4f).toFloat()
        deleteBg.setColor(theme.accentColor)
        delete.background = deleteBg
        delete.visibility = View.GONE
        card.addView(
            delete,
            LinearLayout.LayoutParams(dp(context, 26f), dp(context, 26f)).apply {
                marginStart = dp(context, 4f)
            }
        )

        return TabRow(row, preview, label, delete)
    }

    /** [tabRow] 的产物：行根（焦点项）+ 预览图 + 标题 + 删除按钮（默认 GONE，待删态露出）。 */
    class TabRow internal constructor(
        val root: LinearLayout,
        val preview: ImageView,
        val label: TextView,
        val delete: TextView
    )

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

        val cells = ArrayList<BookmarkCell>(titles.size)
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

    /** 单个书签图标格：圆角方形字母图标 + 名称（名称单行截断）+ 右上角删除角标（编辑态显示）。 */
    private fun buildBookmarkCell(context: Context, title: CharSequence): BookmarkCell {
        val theme = KeydroidxTheme.getCurrentTheme(context)
        val cell = BookmarkCell(context)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER_HORIZONTAL
        cell.minimumHeight = dp(context, 46f)
        cell.setPadding(dp(context, 2f), dp(context, 4f), dp(context, 2f), dp(context, 4f))
        cell.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        // 红线 #8：格子自身声明 focusable，防首键被吞
        cell.isFocusable = true
        cell.isFocusableInTouchMode = true
        cell.isClickable = true
        cell.isLongClickable = true

        val letter = title.firstOrNull { !it.isWhitespace() } ?: '?'

        val iconWrapper = FrameLayout(context)

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
        iconWrapper.addView(
            avatar,
            FrameLayout.LayoutParams(avatarSize, avatarSize, Gravity.CENTER)
        )

        val closeBadge = TextView(context)
        KeydroidxIcons.setIcon(closeBadge, KeydroidxIcons.ICON_CLOSE)
        closeBadge.gravity = Gravity.CENTER
        closeBadge.setTextColor(Color.WHITE)
        closeBadge.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(context, 9f).toFloat())
        closeBadge.includeFontPadding = false
        val badgeBg = GradientDrawable()
        badgeBg.shape = GradientDrawable.OVAL
        badgeBg.setColor(0xFFE53935.toInt())
        closeBadge.background = badgeBg
        val badgeSize = dp(context, 13f)
        closeBadge.visibility = View.GONE
        iconWrapper.addView(
            closeBadge,
            FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.TOP or Gravity.END)
        )

        val wrapperSize = dp(context, 32f)
        cell.addView(iconWrapper, LinearLayout.LayoutParams(wrapperSize, wrapperSize))

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
        cell.closeBadge = closeBadge
        return cell
    }

    /** 书签格子 View，支持编辑模式下显隐右上角删除角标 */
    class BookmarkCell(context: Context) : LinearLayout(context) {
        lateinit var closeBadge: TextView internal set

        fun setEditMode(editMode: Boolean) {
            closeBadge.visibility = if (editMode) View.VISIBLE else View.GONE
        }
    }

    /** [bookmarkGrid] 的产物：容器 + 按顺序排列的图标格（与书签列表一一对应）。 */
    class BookmarkGrid internal constructor(
        val container: LinearLayout,
        val cells: List<BookmarkCell>
    ) {
        fun setEditMode(editMode: Boolean) {
            cells.forEach { it.setEditMode(editMode) }
        }
    }

    /** UC 风格顶部标签栏项数据 */
    data class UcTabItem(
        val id: Int,
        val title: String,
        val isSelected: Boolean
    )

    /** UC 风格数字快捷菜单条目数据 */
    data class QuickMenuItem(
        val keyLabel: String,
        val text: String,
        val action: () -> Unit
    )

    /** UC 风格数字快捷菜单单个条目 View */
    fun quickMenuItem(
        context: Context,
        item: QuickMenuItem,
        isFocused: Boolean,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(context, 21f)
        row.setPadding(dp(context, 7f), dp(context, 2f), dp(context, 7f), dp(context, 2f))
        row.isClickable = true
        row.isFocusable = false
        row.isFocusableInTouchMode = false

        val textView = TextView(context)
        textView.text = item.text
        textView.includeFontPadding = false
        textView.isSingleLine = true
        applyFontSize(textView, R.dimen.keydroidx_font_caption)

        val bg = GradientDrawable()
        bg.cornerRadius = dp(context, 2f).toFloat()

        if (isFocused) {
            bg.setColor(0xFF4A90E2.toInt())
            textView.setTextColor(Color.WHITE)
            textView.paint.isFakeBoldText = true
        } else {
            bg.setColor(Color.TRANSPARENT)
            textView.setTextColor(0xFFD0DDEB.toInt())
            textView.paint.isFakeBoldText = false
        }
        row.background = bg
        row.addView(textView)
        row.setOnClickListener { onClick() }
        return row
    }

    /**
     * UC 风格顶部标签栏单个标签 View：
     * 选中状态高亮天蓝底色 + 白色粗体字；未选中状态深蓝底色 + 边框 + 浅灰蓝字。
     */
    fun ucTabCell(
        context: Context,
        item: UcTabItem,
        onTabClicked: (UcTabItem) -> Unit
    ): View {
        val cell = LinearLayout(context)
        cell.orientation = LinearLayout.HORIZONTAL
        cell.gravity = Gravity.CENTER
        cell.minimumHeight = dp(context, 24f)
        cell.setPadding(dp(context, 6f), dp(context, 2f), dp(context, 6f), dp(context, 2f))
        cell.isFocusable = false
        cell.isFocusableInTouchMode = false
        cell.isClickable = true

        val textView = TextView(context)
        textView.text = item.title
        textView.includeFontPadding = false
        textView.isSingleLine = true
        textView.ellipsize = TextUtils.TruncateAt.END
        textView.gravity = Gravity.CENTER
        applyFontSize(textView, R.dimen.keydroidx_font_caption)

        val bg = GradientDrawable()
        bg.cornerRadius = dp(context, 3f).toFloat()

        if (item.isSelected) {
            bg.setColor(0xFF4A90E2.toInt())
            textView.setTextColor(Color.WHITE)
            textView.paint.isFakeBoldText = true
        } else {
            bg.setColor(0xFF203248.toInt())
            bg.setStroke(dp(context, 1f), 0xFF354B66.toInt())
            textView.setTextColor(0xFFB0C4DE.toInt())
            textView.paint.isFakeBoldText = false
        }
        cell.background = bg

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val minW = dp(context, 48f)
        val maxW = dp(context, 86f)
        cell.minimumWidth = minW
        textView.maxWidth = maxW - dp(context, 12f)

        lp.marginStart = dp(context, 2f)
        lp.marginEnd = dp(context, 2f)
        cell.layoutParams = lp

        cell.addView(textView)
        cell.setOnClickListener { onTabClicked(item) }
        return cell
    }

    /**
     * 正在下载的条目包装类（支持就地更新进度，避免列表全量重建导致焦点跳动）。
     */
    class DownloadProgressRow(
        val row: LinearLayout,
        private val subtitleView: TextView,
        private val progressBar: android.widget.ProgressBar
    ) {
        fun update(statusText: String, progress: Int, isIndeterminate: Boolean) {
            subtitleView.text = statusText
            progressBar.isIndeterminate = isIndeterminate
            if (!isIndeterminate) {
                progressBar.progress = progress.coerceIn(0, 100)
            }
        }
    }

    /**
     * 构造正在下载条目（带同步图标、标题、状态/大小副标题以及 3dp 极细复古进度条）。
     */
    fun downloadProgressRow(
        context: Context,
        title: CharSequence,
        statusText: String,
        progress: Int,
        isIndeterminate: Boolean
    ): DownloadProgressRow {
        val theme = KeydroidxTheme.getCurrentTheme(context)
        val row = LinearLayout(context)
        applyRowBase(context, row, 54f)

        // 1. 左侧图标（同步/下载中）
        val iconView = buildIconView(context, KeydroidxIcons.SYNC)
        row.addView(
            iconView,
            LinearLayout.LayoutParams(dp(context, 22f), ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // 2. 中间文本与进度条列
        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER_VERTICAL

        val titleView = TextView(context)
        titleView.text = title
        titleView.setTextColor(theme.textColor)
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

        val subView = TextView(context)
        subView.text = statusText
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

        // 3. 复古极细水平进度条 (3dp)
        val progressBar = android.widget.ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal
        )
        progressBar.max = 100
        progressBar.isIndeterminate = isIndeterminate
        if (!isIndeterminate) {
            progressBar.progress = progress.coerceIn(0, 100)
        }

        // 自定义进度条背景与高亮色 Drawable (6dp 显眼高对比度按键机复古槽)
        val bgTrack = GradientDrawable().apply {
            cornerRadius = dp(context, 3f).toFloat()
            setColor(0x40000000.toInt()) // 半透明深黑底槽
            setStroke(dp(context, 1f), 0x4DFFFFFF) // 细白框
        }
        val fgProgress = GradientDrawable().apply {
            cornerRadius = dp(context, 3f).toFloat()
            setColor(0xFFFDE047.toInt()) // 亮黄醒目填充色
        }
        val clipProgress = android.graphics.drawable.ClipDrawable(
            fgProgress,
            Gravity.START,
            android.graphics.drawable.ClipDrawable.HORIZONTAL
        )
        val layers = android.graphics.drawable.LayerDrawable(arrayOf(bgTrack, clipProgress)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
        progressBar.progressDrawable = layers

        val pbLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(context, 6f)
        ).apply {
            topMargin = dp(context, 5f)
            bottomMargin = dp(context, 4f)
        }
        column.addView(progressBar, pbLp)

        val colLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        colLp.marginStart = dp(context, 8f)
        row.addView(column, colLp)

        return DownloadProgressRow(row, subView, progressBar)
    }
}
