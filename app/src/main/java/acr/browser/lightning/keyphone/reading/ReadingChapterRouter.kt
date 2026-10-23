package acr.browser.lightning.keyphone.reading

/**
 * A/B 通道选择与判定回填（纯逻辑，可单测）。
 *
 * 设计见 `docs/reading-mode-fetch-decouple-plan-20261022.md` §6.1：
 * 无规则 / 显式 CSR / 缓存判 JS → 走 B；其余先走 A，A 提空才降级并回填 JS。
 */
object ReadingChapterRouter {

    enum class Channel { A_NATIVE, B_WEBVIEW }

    /** 本次取章该走哪条通道 */
    fun channel(rule: ReadingSiteRule?, verdict: ReadingDomainVerdict.Verdict?): Channel = when {
        // 没有选择器可用，A 提不出东西 → 直接 B（省一次 HTTP）
        rule == null -> Channel.B_WEBVIEW
        rule.transport == ReadingSiteRule.Transport.JS_REQUIRED -> Channel.B_WEBVIEW
        verdict == ReadingDomainVerdict.Verdict.JS -> Channel.B_WEBVIEW
        else -> Channel.A_NATIVE
    }

    /**
     * A 通道返回后要回填的判定。
     *
     * - [ChapterFetchResult.Ok] → SSR（确定能提）
     * - [ChapterFetchResult.Empty] → JS（静态 HTML 提不出，以后别白试）
     * - 其余（Error / End / NotReady）→ null，**不缓存**（偶发错误不能改变站点画像）
     */
    fun verdictAfterA(result: ChapterFetchResult): ReadingDomainVerdict.Verdict? = when (result) {
        is ChapterFetchResult.Ok -> ReadingDomainVerdict.Verdict.SSR
        is ChapterFetchResult.Empty -> ReadingDomainVerdict.Verdict.JS
        else -> null
    }
}
