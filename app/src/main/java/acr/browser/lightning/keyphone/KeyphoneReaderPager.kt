package acr.browser.lightning.keyphone

/**
 * 阅读模式分页引擎（**纯逻辑，不依赖 Android 文本排版 API，可独立单测**）。
 *
 * 背景（见 docs/reading-mode-native-textview-plan.md）：阅读显示层从 WebView 换成原生
 * TextView 后，「一屏能放多少字」不再由 CSS + 滚动决定，而要在 Kotlin 侧把正文切成页。
 * 这里只做游标运算，真正的「这段文字在给定宽高下有多高」由外部注入的 [PageMeasurer]
 * 回答（生产实现是 `KeyphoneReaderView` 里的 StaticLayout 二分，单测里是假的等宽测量器），
 * 于是这套分页 / 翻页 / 字号变化的规则可以用普通 JVM 单测覆盖。
 *
 * ## 游标模型
 *
 * 游标只有「章下标 + 章内字符偏移」两项，**页尾偏移永远现算**：
 *
 * ```
 * PageCursor = (chapterIndex, startOffset)   endOffset = measure(startOffset)
 * ```
 *
 * 这样换字号时天然满足规范「startOffset 不变，只调 endOffset」：已经看到的那一屏开头不动，
 * 字号变大就少显示几个字（吐到下一页），变小就多显示几个字。
 *
 * ## 为什么不跨章拼页
 *
 * 每章正文前都插了一张「标题页」（[ReaderPage.isTitlePage]），标题页物理上把两章正文隔开，
 * 一页正文不可能跨过标题页接到下一章，所以「跨章页」在这个模型里不存在。旧方案里
 * 「字号缩小跨章补字」的需求由标题页自然消解，换来的是游标永远只落在一章内 ——
 * 上一页 / 下一页 / 换字号全部是单章内的一维运算，不会有跨章边界缺陷。
 *
 * ## 队列有界
 *
 * 只保留当前章 + 预取章（[MAX_CHAPTERS]），超出时丢最旧的章并同步修正游标；
 * 已读章节的 url / 标题由宿主 `ReadingSession` 记账（历史记录不丢）。
 *
 * @param measurer 文本高度测量策略（见 [PageMeasurer]）
 */
class KeyphoneReaderPager(private val measurer: PageMeasurer) {

    /**
     * 分页测量策略：返回「从 [start] 起，最多能放进 [spec] 视口里的字符结束位置（开区间）」。
     *
     * 约定：
     * - 返回值落在 `start + 1 .. text.length`，且必须可重复（同样的入参同样结果）；
     * - 实现**必须**把测量窗口收敛到一页量级（见 `StaticLayoutMeasurer` 的 `maxPageChars`）：
     *   本类会反复调用它，每次都对整章做排版会直接把主线程堵到 ANR
     *   （docs/reading-mode-anr-fix-20261022.md §二 第 2 层）。
     */
    fun interface PageMeasurer {
        fun pageEnd(text: CharSequence, start: Int, spec: PageSpec): Int
    }

    /** 一次分页测量所需的度量：视口宽高 + 正文字号（px） */
    data class PageSpec(val widthPx: Int, val heightPx: Int, val textSizePx: Int) {
        /** 尺寸还没量出来（视图未布局）时不做切页，先整章显示 */
        val isUsable: Boolean get() = widthPx > 0 && heightPx > 0
    }

    /**
     * 队列里的一章。
     *
     * @param url 章节真实 URL（去重用，宿主也据此记账）
     * @param title 章节标题（标题页显示、日志）
     * @param ordinal 全书第几章（1 起，宿主按已读轨迹累加；队列裁剪后仍然正确）
     * @param plain 提纯后的正文（`Html.fromHtml` 的结果，保留段落 / 加粗等基本样式）
     */
    data class ChapterText(
        val url: String,
        val title: String,
        val ordinal: Int,
        val plain: CharSequence
    )

    /**
     * 当前要显示的「页」。
     *
     * @param isTitlePage 是否章节标题页（此时 [text] 为空，由视图自行渲染标题）
     * @param startOffset 本页首字在该章 `plain` 里的偏移
     * @param endOffset 本页尾字偏移（开区间）
     */
    data class ReaderPage(
        val chapterIndex: Int,
        val chapter: ChapterText,
        val isTitlePage: Boolean,
        val text: CharSequence,
        val startOffset: Int,
        val endOffset: Int
    )

    private val chapters = ArrayList<ChapterText>()

