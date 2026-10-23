package acr.browser.lightning.keyphone.reading

import acr.browser.lightning.keyphone.reading.ReadingDomainVerdict.Verdict
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/** 域名判定缓存：内存级、可清、未知返回 null */
class ReadingDomainVerdictTest {

    @Test
    fun `未判定过的域名返回 null`() {
        assertThat(ReadingDomainVerdict().get("uukan.org")).isNull()
        assertThat(ReadingDomainVerdict().get(null)).isNull()
    }

    @Test
    fun `回填后可读回，可单点清除`() {
        val verdict = ReadingDomainVerdict()
        verdict.set("uukan.org", Verdict.SSR)
        assertThat(verdict.get("uukan.org")).isEqualTo(Verdict.SSR)

        verdict.set("uukan.org", Verdict.JS)
        assertThat(verdict.get("uukan.org")).isEqualTo(Verdict.JS)

        verdict.clear("uukan.org")
        assertThat(verdict.get("uukan.org")).isNull()
    }

    @Test
    fun `clearAll 清空全部（站点改版后重新检测）`() {
        val verdict = ReadingDomainVerdict()
        verdict.set("a.com", Verdict.SSR)
        verdict.set("b.com", Verdict.JS)

        verdict.clearAll()

        assertThat(verdict.get("a.com")).isNull()
        assertThat(verdict.get("b.com")).isNull()
    }
}
