package acr.browser.lightning.keyphone

import acr.browser.lightning.constant.HTTPS
import acr.browser.lightning.constant.SCHEME_HOMEPAGE
import android.net.Uri

/**
 * 按键机地址栏 / 搜索框的输入归一化（首页与浏览页共用，避免两处逻辑漂移）。
 *
 * ⚠ 已知待办（`HANDOVER.md` §7.3）：搜索 URL 目前硬编码 Google，
 * 后续应改走上游 `SearchEngineProvider`。
 */
internal object KeyphoneUrls {

    /** 应用内部生成的页面（`about:` 系）不可回填到地址编辑页。 */
    fun isInternalPage(url: String?): Boolean =
        url.isNullOrBlank() || url.startsWith("about:")

    /**
     * 把用户输入归一化成可加载的 URL：
     * - 含 `://` → 原样；
     * - 「无空格且含点」→ 当域名，补 `https://`；
     * - 其余（含空串）→ 走搜索。
     */
    fun normalize(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) {
            return SCHEME_HOMEPAGE
        }
        if (text.contains("://")) {
            return text
        }
        val looksLikeUrl = !text.contains(' ') && text.contains('.')
        return if (looksLikeUrl) HTTPS + text else SEARCH_URL + Uri.encode(text)
    }

    private const val SEARCH_URL = "https://www.google.com/search?q="
}