    /** 游标：章下标 */
    private var index = 0

    /** 游标：章内字符偏移（正文页有效） */
    private var offset = 0

    /** 游标：当前是否停在标题页 */
    private var onTitlePage = true

    /**
     * 初始度量：宽高未知（视图还没布局）。字号用 [DEFAULT_TEXT_SIZE_PX]——
     * 刻意**不**引用 [KeyphoneReaderThemes]，让本类保持零 Android 依赖（可直接跑纯 JVM 单测）；
     * 真实字号由 `KeyphoneReaderView` 布局后通过 [setSpec] 注入。
     */
    private var spec = PageSpec(0, 0, DEFAULT_TEXT_SIZE_PX)

    /** 结构 / 度量变化计数：任何一项变了，页边界缓存即作废 */
    private var version = 0L

    val chapterCount: Int get() = chapters.size

    val currentChapterIndex: Int get() = index

    val currentChapter: ChapterText? get() = chapters.getOrNull(index)

    /** 当前是否停在标题页 */
    val isTitlePage: Boolean get() = onTitlePage

    /** 队列里是否有任何内容 */
    val isEmpty: Boolean get() = chapters.isEmpty()

    /** 当前度量（字号 / 视口） */
    val currentSpec: PageSpec get() = spec

    /** 正文当前字号（px） */
    val textSizePx: Int get() = spec.textSizePx

    /**
     * 队列是不是快用完了：当前章位于队列倒数 [keepAhead] 章之内。
     * 宿主据此（向后读）催取下一章 —— 事件驱动，没有任何定时器。
     */
    fun needsMoreChapters(keepAhead: Int = 1): Boolean =
        chapters.isNotEmpty() && index >= chapters.size - keepAhead

    /** 队列里各章的 URL（宿主交给取章脚本做「已访问」去重，防止站点 next 成环） */
    fun chapterUrls(): List<String> = chapters.map { it.url }

    /** 从零开始重来（进入阅读 / 换标签） */
    fun reset(chapter: ChapterText) {
        chapters.clear()
        chapters.add(chapter)
        index = 0
        offset = 0
        onTitlePage = true
        invalidate()
    }

    /** 清空（退出阅读） */
    fun clear() {
        chapters.clear()
        index = 0
        offset = 0
        onTitlePage = true
        invalidate()
    }

    /**
     * 追加一章到队列尾部。URL 重复（站点 next 指回已读章）时返回 false 且不做任何改动。
     */
    fun append(chapter: ChapterText): Boolean {
        if (chapters.any { sameUrl(it.url, chapter.url) }) {
            return false
        }
        chapters.add(chapter)
        // 有界：超过上限丢最旧的章，游标同步前移（继续读不受影响，只是不能回翻那么远）
        var dropped = false
        while (chapters.size > MAX_CHAPTERS) {
            chapters.removeAt(0)
            index = (index - 1).coerceAtLeast(0)
            dropped = true
        }
        // 只有「丢章」才让边界表作废（章下标整体前移）；单纯追加一节不改已有章节的排版，
        // 缓存必须留着 —— 否则每次预取成功都会把回翻路径打回「从头补量」
        if (dropped) invalidate()
        return true
    }

    /** 更新视口 / 字号；变化时页边界缓存作废，当前 startOffset 保持不变 */
    fun setSpec(widthPx: Int, heightPx: Int, textSizePx: Int) {
        val next = PageSpec(widthPx, heightPx, textSizePx)
        if (next == spec) return
        spec = next
        invalidate()
    }

    /** 当前页（含标题页）；队列为空时返回 null */
    fun currentPage(): ReaderPage? {
        val chapter = chapters.getOrNull(index) ?: return null
        if (onTitlePage) {
            return ReaderPage(index, chapter, true, "", 0, 0)
        }
        val length = chapter.plain.length
        if (length <= 0) {
            return ReaderPage(index, chapter, false, "", 0, 0)
        }
        val start = offset.coerceIn(0, length - 1)
        val end = pageEnd(chapter, start)
        return ReaderPage(index, chapter, false, chapter.plain.subSequence(start, end), start, end)
    }

