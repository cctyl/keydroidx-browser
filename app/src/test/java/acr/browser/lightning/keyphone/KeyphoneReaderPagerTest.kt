package acr.browser.lightning.keyphone

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * [KeyphoneReaderPager] 的分页 / 翻页 / 字号变化规则测试。
 *
 * 测量器用「每页固定字数」的假实现（[FakeMeasurer]），于是这些规则可以在纯 JVM 上
 * 精确断言，不依赖 StaticLayout 的真实排版结果。真实排版口径见 `KeyphoneReaderView`。
 */
class KeyphoneReaderPagerTest {

    /** 每页固定 [charsPerPage] 字，便于精确断言游标 */
    private class FakeMeasurer(private val charsPerPage: Int) : KeyphoneReaderPager.PageMeasurer {
        override fun pageEnd(
            text: CharSequence,
            start: Int,
            spec: KeyphoneReaderPager.PageSpec
        ): Int = (start + charsPerPage).coerceAtMost(text.length)
    }

    /**
     * 会数调用次数的假测量器：用来给「单次翻页的测量量」上界
     * （ANR 的根因就是回翻触发整章遍历，见 docs/reading-mode-anr-fix-20261022.md）。
     */
    private class CountingMeasurer(private val charsPerPage: Int) : KeyphoneReaderPager.PageMeasurer {
        var calls = 0

        override fun pageEnd(
            text: CharSequence,
            start: Int,
            spec: KeyphoneReaderPager.PageSpec
        ): Int {
            calls++
            return (start + charsPerPage).coerceAtMost(text.length)
        }
    }

    private fun chapter(
        index: Int,
        length: Int = 10,
        url: String = "https://example.com/ch$index",
        title: String = "第${index}章 标题"
    ): KeyphoneReaderPager.ChapterText = KeyphoneReaderPager.ChapterText(
        url = url,
        title = title,
        ordinal = index,
        plain = "x".repeat(length)
    )

    private fun pager(charsPerPage: Int = 5): KeyphoneReaderPager =
        KeyphoneReaderPager(FakeMeasurer(charsPerPage)).apply {
            setSpec(widthPx = 100, heightPx = 100, textSizePx = 16)
        }

    private fun pager(measurer: KeyphoneReaderPager.PageMeasurer): KeyphoneReaderPager =
        KeyphoneReaderPager(measurer).apply {
            setSpec(widthPx = 100, heightPx = 100, textSizePx = 16)
        }

    private fun pageRange(pager: KeyphoneReaderPager): Pair<Int, Int> {
        val page = pager.currentPage()!!
        return page.startOffset to page.endOffset
    }

    @Test
    fun `reset shows the chapter title page first`() {
        val pager = pager()
        pager.reset(chapter(1))

        val page = pager.currentPage()

        assertThat(page).isNotNull
        assertThat(page!!.isTitlePage).isTrue()
        assertThat(page.chapter.ordinal).isEqualTo(1)
    }

    @Test
    fun `next page moves the cursor one screen at a time`() {
        val pager = pager(charsPerPage = 4)
        pager.reset(chapter(1, length = 10))

        assertThat(pager.next()).isTrue()
        assertThat(pageRange(pager)).isEqualTo(0 to 4)

        assertThat(pager.next()).isTrue()
        assertThat(pageRange(pager)).isEqualTo(4 to 8)

        // 最后一页：不足一屏，按章末收尾
        assertThat(pager.next()).isTrue()
        assertThat(pageRange(pager)).isEqualTo(8 to 10)
    }

    @Test
    fun `next page at the end of the queue reports failure so the host can fetch`() {
        val pager = pager(charsPerPage = 100)
        pager.reset(chapter(1, length = 10))

        assertThat(pager.next()).isTrue()   // 标题页 -> 正文（整章一页）
        assertThat(pager.next()).isFalse()  // 队列里没有下一章
    }

