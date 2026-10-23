package acr.browser.lightning.keyphone.reading

import acr.browser.lightning.keyphone.KeyphoneGuardStore

/**
 * 站点规则表（按域名查）。
 *
 * 新增站点 = 加一条规则，不改解析逻辑。规则里的选择器必须**对着真实 HTML 核实过**
 * （见 `docs/reading-mode-fetch-decouple-impl-20261022.md` §2.2）：填错的后果只是 A 提空、
 * 自动降级到 B，不会比现状更差。
 *
 * @param domainOf 域名归一化函数（默认复用 [KeyphoneGuardStore.baseDomain] 的 eTLD+1 口径；
 *   单测注入纯 JVM 实现，避免依赖 `android.net.Uri`）
 */
class ReadingSiteRules(
    private val domainOf: (String?) -> String? = { KeyphoneGuardStore.baseDomain(it) }
) {

    /** 按页面 URL 查规则；无匹配返回 null（调用方走方案 B 的通用路径） */
    fun forUrl(url: String?): ReadingSiteRule? {
        val domain = domainOf(url) ?: return null
        return builtIn.firstOrNull { it.host == domain }
    }

    /** 按已归一化的域名查规则 */
    fun forHost(host: String?): ReadingSiteRule? {
        val h = host?.trim()?.lowercase()?.trimEnd('.') ?: return null
        return builtIn.firstOrNull { it.host == h }
    }

    companion object {
        /**
         * 内置规则。
         *
         * `uukan.org`（2026-10-22 对真实 HTML 核实）：
         * ```html
         * <div role="main" id="content" class="readcontent"><p>…</p></div>
         * <a class="btn btn-default" href="/chapter/…/X.html" id="linkPrev">上一章</a>
         * <a class="btn btn-default" href="/chapter/…/Y.html" id="linkNext">下一章</a>
         * <h1 class="pt10">184.最年轻的长老，雷长老</h1>
         * ```
         * 正文与链接全在静态 HTML 里（SSR），所以这是方案 A 的首条规则。
         */
        val builtIn: List<ReadingSiteRule> = listOf(
            ReadingSiteRule(
                host = "uukan.org",
                contentSelector = "#content",
                nextLinkSelector = "#linkNext",
                prevLinkSelector = "#linkPrev",
                titleSelector = "h1",
                transport = ReadingSiteRule.Transport.SSR_OK
            )
        )
    }
}