    /**
     * 下一页。队列末尾（后面还没有章）时返回 false，宿主据此催取下一章。
     */
    fun next(): Boolean {
        if (chapters.isEmpty()) return false
        if (onTitlePage) {
            val chapter = chapters[index]
            if (chapter.plain.isNotEmpty()) {
                onTitlePage = false
                offset = 0
                return true
            }
            // 空正文的章（提取到了标题但没字）不留在队列里挡路，直接跳过
            return advanceChapter()
        }
        val chapter = chapters.getOrNull(index) ?: return false
        val end = pageEnd(chapter, offset.coerceIn(0, (chapter.plain.length - 1).coerceAtLeast(0)))
        if (end >= chapter.plain.length) {
            return advanceChapter()
        }
        // 段尾的 `\n` 只是分隔符：绝不能当作新页的第一行渲染（那会凭空多一条空行，
        // 一屏只有六七行时非常刺眼）。页首跳过它，等于让两条正文页「无缝」相接。
        val nextStart = skipLeadingNewlines(chapter, end)
        // 向前翻出来的页尾就是下一页的页首：直接记进边界表，回翻时零测量（见 [previousBoundary]）
        recordBoundary(index, offset, nextStart)
        offset = nextStart
        return true
    }

    /** 跳过 [start] 起的连续换行（段落分隔符），返回真正的正文起点 */
    private fun skipLeadingNewlines(chapter: ChapterText, start: Int): Int {
        var position = start
        val length = chapter.plain.length
        while (position < length && chapter.plain[position] == '\n') {
            position++
        }
        return position
    }

    /**
     * 上一页。
     *
     * 页首来自**边界表**而不是「把整章从头量一遍」：正常向前阅读时每个翻过的页尾都记在表里，
     * 回翻只是查表（0 次测量）；只有「游标没有落在已知边界上」（换字号后的第一次回翻、
     * 跨章回翻）才补量，而且**只补到当前页为止**，绝不扫整章
     * （旧实现在这里扫整章 → 万字章 400 次 StaticLayout → 主线程 5.6s → ANR，
     * 见 docs/reading-mode-anr-fix-20261022.md）。
     *
     * 队列里没有更早的章时停在最早一章的标题页并返回 false。
     */
    fun prev(): Boolean {
        if (chapters.isEmpty()) return false
        if (onTitlePage) {
            if (index == 0) return false
            var previous = index - 1
            while (previous > 0 && chapters[previous].plain.isEmpty()) {
                previous -= 1
            }
            val chapter = chapters[previous]
            if (chapter.plain.isEmpty()) {
                // 前面全是空章：没有可显示的正文，停在它的标题页
                index = previous
                offset = 0
                return true
            }
            index = previous
            onTitlePage = false
            offset = lastPageStart(previous)
            return true
        }
        if (offset <= 0) {
            onTitlePage = true
            offset = 0
            return true
        }
        offset = previousBoundary(index, offset)
        return true
    }

    /** 当前页是不是本章最后一页（宿主可用于「剩余页数」判断 / 日志） */
    fun isLastPageOfChapter(): Boolean {
        val chapter = chapters.getOrNull(index) ?: return true
        if (chapter.plain.isEmpty()) return true
        val end = pageEnd(chapter, offset.coerceIn(0, chapter.plain.length - 1))
        return end >= chapter.plain.length
    }

    // region 内部：分页测量与页边界缓存

    /**
     * 前进到下一章（停在它的标题页）。
     *
     * 正文为空（提取失败但标题还在）的章直接跳过，否则用户会卡在一个翻不动、
     * 又没有任何字的标题页上。队列末尾返回 false —— 那是「等宿主取新章」的正常状态。
     */
    private fun advanceChapter(): Boolean {
        var next = index + 1
        while (next < chapters.size && chapters[next].plain.isEmpty()) {
            next += 1
        }
        if (next >= chapters.size) return false
        index = next
        offset = 0
        onTitlePage = true
        return true
    }

    private fun pageEnd(chapter: ChapterText, start: Int): Int {
        val length = chapter.plain.length
        if (start >= length) return length
        if (!spec.isUsable) return length
        val measured = measurer.pageEnd(chapter.plain, start, spec)
        return measured.coerceIn(start + 1, length)
    }

    /**
     * 已量出来的页边界（升序，首项恒为 0），按「章下标 → 边界表」缓存。
     *
     * 为什么是「已量出来的」而不是「整章一次算全」：整章遍历是 ANR 的元凶
     * （见 docs/reading-mode-anr-fix-20261022.md §二）。这里的表**只增不改**，
     * 每翻一页最多补一次测量，回翻是查表，跨字号变化才从头补到当前页。
     * [version] 一变（字号 / 视口 / 章节结构）整表作废。
     */
    private val boundaries = HashMap<Int, ArrayList<Int>>()