    @Test
    fun `appended chapter continues behind its own title page`() {
        val pager = pager(charsPerPage = 100)
        pager.reset(chapter(1, length = 10))
        assertThat(pager.append(chapter(2, length = 10))).isTrue()

        assertThat(pager.next()).isTrue()   // 第 1 章正文
        assertThat(pager.next()).isTrue()   // 第 2 章标题页
        assertThat(pager.currentPage()!!.isTitlePage).isTrue()
        assertThat(pager.currentPage()!!.chapter.ordinal).isEqualTo(2)

        assertThat(pager.next()).isTrue()   // 第 2 章正文
        assertThat(pager.currentPage()!!.chapter.ordinal).isEqualTo(2)
        assertThat(pager.currentPage()!!.isTitlePage).isFalse()
    }

    @Test
    fun `duplicate chapter url is rejected`() {
        val pager = pager()
        pager.reset(chapter(1))
        assertThat(pager.append(chapter(2))).isTrue()

        assertThat(pager.append(chapter(2))).isFalse()
        assertThat(pager.chapterCount).isEqualTo(2)
    }

    @Test
    fun `duplicate chapter url is compared ignoring the fragment`() {
        val pager = pager()
        pager.reset(chapter(1, url = "https://example.com/ch1"))

        assertThat(pager.append(chapter(1, url = "https://example.com/ch1#top"))).isFalse()
    }

    @Test
    fun `prev page returns to the previous screen start`() {
        val pager = pager(charsPerPage = 4)
        pager.reset(chapter(1, length = 10))
        pager.next()   // 标题页 -> 0..4
        pager.next()   // 4..8
        pager.next()   // 8..10

        assertThat(pager.prev()).isTrue()
        assertThat(pageRange(pager)).isEqualTo(4 to 8)

        assertThat(pager.prev()).isTrue()
        assertThat(pager.currentPage()!!.startOffset).isEqualTo(0)

        // 正文第一页再往前 = 本章标题页
        assertThat(pager.prev()).isTrue()
        assertThat(pager.currentPage()!!.isTitlePage).isTrue()

        // 队列最前面：不能再往前
        assertThat(pager.prev()).isFalse()
    }

    @Test
    fun `prev page from a chapter title page lands on the previous chapter last page`() {
        val pager = pager(charsPerPage = 4)
        pager.reset(chapter(1, length = 10))
        pager.append(chapter(2, length = 10))
        pager.next()   // 第 1 章正文 0..4
        pager.next()   // 4..8
        pager.next()   // 8..10
        pager.next()   // 第 2 章标题页

        assertThat(pager.currentPage()!!.isTitlePage).isTrue()
        assertThat(pager.prev()).isTrue()

        val page = pager.currentPage()!!
        assertThat(page.chapter.ordinal).isEqualTo(1)
        assertThat(page.isTitlePage).isFalse()
        assertThat(page.startOffset).isEqualTo(8)
    }

    @Test
    fun `spec change never moves the page start offset`() {
        val pager = pager(charsPerPage = 4)
        pager.reset(chapter(1, length = 20))
        pager.next()   // 正文 0..4
        pager.next()   // 4..8

        val beforeStart = pager.currentPage()!!.startOffset
        pager.setSpec(widthPx = 100, heightPx = 100, textSizePx = 40)

        assertThat(pager.currentPage()!!.startOffset).isEqualTo(beforeStart)
        assertThat(pager.currentSpec.textSizePx).isEqualTo(40)
    }

    @Test
    fun `needsMoreChapters becomes true at the tail of the queue`() {
        val pager = pager(charsPerPage = 100)
        pager.reset(chapter(1))
        assertThat(pager.needsMoreChapters()).isTrue()   // 只有一章：本来就该预取

        pager.append(chapter(2))
        assertThat(pager.needsMoreChapters()).isFalse()  // 后面还有一章：不用催

        pager.append(chapter(3))
        pager.next()   // 第 1 章正文
        pager.next()   // 第 2 章标题页
        assertThat(pager.needsMoreChapters()).isFalse()

        pager.next()   // 第 2 章正文
        pager.next()   // 第 3 章标题页（队列最后一章）
        assertThat(pager.needsMoreChapters()).isTrue()
    }

