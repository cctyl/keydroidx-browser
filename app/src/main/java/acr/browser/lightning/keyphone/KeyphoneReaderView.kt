package acr.browser.lightning.keyphone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Paint
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import acr.browser.lightning.R
import io.github.cctyl.nokia.common.log.KeydroidxLog
import io.github.cctyl.nokia.common.ui.KeydroidxFontManager
import java.util.Date

/**
 * 阅读模式显示层：**单 TextView + 分页游标**（无 ScrollView、无 RecyclerView）。
 *
 * 为什么要有这个类（见 docs/reading-mode-native-textview-plan.md）：旧实现把正文渲染在
 * WebView 里，阅读态的三套常驻心跳（800ms JS pump 强制 layout + 3s 帧合成探针 +
 * 2s 状态采样）让渲染进程永远不空闲，长读 71 分钟后被系统以 `excessive cpu` 杀掉，
 * 用户看到的就是「文字全部消失只剩背景色」。现在正文显示在**原生 View** 上：
 * WebView 只在阅读态隐藏起来当取章工具，它死掉 / 变慢都不影响当前这一屏。
 *
 * 分页由 [KeyphoneReaderPager] 负责（纯逻辑 + 单测），本类负责：
 * - 把当前页文本喂给 TextView（跨章拼接、页边界测量在 [StaticLayoutMeasurer] 里）；
 * - 字号（1 / 3）与背景（7）直接改原生属性，**不再经过 JS**，也不触发重排 WebView；
 * - 队列快用完时通过 [onNeedMoreChapters] 通知宿主取下一章（事件驱动，无定时器）。
 */
class KeyphoneReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 读到队列末尾：宿主应立刻取下一章（同步取章，返回后 appendChapter 即可继续翻） */
    var onNeedMoreChapters: (() -> Unit)? = null

    /** 当前页所属章节变化（宿主据此更新 lastUrl / 历史 / 落盘快照） */
    var onChapterShown: ((KeyphoneReaderPager.ChapterText) -> Unit)? = null

    private val textView = TextView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        gravity = Gravity.START or Gravity.TOP
        // 阅读正文一律左对齐、可换行；不做任何滚动，翻页靠游标
        setLineSpacing(0f, lineSpacingMultiplier)
        includeFontPadding = true
        isFocusable = false
        isFocusableInTouchMode = false
        ellipsize = null
        maxLines = Int.MAX_VALUE
        typeface = KeydroidxFontManager.getTypeface(context)
    }

    /**
     * 页脚状态条（右下角：电量 + 当前时间）。
     *
     * 只占**一条内边距高度**的窄条，条高会从正文可用高度里扣掉（`updateSpec` 用
     * `textView.paddingBottom` 算可用高），所以正文绝不会被它盖住 —— 宁可少排一行，
     * 也不做「浮在字上」那种会吞字的叠加层。
     */
    private val statusView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, STATUS_TEXT_SP)
        includeFontPadding = false
        isSingleLine = true
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        isFocusable = false
        isFocusableInTouchMode = false
        typeface = KeydroidxFontManager.getTypeface(context)
    }

    /**
     * 页脚条额外占用的高度（px）= 正文底部内边距 + 一点点。
     *
     * 页脚条本身用「底部内边距 + 这一点」当高度，正好落在正文区下方那条空隙里，
     * 所以只从可用高里扣这几像素（14dp 全扣会白丢一行）。
     */
    private val statusReservePx: Int
        get() = (STATUS_RESERVE_DP * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private val pager = KeyphoneReaderPager(StaticLayoutMeasurer())

    private var themeIndex = 0
    private var fontIndex = KeyphoneReaderThemes.DEFAULT_FONT_INDEX

    /** 上一次已经通知过宿主的章节（避免每翻一页都回调） */
    private var lastNotifiedChapter: KeyphoneReaderPager.ChapterText? = null

    init {
        setBackgroundColor(KeyphoneReaderThemes.theme(themeIndex).bg)
        val padding = (KeyphoneReaderThemes.PADDING_DP * resources.displayMetrics.density).toInt()
        val reserve = statusReservePx
        // 底部多留 reserve：页脚条画在「内边距 + reserve」这条带子里，正文不会压到它上面
        textView.setPadding(padding, padding, padding, padding + reserve)
        addView(textView)
        addView(
            statusView,
            LayoutParams(LayoutParams.MATCH_PARENT, padding + reserve, Gravity.BOTTOM).apply {
                setMargins(padding, 0, padding, 0)
            }
        )
        applyAppearance()
    }

    /** 每整分钟由系统 `ACTION_TIME_TICK` 触发一次；不在页面可见时注销，静止阅读零开销 */
    private val timeTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            updateStatus()
        }
    }

    private var timeTickRegistered = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!timeTickRegistered) {
            val filter = IntentFilter(Intent.ACTION_TIME_TICK)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(timeTickReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(timeTickReceiver, filter)
                }
                timeTickRegistered = true
            } catch (t: Throwable) {
                KeydroidxLog.w(TAG, "页脚时间订阅失败（不影响阅读）", t)
            }
        }
        updateStatus()
    }

    override fun onDetachedFromWindow() {
        if (timeTickRegistered) {
            try {
                context.unregisterReceiver(timeTickReceiver)
            } catch (t: Throwable) {
                KeydroidxLog.w(TAG, "页脚时间退订失败", t)
            }
            timeTickRegistered = false
        }
        super.onDetachedFromWindow()
    }

    /**
     * 刷新右下角的「电量 + 时间」。
     *
     * 电量直接查 [BatteryManager.BATTERY_PROPERTY_CAPACITY]（一次属性读取，无广播、无常驻订阅）；
     * 时间用系统格式（跟随用户的 12/24 小时制设置）。
     */
    private fun updateStatus() {
        val clock = DateFormat.getTimeFormat(context).format(Date())
        val percent = batteryPercent()
        statusView.text = if (percent >= 0) "$percent%  $clock" else clock
        statusView.setTextColor(KeyphoneReaderThemes.theme(themeIndex).dim)
    }

    private fun batteryPercent(): Int {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val capacity = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (capacity in 0..100) return capacity
        // 个别机型属性不可用：退回 sticky 广播读一次（registerReceiver 传 null 不会注册接收器）
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) level * 100 / scale else -1
        } catch (t: Throwable) {
            -1
        }
    }

    /** 正文默认字号（px），供宿主提示「字号 N」 */
    val fontSizePx: Int get() = pager.textSizePx

    val currentThemeIndex: Int get() = themeIndex

    val chapterCount: Int get() = pager.chapterCount

    val currentChapter: KeyphoneReaderPager.ChapterText? get() = pager.currentChapter

    val chapterUrls: List<String> get() = pager.chapterUrls()

    /** 宿主进入阅读前先同步一次落盘的外观偏好（字号 px / 主题序号） */
    fun applyPreferences(fontPx: Int, theme: Int) {
        fontIndex = KeyphoneReaderThemes.nearestFontIndex(fontPx)
        themeIndex = theme.coerceIn(0, KeyphoneReaderThemes.count - 1)
        applyAppearance()
    }

    /** 开始阅读一章：清空队列并停在标题页 */
    fun startChapter(chapter: KeyphoneReaderPager.ChapterText) {
        pager.reset(chapter)
        lastNotifiedChapter = null
        render()
    }

    /** 追加取到的一章（章节队列有界，见 [KeyphoneReaderPager.append]） */
    fun appendChapter(chapter: KeyphoneReaderPager.ChapterText): Boolean {
        val added = pager.append(chapter)
        if (added) {
            // 当前页文本可能因队列/缓存变化而需要重画（代价一次二分，无重排 WebView）
            render()
        }
        return added
    }

    /** 清空（退出阅读） */
    fun clear() {
        pager.clear()
        lastNotifiedChapter = null
        currentRenderedPage = null
        textView.text = ""
    }

    /** 下一页；返回 false 表示队列里没有下一页（宿主去取下一章） */
    fun nextPage(): Boolean = measured("next") {
        if (!pager.next()) return@measured false
        render()
        true
    }

    /** 上一页 */
    fun prevPage(): Boolean = measured("prev") {
        if (!pager.prev()) return@measured false
        render()
        true
    }

    /**
     * 按键 1 / 3：减小 / 增大字号。**不换页序**——游标 startOffset 不动，只重新量 endOffset，
     * 于是「已看到的这一段开头」稳定（规范 §4.5）。
     *
     * @return 新字号（px），已到档位边界时返回原值
     */
    fun changeFont(delta: Int): Int {
        val next = (fontIndex + if (delta > 0) 1 else -1)
            .coerceIn(0, KeyphoneReaderThemes.FONT_SIZES.size - 1)
        if (next != fontIndex) {
            measured("font${if (delta > 0) "+" else "-"}") {
                fontIndex = next
                applyAppearance()
                render()
            }
        }
        return KeyphoneReaderThemes.FONT_SIZES[fontIndex]
    }

    /**
     * 记录翻页 / 换字号的主线程耗时（ANR 修复的验收口径：单次 ≤ 50ms，见
     * docs/reading-mode-anr-fix-20261022.md §五）。慢于 [SLOW_PAGE_COST_MS] 用 WARN，
     * 真机上 `adb logcat -s KeyphoneReaderView` 即可核对。
     */
    private inline fun <T> measured(what: String, block: () -> T): T {
        val startedAt = SystemClock.elapsedRealtime()
        val result = block()
        val cost = SystemClock.elapsedRealtime() - startedAt
        if (cost >= SLOW_PAGE_COST_MS) {
            KeydroidxLog.w(
                TAG,
                "【阅读分页耗时】$what=${cost}ms 章=${pager.currentChapter?.ordinal}" +
                    " 队列=${pager.chapterCount} 字号=${pager.textSizePx}px"
            )
        } else {
            KeydroidxLog.d(TAG, "阅读分页耗时 $what=${cost}ms")
        }
        return result
    }

    /** 按键 7：轮换背景主题，返回新序号（只换颜色，不触发重新分页） */
    fun cycleTheme(): Int {
        themeIndex = (themeIndex + 1) % KeyphoneReaderThemes.count
        applyTheme()
        // 标题页字色与正文不同，重画一次保证观感一致（不做分页测量，开销可忽略）
        render(remeasure = false)
        return themeIndex
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateSpec(w, h)
        render()
    }

    private fun updateSpec(width: Int, height: Int) {
        val horizontalPadding = textView.paddingLeft + textView.paddingRight
        val verticalPadding = textView.paddingTop + textView.paddingBottom
        val usableWidth = (width - horizontalPadding).coerceAtLeast(0)
        val usableHeight = (height - verticalPadding).coerceAtLeast(0)
        val textSize = KeyphoneReaderThemes.FONT_SIZES[fontIndex]
        // 行距依赖「最终视口高度」（要把余量均摊到整数行），所以必须在这里定、而不是在 applyAppearance
        textView.setLineSpacing(0f, lineMultiplierFor(textSize.toFloat(), usableHeight))
        pager.setSpec(usableWidth, usableHeight, textSize)
    }

    /** 字号 / 字体变化后同步 TextView 与实际测量口径（两者必须完全一致，否则会裁字） */
    private fun applyAppearance() {
        val px = KeyphoneReaderThemes.FONT_SIZES[fontIndex]
        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, px.toFloat())
        applyTheme()
        updateSpec(width, height)
    }

    private fun applyTheme() {
        val theme = KeyphoneReaderThemes.theme(themeIndex)
        setBackgroundColor(theme.bg)
        textView.setTextColor(theme.text)
        statusView.setTextColor(theme.dim)
    }

    /**
     * 把当前页画到 TextView 上。
     *
     * @param remeasure false 表示只换颜色（主题轮换），不重新取页文本
     */
    private fun render(remeasure: Boolean = true) {
        val page = if (remeasure) pager.currentPage() else currentRenderedPage ?: pager.currentPage()
        if (page == null) {
            textView.text = ""
            return
        }
        currentRenderedPage = page
        val theme = KeyphoneReaderThemes.theme(themeIndex)
        if (page.isTitlePage) {
            textView.gravity = Gravity.CENTER
            textView.setTextColor(theme.head)
            textView.text = buildTitlePageText(page.chapter)
        } else {
            textView.gravity = Gravity.START or Gravity.TOP
            textView.setTextColor(theme.text)
            textView.text = page.text
            // 分页边界日志：排查「页面以孤字标点开头 / 段尾丢字」时看这条
            KeydroidxLog.d(
                TAG,
                "阅读页 [${page.startOffset},${page.endOffset}) 章=${page.chapter.ordinal}" +
                    " 首='${page.text.take(8)}' 尾='${page.text.takeLast(8)}'"
            )
            // 核对「StaticLayout 测量」与「TextView 实际排版」是否一致：
            // 实际高度 > 视口高度 就一定会裁掉页尾（用户看到的是「最后一行显示不全」）
            textView.post {
                val layout = textView.layout
                KeydroidxLog.d(
                    TAG,
                    "排版核对 实际高=${layout?.height} 行=${layout?.lineCount}" +
                        " 视口高=${textView.height - textView.paddingTop - textView.paddingBottom}" +
                        " 可用宽=${textView.width - textView.paddingLeft - textView.paddingRight}" +
                        " 字号=${KeyphoneReaderThemes.FONT_SIZES[fontIndex]}"
                )
            }
        }
        if (lastNotifiedChapter !== page.chapter) {
            lastNotifiedChapter = page.chapter
            onChapterShown?.invoke(page.chapter)
        }
        if (pager.needsMoreChapters()) {
            onNeedMoreChapters?.invoke()
        }
    }

    /** 当前已渲染的页（主题轮换时不重新测量，直接复用） */
    private var currentRenderedPage: KeyphoneReaderPager.ReaderPage? = null

    /** 单行自然高按字号缓存（探针要构造两次 StaticLayout，换字号才值得重算） */
    private var naturalSizeCache = -1f
    private var naturalValueCache = 0f

    /** 标定出的行距倍数按「字号 + 视口高」缓存（每次翻页都会问，不能每次重标） */
    private var multCacheSize = -1f
    private var multCacheHeight = -1
    private var multCacheValue = 0f

    private fun buildTitlePageText(chapter: KeyphoneReaderPager.ChapterText): CharSequence {
        val head = context.getString(R.string.keyphone_reading_chapter_title, chapter.ordinal)
        if (chapter.title.isBlank()) return head
        return TextUtils.concat(head, "\n", chapter.title)
    }

    /**
     * 行高倍数：既遵守主题的「行高 1.8」，又保证**整页刚好填满视口**。
     *
     * 1) 基准倍数 = `字号 × 1.8 / 单行自然高`。自然高必须用**含回退字体**的行高量出来：
     *    正文是中文，字形来自回退字体，它的 ascent/descent 比主字体大一圈
     *    （20px 字号实测：主字体 23px / 回退字体 28px）。按主字体算倍数，
     *    实际行高会变成设计值的 1.2 倍 —— 一页白少两三行，还永远在底部留一条缝。
     * 2) 再把余量均摊：行数取 `floor(视口高 / 基准行高)`，每行高度取 `floor(视口高 / 行数)`。
     *    于是每页最后一行正好压到视口底部，不会再有「底部空出大半行」的观感
     *    （用户报的「底部多出一条空行」）。
     *
     * 行高只会比设计值略大、不会更小（`floor` 的性质保证），所以不会把字排挤。
     */
    private fun lineMultiplierFor(textSizePx: Float, viewportHeightPx: Int): Float {
        if (viewportHeightPx <= 0) return baseMultiplier(textSizePx)
        if (multCacheSize == textSizePx && multCacheHeight == viewportHeightPx && multCacheValue > 0f) {
            return multCacheValue
        }
        val value = calibrateMultiplier(textSizePx, viewportHeightPx)
        multCacheSize = textSizePx
        multCacheHeight = viewportHeightPx
        multCacheValue = value
        return value
    }

    /** 只按「字号 × 1.8」算的基准倍数（视口未知时用） */
    private fun baseMultiplier(textSizePx: Float): Float {
        val natural = naturalLineHeight(textSizePx)
        val desired = textSizePx * KeyphoneReaderThemes.LINE_SPACING
        return (desired / natural).coerceIn(1f, 4f)
    }

    /**
     * 标定行距倍数：让「整屏行数 × 行高」正好等于视口高度。
     *
     * 不能只靠解析式算：`StaticLayout` 每行高度是**整数截断**的，按公式算出来的倍数
     * 会差 1~3px/屏（实测 10 行差 16px，就是用户看到的「底部空出一条」）。
     * 这里用真实排版迭代两三次（每行高度对倍数是线性的，一次按比例修正就收敛）。
     */
    private fun calibrateMultiplier(textSizePx: Float, viewportHeightPx: Int): Float {
        val natural = naturalLineHeight(textSizePx)
        val base = baseMultiplier(textSizePx)
        val lines = (viewportHeightPx / (natural * base)).toInt().coerceAtLeast(1)
        val probe = buildString {
            repeat(lines) { if (isNotEmpty()) append('\n'); append('中') }
        }
        // 只接受「排得进视口」的解，并取其中最满的那个：宁可少 1px，也绝不能多 1px
        // （多 1px 就意味着这一页装不下 lines 行，底部反而会空出整整一行）
        var best = base
        var bestHeight = -1
        var multiplier = base
        repeat(CALIBRATE_ROUNDS) {
            val height = measureHeight(probe, textSizePx, multiplier)
            if (height <= 0) return best
            if (height <= viewportHeightPx && height > bestHeight) {
                bestHeight = height
                best = multiplier
            }
            if (height == viewportHeightPx) return multiplier
            // 行高对倍数是线性的：按比例修正一次基本到位
            multiplier = (multiplier * viewportHeightPx / height.toFloat()).coerceIn(1f, 4f)
        }
        return best
    }

    /**
     * 单行自然行高（倍数 1.0，含回退字体与 `includePad`）。
     *
     * 用「两行高 - 一行高」求：`includePad` 的上下 padding 只在首尾各出现一次，
     * 直接拿总高除行数会算偏。结果按字号缓存（换字号才重算）。
     */
    private fun naturalLineHeight(textSizePx: Float): Float {
        if (naturalSizeCache == textSizePx && naturalValueCache > 0f) return naturalValueCache
        val value = try {
            val one = measureHeight("中", textSizePx, 1f)
            val two = measureHeight("中\n中", textSizePx, 1f)
            (two - one).toFloat().coerceAtLeast(1f)
        } catch (t: Throwable) {
            KeydroidxLog.w(TAG, "行高探针失败，回退到字号 ×1.2", t)
            textSizePx * 1.2f
        }
        naturalSizeCache = textSizePx
        naturalValueCache = value
        return value
    }

    private fun measureHeight(text: String, textSizePx: Float, multiplier: Float): Int {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSizePx
            typeface = textView.typeface
        }
        val builder = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, NATURAL_MEASURE_WIDTH)
            .setLineSpacing(0f, multiplier)
            .setIncludePad(true)
            .setBreakStrategy(textView.breakStrategy)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUseLineSpacingFromFallbacks(textView.isFallbackLineSpacing)
        }
        return builder.build().height
    }

    /**
     * StaticLayout 二分测量：找最大的 `end`，使 `text[start, end)` 排出来的高度不超过视口高度。
     *
     * **测量窗口必须收敛到「一页量级」**（[maxPageChars]）：对整章做 `StaticLayout` 是
     * ANR 的直接成因 —— 万字章一次 `pageEnd` 要构造十几次「近万字」的排版，主线程几百毫秒
     * 起步（见 docs/reading-mode-anr-fix-20261022.md §二 第 2 层）。窗口收到几百字以后，
     * 单页测量是「十几次几百字的排版」，功能机上也在毫秒级。
     */
    private inner class StaticLayoutMeasurer : KeyphoneReaderPager.PageMeasurer {

        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)

        override fun pageEnd(
            text: CharSequence,
            start: Int,
            spec: KeyphoneReaderPager.PageSpec
        ): Int {
            val length = text.length
            if (start >= length) return length
            if (!spec.isUsable) return length
            paint.textSize = spec.textSizePx.toFloat()
            paint.typeface = textView.typeface
            val budget = usableHeight(spec)
            val multiplier = lineMultiplierFor(spec.textSizePx.toFloat(), spec.heightPx)
            var window = maxPageChars(spec)
            while (true) {
                val hi = (start.toLong() + window).coerceAtMost(length.toLong()).toInt()
                if (hi <= start) return length
                val end = largestFittingEnd(text, start, hi, spec, budget, multiplier)
                // 窗口没被填满 = 这一页确实到此为止；窗口右端就是章末也一样
                if (end < hi || hi >= length) {
                    val pulled = pullOrphanPunctuation(text, start, end, spec, multiplier)
                    return logPageEnd(text, start, pulled, spec, budget, multiplier)
                }
                // 窗口右端恰好被填满：可能还有字放得下，扩大窗口再量一次（有上限，最多几轮）
                if (window >= MAX_MEASURE_CHARS) {
                    val pulled = pullOrphanPunctuation(text, start, end, spec, multiplier)
                    return logPageEnd(text, start, pulled, spec, budget, multiplier)
                }
                window = (window * 2).coerceAtMost(MAX_MEASURE_CHARS)
            }
        }

        /** 诊断：把「这一页量出来多高 / 预算是多少」打出来，方便与 TextView 实际排版对比 */
        private fun logPageEnd(
            text: CharSequence,
            start: Int,
            end: Int,
            spec: KeyphoneReaderPager.PageSpec,
            budget: Int,
            multiplier: Float
        ): Int {
            val layout = layoutOf(text, start, end, spec, multiplier)
            KeydroidxLog.d(
                TAG,
                "测量 [$start,$end) 高=${layout.height} 行=${layout.lineCount}" +
                    " 预算=$budget 视口=${spec.heightPx} 宽=${spec.widthPx} 倍数=$multiplier"
            )
            return end
        }

        /**
         * 避头尾：下一页不许以收尾标点开头。
         *
         * 硬切字符时经常切出「。在页面第一行」这种孤字行（观感很差）。这里把这类标点
         * **拉回本页**，前提是拉回来之后仍然排得下（`<= 视口高度`）；排不下就放弃 ——
         * 宁可留一个孤字，也绝不把页尾那一行挤出屏幕（那就是丢字）。
         */
        private fun pullOrphanPunctuation(
            text: CharSequence,
            start: Int,
            end: Int,
            spec: KeyphoneReaderPager.PageSpec,
            multiplier: Float
        ): Int {
            var pulled = end
            var count = 0
            while (pulled < text.length && count < MAX_ORPHAN_PULL && isNoLineStartChar(text[pulled])) {
                val candidate = pulled + 1
                if (heightOf(text, start, candidate, spec, multiplier) > spec.heightPx) break
                pulled = candidate
                count++
            }
            return pulled
        }

        /** 中文排版的「避头」字符（不能出现在行首） */
        private fun isNoLineStartChar(c: Char): Boolean = when (c) {
            '，', '。', '、', '；', '：', '？', '！', '）', '］', '｝', '〕', '〉', '》', '」', '』',
            '”', '’', '…', '～', '·', ',', '.', ';', ':', '?', '!', ')', ']', '}' -> true
            else -> false
        }

        /**
         * 在 `[start, hi]` 内找最大的「排出来不超过预算」的结束位置。
         *
         * 先整窗试一次（大多数页一次就命中，省掉整条二分），再做二分。
         */
        private fun largestFittingEnd(
            text: CharSequence,
            start: Int,
            hi: Int,
            spec: KeyphoneReaderPager.PageSpec,
            budget: Int,
            multiplier: Float
        ): Int {
            if (heightOf(text, start, hi, spec, multiplier) <= budget) return hi
            var lo = start + 1
            var high = hi
            while (lo < high) {
                val mid = (lo + high + 1) / 2
                if (heightOf(text, start, mid, spec, multiplier) <= budget) lo = mid else high = mid - 1
            }
            return lo
        }

        /**
         * 测量预算 = 视口高度（不再预留一行）。
         *
         * 早期版本刻意少排一行，用来兜住「测量比实际排版小」的差异；但那个差异的**真凶**
         * 是 `fallbackLineSpacing` / `breakStrategy` 没跟 TextView 对齐（见 [layoutOf]），
         * 对齐之后测量高度与实际排版逐像素相同（真机核对 326 == 326，行顶 [0,49,98,147]）。
         * 既然口径一致，再白留一行只会让每页少显示一行字；这里改成整屏，靠
         * `排版核对` 日志做长期哨兵。
         */
        private fun usableHeight(spec: KeyphoneReaderPager.PageSpec): Int =
            spec.heightPx.coerceAtLeast(spec.textSizePx)

        /**
         * 一页最多可能放多少字（**上界**，不是估计值）。
         *
         * 行数按「视口高 / 行高」算，每行字数按「视口宽 / 半字号」算（ASCII 平均字宽约 0.5em，
         * CJK 是 1em，取 0.5em 就是往多了算），再乘 [PAGE_CHARS_SAFETY] 作为保险。
         * 估小了会让页尾填不满，所以宁可估大 —— 反正在这个窗口里二分仍然只量几百字。
         */
        private fun maxPageChars(spec: KeyphoneReaderPager.PageSpec): Int {
            val lineHeight = (spec.textSizePx * KeyphoneReaderThemes.LINE_SPACING).coerceAtLeast(1f)
            val lines = (usableHeight(spec) / lineHeight).toInt().coerceAtLeast(1)
            val perLine = (spec.widthPx / (spec.textSizePx * MIN_CHAR_WIDTH_EM))
                .toInt()
                .coerceAtLeast(1)
            val raw = lines.toLong() * perLine.toLong() * PAGE_CHARS_SAFETY
            return raw.coerceIn(MIN_MEASURE_CHARS.toLong(), MAX_MEASURE_CHARS.toLong()).toInt()
        }

        private fun heightOf(
            text: CharSequence,
            start: Int,
            end: Int,
            spec: KeyphoneReaderPager.PageSpec,
            multiplier: Float
        ): Int {
            if (end <= start) return 0
            return try {
                layoutOf(text, start, end, spec, multiplier).height
            } catch (t: Throwable) {
                // 测量失败绝不能把阅读卡死：按「整段放得下」处理，宁可一页多显示
                KeydroidxLog.e(TAG, "阅读分页测量失败 start=$start end=$end", t)
                0
            }
        }

        /**
         * 构造与 **TextView 实际排版完全同口径** 的 StaticLayout。
         *
         * 最容易错的一条是 `fallbackLineSpacing`：TextView 从 Android P 起默认开启，
         * 中日韩字形走回退字体，行高按**回退字体**的 ascent/descent 算（实测 22px 字号：
         * 开=49px/行，关=38px/行）。测量时若不开，量与画会差 30%，
         * 页尾就会多塞一行、渲染时被裁掉 —— 用户看到「最后一行显示不全、下一页开头少字」。
         * （2026-10-22 真机实证：测量 tops=[0,45,83] vs 实际 tops=[0,49,98]。）
         */
        private fun layoutOf(
            text: CharSequence,
            start: Int,
            end: Int,
            spec: KeyphoneReaderPager.PageSpec,
            multiplier: Float
        ): StaticLayout {
            // 显式按「简单断行、不做连字符」排版，与 TextView 的默认口径一致
            // （StaticLayout.Builder 的默认 breakStrategy 就是 BREAK_STRATEGY_SIMPLE，
            // 不再显式设置：它的常量在低版本被标注为 Layout 上的废弃别名，Lint 会报错）
            val builder = StaticLayout.Builder
                .obtain(text, start, end, paint, spec.widthPx.coerceAtLeast(1))
                .setLineSpacing(0f, multiplier)
                .setIncludePad(true)
                // 断行策略跟着 TextView 走（TextView 默认 HIGH_QUALITY，Builder 默认 SIMPLE，
                // 两者对中文标点/连字符的处理不同 → 行数可能差一行）
                .setBreakStrategy(textView.breakStrategy)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // 直接跟随 TextView 的开关（默认开），不要写死 true
                builder.setUseLineSpacingFromFallbacks(textView.isFallbackLineSpacing)
            }
            return builder
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()
        }
    }

    companion object {
        private const val TAG = "KeyphoneReaderView"

        /** 估算「一页最多几个字」时假设的最小字宽（em）：0.5em ≈ ASCII 平均字宽 */
        private const val MIN_CHAR_WIDTH_EM = 0.5f

        /** 上界估计的保险系数（估小了页尾填不满，估大了只是多量几百字） */
        private const val PAGE_CHARS_SAFETY = 2L

        /** 测量窗口下限：极窄视口 / 极大字号也不至于窗口里塞不下一个字 */
        private const val MIN_MEASURE_CHARS = 64

        /** 测量窗口上限：再大就失去「窗口收敛」的意义了 */
        private const val MAX_MEASURE_CHARS = 4096

        /** 翻页 / 换字号的主线程耗时超过它就打 WARN（验收线：50ms，见 ANR 修复方案 §五） */
        private const val SLOW_PAGE_COST_MS = 50L

        /** 避头尾最多往回拉几个标点（「。”」这类连着的两个足够） */
        private const val MAX_ORPHAN_PULL = 2

        /** 量「单行自然高」时的排版宽度：只要够宽不换行即可 */
        private const val NATURAL_MEASURE_WIDTH = 10000

        /** 行距倍数的标定轮数（线性收敛，3 轮足够把误差压到 1px 内） */
        private const val CALIBRATE_ROUNDS = 3

        /** 页脚「电量 + 时间」的字号（sp）；功能机上比正文小一半左右 */
        private const val STATUS_TEXT_SP = 11f

        /**
         * 页脚条**额外**占用的高度（dp）。
         *
         * 页脚条整体高 = 正文底部内边距 + 这个值（10 + 4 = 14dp，正好放下 11sp 的数字），
         * 而可用高只扣这 4dp —— 22px 字号下仍保住 9 行、20px 字号下保住 10 行。
         * 若按整条 14dp 扣，会白丢一行正文。
         */
        private const val STATUS_RESERVE_DP = 4f
    }
}
