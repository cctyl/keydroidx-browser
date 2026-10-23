package acr.browser.lightning.keyphone.reading

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * 站点规则表的查表与 uukan.org 规则内容。
 *
 * 域名归一化用纯 JVM 的假实现注入（生产走 [acr.browser.lightning.keyphone.KeyphoneGuardStore]，
 * 它依赖 `android.net.Uri`，会让这个测试需要 Robolectric）。
 */
class ReadingSiteRulesTest {

    /** 只看最后两段，等价于 eTLD+1 */
    private fun domainOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val host = url.substringAfter("://", url).substringBefore('/').substringBefore('?').lowercase()
        if (!host.contains('.')) return null
        return host.split('.').takeLast(2).joinToString(".")
    }

    private val rules = ReadingSiteRules(::domainOf)

    @Test
    fun `uukan 章节 URL 命中内置规则`() {
        val rule = rules.forUrl("https://www.uukan.org/chapter/abc/def.html")
        assertThat(rule).isNotNull
        assertThat(rule!!.host).isEqualTo("uukan.org")
        assertThat(rule.contentSelector).isEqualTo("#content")
        assertThat(rule.nextLinkSelector).isEqualTo("#linkNext")
        assertThat(rule.prevLinkSelector).isEqualTo("#linkPrev")
        assertThat(rule.titleSelector).isEqualTo("h1")
        assertThat(rule.transport).isEqualTo(ReadingSiteRule.Transport.SSR_OK)
    }

    @Test
    fun `带 www 与子域也能命中 eTLD+1`() {
        assertThat(rules.forUrl("https://m.uukan.org/x")).isNotNull
        assertThat(rules.forUrl("https://uukan.org")).isNotNull
    }

    @Test
    fun `未登记站点返回 null（调用方走方案 B）`() {
        assertThat(rules.forUrl("https://example.com/chapter/1.html")).isNull()
        assertThat(rules.forUrl("")).isNull()
        assertThat(rules.forUrl(null)).isNull()
    }

    @Test
    fun `forHost 按已归一化域名查`() {
        assertThat(rules.forHost("UUKAN.ORG")?.contentSelector).isEqualTo("#content")
        assertThat(rules.forHost("www.uukan.org")).isNull()
        assertThat(rules.forHost(null)).isNull()
    }
}