    @Test
    fun `queue is bounded and the cursor follows the drop`() {
        val pager = pager(charsPerPage = 100)
        pager.reset(chapter(1))
        for (i in 2..5) pager.append(chapter(i))
        // 每章「标题页 + 一页正文」= 2 次翻页；翻到第 5 章标题页
        repeat(8) { pager.next() }
        assertThat(pager.currentChapter!!.ordinal).isEqualTo(5)
        assertThat(pager.chapterCount).isEqualTo(5)

        for (i in 6..16) pager.append(chapter(i))

        assertThat(pager.chapterCount).isEqualTo(KeyphoneReaderPager.MAX_CHAPTERS)
        // 当前章仍在队列里（游标已随丢弃前移，用户不会因为丢弃而跳到别章）
        assertThat(pager.currentChapter!!.ordinal).isEqualTo(5)
    }

    @Test
    fun `viewport not measured yet keeps the whole chapter on one page`() {
        val pager = KeyphoneReaderPager(FakeMeasurer(2))   // 不 setSpec：宽高都是 0
        pager.reset(chapter(1, length = 30))

        assertThat(pager.next()).isTrue()
        val page = pager.currentPage()!!
        assertThat(page.startOffset).isEqualTo(0)
        assertThat(page.endOffset).isEqualTo(30)
    }

    @Test
    fun `empty chapter body is skipped instead of trapping the reader`() {
        val pager = pager(charsPerPage = 4)
        pager.reset(chapter(1, length = 5))
        pager.append(chapter(2, length = 0))
        pager.append(chapter(3, length = 5))

        pager.next()   // 第 1 章正文 0..4
        pager.next()   // 第 1 章正文 4..5
        pager.next()   // 第 2 章正文为空：整章跳过，直接落到第 3 章标题页

        val page = pager.currentPage()!!
        assertThat(page.chapter.ordinal).isEqualTo(3)
        assertThat(page.isTitlePage).isTrue()
    }

    /**
     * ANR 回归 1：万字章里回翻一次**不许**触发整章遍历。
     *
     * 旧实现 `prev()` → `previousPageStart()` → `pageStarts()` 会把整章从头量一遍
     * （1000 页的章 = 1000 次 pageEnd），本用例用「测量调用次数」把这个退化钉死。
     */
    @Test
    fun `prev page never scans the whole chapter`() {
        val measurer = CountingMeasurer(charsPerPage = 10)
        val pager = pager(measurer)
        // 10000 字 / 每页 10 字 = 1000 页：整章遍历必然是四位数调用
        pager.reset(chapter(1, length = 10_000))
        repeat(3) { pager.next() }   // 标题页 -> 0..10 -> 10..20（偏移 20）
        assertThat(pager.currentPage()!!.startOffset).isEqualTo(20)

        measurer.calls = 0
        assertThat(pager.prev()).isTrue()

        // 页边界是向前阅读时记下来的，回翻纯查表：一次测量都不做
        assertThat(measurer.calls).isEqualTo(0)
        assertThat(pager.currentPage()!!.startOffset).isEqualTo(10)

        measurer.calls = 0
        assertThat(pager.prev()).isTrue()
        assertThat(measurer.calls).isEqualTo(0)
        assertThat(pager.currentPage()!!.startOffset).isEqualTo(0)
    }

    /**
     * ANR 回归 2：换字号后边界表作废，回翻只从章首补量到**当前页**，
     * 而不是补到章末（更不是整章遍历后再反查）。
     */
    @Test
    fun `prev after a spec change only measures up to the current page`() {
        val measurer = CountingMeasurer(charsPerPage = 10)
        val pager = pager(measurer)
        pager.reset(chapter(1, length = 10_000))
        repeat(3) { pager.next() }   // 偏移 20
        pager.setSpec(widthPx = 100, heightPx = 100, textSizePx = 24)   // 换字号 → 边界表作废

        measurer.calls = 0
        assertThat(pager.prev()).isTrue()

        // 只补量到 20（2 页），不是 1000 页
        assertThat(measurer.calls).isLessThanOrEqualTo(3)
        // 回翻落在「包含当前偏移的那一页」的页首
        assertThat(pager.currentPage()!!.startOffset).isEqualTo(10)
    }

