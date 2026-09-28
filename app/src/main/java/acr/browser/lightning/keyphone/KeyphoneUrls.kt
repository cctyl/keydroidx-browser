package acr.browser.lightning.keyphone

import acr.browser.lightning.constant.SCHEME_HOMEPAGE
import acr.browser.lightning.search.SearchEngineProvider
import acr.browser.lightning.search.engine.search

/**
 * 按键机地址栏 / 搜索框的输入归一化。
 */
internal object KeyphoneUrls {

    /** 应用内部生成的页面（`about:` 系）不可回填到地址编辑页。 */
    fun isInternalPage(url: String?): Boolean =
        url.isNullOrBlank() || url.startsWith("about:")

    /**
     * 把用户输入归一化成可加载的 URL：
     * - 空串 → 回主页；
     * - 其余 → 交由当前偏好的搜索引擎及 smartUrlFilter 进行智能识别（支持直开网址与关键词搜索）。
     */
    suspend fun normalize(raw: String, searchEngineProvider: SearchEngineProvider): String {
        val text = raw.trim()
        if (text.isEmpty()) {
            return SCHEME_HOMEPAGE
        }
        return searchEngineProvider.provideSearchEngine().search(text)
    }
}
