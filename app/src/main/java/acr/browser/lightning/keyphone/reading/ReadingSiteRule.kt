package acr.browser.lightning.keyphone.reading

/**
 * 单条站点取章规则。
 *
 * - 方案 A（原生 HTTP）用 [contentSelector] / [nextLinkSelector] 在静态 HTML 上直提；
 * - 方案 B（专属取章 WebView）在没有规则或规则声明 CSR 时兜底；
 * - 规则表是**唯一事实源**：不要在 JS 与 Kotlin 各写一套。
 */
data class ReadingSiteRule(
    /** 归一化后的域名（eTLD+1，小写），如 `uukan.org` */
    val host: String,
    /** 正文容器的 CSS 选择器（jsoup），如 `#content` */
    val contentSelector: String,
    /** 「下一章」链接选择器；为空表示只用通用启发式 */
    val nextLinkSelector: String? = null,
    /** 「上一章」链接选择器（仅用于把 prev 从通用启发式里排除） */
    val prevLinkSelector: String? = null,
    /** 章节标题选择器；为空时退回 `<title>` 的首段 */
    val titleSelector: String? = null,
    /** 正文最少字符数（低于它判 [ChapterFetchResult.Empty]） */
    val minContentChars: Int = DEFAULT_MIN_CONTENT_CHARS,
    /** 传输方式：静态 HTML 可直提 / 必须跑 JS / 运行时判定 */
    val transport: Transport = Transport.AUTO
) {
    enum class Transport {
        /** 静态 HTML 就能提出正文与下一章（方案 A 的主场） */
        SSR_OK,

        /** 正文或链接靠 JS 跑出来，直接用方案 B，别浪费一次 HTTP */
        JS_REQUIRED,

        /** 先试 A，提空再降级 B（默认） */
        AUTO
    }

    companion object {
        /** 正文少于这么多字就认为没提到（公告章 / 空壳页） */
        const val DEFAULT_MIN_CONTENT_CHARS = 200
    }
}