    private var boundariesVersion = -1L

    /** 版本变了就整体作废（表很小，直接扔） */
    private fun ensureBoundariesVersion() {
        if (boundariesVersion == version) return
        boundaries.clear()
        boundariesVersion = version
    }

    private fun boundariesOf(chapterIndex: Int): ArrayList<Int> =
        boundaries.getOrPut(chapterIndex) { arrayListOf(0) }

    /**
     * 把 [chapterIndex] 的边界表补到「覆盖 [upto]」为止，返回该表。
     *
     * 补的方式是**从表尾一页页往后量**（不是从头重算）：表里已有的边界永远可信，
     * 所以正常向前阅读时这里一次测量都不做；换字号后第一次回翻才会补，
     * 而且补到当前页就停 —— 复杂度 O(当前页之前的页数)，与章长无关。
     */
    private fun boundariesUpTo(chapterIndex: Int, upto: Int): ArrayList<Int> {
        ensureBoundariesVersion()
        val starts = boundariesOf(chapterIndex)
        val chapter = chapters.getOrNull(chapterIndex) ?: return starts
        val length = chapter.plain.length
        if (length <= 0 || !spec.isUsable) return starts
        while (true) {
            val last = starts[starts.size - 1]
            if (last >= upto) break
            val end = pageEnd(chapter, last)
            if (end <= last) break
            // 与 [next] 同口径：边界一律落在正文上，不停在段落分隔符（`\n`）上
            val next = skipLeadingNewlines(chapter, end)
            starts.add(next)
            if (next >= length) break
        }
        return starts
    }

    /**
     * 记录一个已经量出来的页尾（[next] 向前翻时用），保证表的「只增不改」性质：
     * 只有当表尾正好等于本页页首时才追加 —— 游标不在已知边界上（换字号后）就不写，
     * 否则表里会出现不连续的边界，回翻会算错页首。
     */
    private fun recordBoundary(chapterIndex: Int, pageStart: Int, pageEnd: Int) {
        ensureBoundariesVersion()
        val starts = boundariesOf(chapterIndex)
        if (starts[starts.size - 1] != pageStart) return
        if (pageEnd > pageStart) starts.add(pageEnd)
    }

    /**
     * 上一页的页首：表里小于 [before] 的最大边界（没有更早的页则回到 0）。
     *
     * 只补量到 [before]，不碰整章 —— 这是 ANR 修复的核心（旧实现是整章遍历）。
     */
    private fun previousBoundary(chapterIndex: Int, before: Int): Int {
        val starts = boundariesUpTo(chapterIndex, before)
        var previous = 0
        for (start in starts) {
            if (start >= before) break
            previous = start
        }
        return previous
    }

    /**
     * 本章最后一页的页首（跨章回翻落在「上一章最后页」时用）。
     *
     * 这是唯一需要知道**章末**的路径：上一章若是一路读过来的，边界表里已经有全部边界，
     * 直接查表；否则（换字号后再回翻）补量整章一次 —— 单次测量已被
     * `StaticLayoutMeasurer` 收敛到一页量级，且只在跨章回翻时发生。
     */
    private fun lastPageStart(chapterIndex: Int): Int {
        val chapter = chapters.getOrNull(chapterIndex) ?: return 0
        val length = chapter.plain.length
        if (length <= 0 || !spec.isUsable) return 0
        val starts = boundariesUpTo(chapterIndex, length)
        var last = 0
        for (start in starts) {
            if (start >= length) break
            last = start
        }
        return last
    }

    private fun invalidate() {
        version += 1
    }

    private fun sameUrl(a: String, b: String): Boolean =
        normalize(a).equals(normalize(b), ignoreCase = true)

    private fun normalize(url: String): String {
        if (url.isEmpty()) return url
        val hash = url.indexOf('#')
        return if (hash >= 0) url.substring(0, hash) else url
    }

    // endregion

    companion object {
        /**
         * 队列上限（当前章 + 预取章）。
         *
         * 旧方案的痛点是「DOM 无限增长 → 每次 layout 越来越贵」，这里的队列上限保证
         * 内存与分页开销恒定；用户不回翻很久以前的内容，超出范围回翻会停在最早一章。
         */
        const val MAX_CHAPTERS = 12

        /** 视图注入真实字号前的占位（16px，与 `KeyphoneReadingPrefs` 默认档一致） */
        private const val DEFAULT_TEXT_SIZE_PX = 16
    }
}
