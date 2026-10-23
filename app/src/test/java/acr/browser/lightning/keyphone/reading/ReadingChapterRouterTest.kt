package acr.browser.lightning.keyphone.reading

import acr.browser.lightning.keyphone.reading.ReadingChapterRouter.Channel
import acr.browser.lightning.keyphone.reading.ReadingDomainVerdict.Verdict
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/** A/B 通道选择与判定回填（设计文档 §6.1） */
class ReadingChapterRouterTest {

    private fun rule(transport: ReadingSiteRule.Transport) = ReadingSiteRule(
        host = "uukan.org",
        contentSelector = "#content",
        transport = transport
    )

    @Test
    fun `无规则走 B`() {
        assertThat(ReadingChapterRouter.channel(null, null)).isEqualTo(Channel.B_WEBVIEW)
    }

    @Test
    fun `规则声明 JS_REQUIRED 走 B`() {
        assertThat(
            ReadingChapterRouter.channel(rule(ReadingSiteRule.Transport.JS_REQUIRED), null)
        ).isEqualTo(Channel.B_WEBVIEW)
    }

    @Test
    fun `缓存判定该域是 JS 走 B`() {
        assertThat(
            ReadingChapterRouter.channel(rule(ReadingSiteRule.Transport.SSR_OK), Verdict.JS)
        ).isEqualTo(Channel.B_WEBVIEW)
    }

    @Test
    fun `SSR_OK 与 AUTO 走 A`() {
        assertThat(
            ReadingChapterRouter.channel(rule(ReadingSiteRule.Transport.SSR_OK), null)
        ).isEqualTo(Channel.A_NATIVE)
        assertThat(
            ReadingChapterRouter.channel(rule(ReadingSiteRule.Transport.AUTO), Verdict.SSR)
        ).isEqualTo(Channel.A_NATIVE)
    }

    @Test
    fun `A 成功回填 SSR，提空回填 JS，出错不回填`() {
        val ok = ChapterFetchResult.Ok("u", "t", "<p>x</p>", "n")
        assertThat(ReadingChapterRouter.verdictAfterA(ok)).isEqualTo(Verdict.SSR)
        assertThat(ReadingChapterRouter.verdictAfterA(ChapterFetchResult.Empty)).isEqualTo(Verdict.JS)
        assertThat(ReadingChapterRouter.verdictAfterA(ChapterFetchResult.Error("HTTP 500"))).isNull()
        assertThat(ReadingChapterRouter.verdictAfterA(ChapterFetchResult.End)).isNull()
        assertThat(ReadingChapterRouter.verdictAfterA(ChapterFetchResult.NotReady)).isNull()
    }
}