    /** 追加一章（预取成功）不该清掉已记录的边界，否则每次取章都把回翻打回补量 */
    @Test
    fun `appending a chapter keeps the recorded boundaries`() {
        val measurer = CountingMeasurer(charsPerPage = 10)
        val pager = pager(measurer)
        pager.reset(chapter(1, length = 100))
        pager.append(chapter(2, length = 100))
        repeat(3) { pager.next() }   // 偏移 20

        measurer.calls = 0
        pager.append(chapter(3, length = 100))
        assertThat(pager.prev()).isTrue()

        assertThat(measurer.calls).isEqualTo(0)
        assertThat(pager.currentPage()!!.startOffset).isEqualTo(10)
    }

    /**
     * ANR 回归 3：页边界连续 —— 任意章节上 `next` 走到底再 `prev` 走回来，
     * 每一页的区间必须与去程一一对应（不丢字、不重复、不跳页）。
     */
    @Test
    fun `prev and next round-trip never loses or duplicates text`() {
        // 23 与 7 互质：最后一页装不满，能覆盖「章末不足一屏」的边界
        val pager = pager(charsPerPage = 7)
        pager.reset(chapter(1, length = 23))

        val forward = ArrayList<Pair<Int, Int>>()
        while (true) {
            if (!pager.next()) break
            val page = pager.currentPage()!!
            if (!page.isTitlePage) forward.add(page.startOffset to page.endOffset)
        }

        // 去程：从 0 连续铺到章末，无空洞无重叠
        assertThat(forward.first().first).isEqualTo(0)
        assertThat(forward.last().second).isEqualTo(23)
        forward.zipWithNext().forEach { (a, b) -> assertThat(a.second).isEqualTo(b.first) }

        // 回程：逐页倒着回来，区间必须与去程完全一致
        // （去程最后一页就是当前页，所以比对时从倒数第二页开始）
        val backward = ArrayList<Pair<Int, Int>>()
        while (true) {
            if (!pager.prev()) break
            val page = pager.currentPage()!!
            if (!page.isTitlePage) backward.add(page.startOffset to page.endOffset)
        }

        assertThat(backward).isEqualTo(forward.reversed().drop(1))
    }

    /** 正文第一页再往前 = 本章标题页（不是章末、不是无效页） */
    @Test
    fun `prev from the first page goes back to the title page`() {
        val pager = pager(charsPerPage = 7)
        pager.reset(chapter(1, length = 23))
        pager.next()   // 标题页 -> 0..7

        assertThat(pager.prev()).isTrue()
        assertThat(pager.currentPage()!!.isTitlePage).isTrue()
        assertThat(pager.currentPage()!!.startOffset).isEqualTo(0)
    }

    /**
     * 段落分隔符不能变成新页的第一行：页首跳过 `\n`。
     *
     * 否则每页顶上会凭空多一条空行（旧实现就是这样，用户看到的就是「空白太大」）。
     */
    @Test
    fun `page never starts on a paragraph gap`() {
        // 第 10 个字符正好是段落分隔符：页尾落在 '\n' 上
        val body = "a".repeat(10) + "\n" + "b".repeat(20)
        val pager = pager(charsPerPage = 10)
        pager.reset(KeyphoneReaderPager.ChapterText("https://example.com/ch1", "第1章", 1, body))

        assertThat(pager.next()).isTrue()   // 标题页 -> 正文

        val page = pager.currentPage()!!
        assertThat(page.startOffset).isEqualTo(0)
        // 页尾正好落在 '\n' 上：下一页必须从 'b' 开始，而不是从空行开始
        assertThat(page.endOffset).isEqualTo(10)
        assertThat(page.text.last()).isEqualTo('a')

        assertThat(pager.next()).isTrue()
        val second = pager.currentPage()!!
        assertThat(second.startOffset).isEqualTo(11)
        assertThat(second.text.first()).isEqualTo('b')

        // 回翻仍然落在真正的页首上（边界表记的是跳过换行后的位置）
        assertThat(pager.prev()).isTrue()
        assertThat(pager.currentPage()!!.startOffset).isEqualTo(0)
    }
}
