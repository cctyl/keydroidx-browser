package acr.browser.lightning.keyphone.reading

import acr.browser.lightning.concurrency.CoroutineDispatchers
import acr.browser.lightning.di.NoCacheClient
import acr.browser.lightning.useragent.UserAgentProvider
import io.github.cctyl.nokia.common.log.KeydroidxLog
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 方案 A：原生 HTTP 取章（OkHttp + jsoup + CookieManager）。
 *
 * 全程**不碰任何 WebView**，所以浏览标签的渲染进程死不死都与阅读模式无关 ——
 * 这正是 2026-10-22「读到一半说没有下一章」的根治手段。
 *
 * 取章本质只有四步：带 Cookie + UA GET → jsoup 定位正文容器 → 取下一章链接 → 返回 HTML 片段。
 * 提纯不在这里做（交 `htmlToPlainStatic`），保证与显示层口径一致。
 */
@Singleton
class ReadingChapterFetcher @Inject constructor(
    // Deferred<out T> 在 Java 签名里是通配符，Dagger 的 key 会对不上 —— 必须压掉（同 DefaultFileDownloader）
    @NoCacheClient private val client: Deferred<@JvmSuppressWildcards OkHttpClient>,
    private val userAgentProvider: UserAgentProvider,
    private val cookieProvider: ReadingCookieProvider,
    private val rules: ReadingSiteRules,
    private val dispatchers: CoroutineDispatchers
) {

    /**
     * 取一章。
     *
     * @param url 章节 URL（方案 A 只处理规则表里登记过的站；无规则应由 router 走 B）
     * @param recorded 已读 URL 集合（判「next 成环」用，与旧 JS 同口径）
     */
    suspend fun fetch(url: String, recorded: Set<String>): ChapterFetchResult {
        val rule = rules.forUrl(url)
            ?: return ChapterFetchResult.Error("no-rule: $url")
        return try {
            withContext(dispatchers.io) { fetchBlocking(url, rule, recorded) }
        } catch (t: Throwable) {
            KeydroidxLog.w(TAG, "取章失败 url=$url", t)
            ChapterFetchResult.Error(t.message ?: t.javaClass.simpleName)
        }
    }

    private suspend fun fetchBlocking(
        url: String,
        rule: ReadingSiteRule,
        recorded: Set<String>
    ): ChapterFetchResult {
        val userAgent = userAgentProvider.getUserAgent()
        val cookie = cookieProvider.cookieFor(url)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
            .build()

        val client = client.await()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return ChapterFetchResult.Error("HTTP ${response.code}")
            }
            val finalUrl = response.request.url.toString()
            if (finalUrl != url) {
                // 站点重定向（VIP 章 → 免费章 / 登录页）会让「下一章」变成自己，必须留痕
                KeydroidxLog.w(TAG, "站点重定向: $url -> $finalUrl")
            }
            // charset 交给 jsoup 自己按 BOM / <meta charset> 判：站点是 gbk 时也不会乱码
            val doc = response.body.byteStream().use { stream: InputStream ->
                Jsoup.parse(stream, null, finalUrl)
            }
            val content = doc.selectFirst(rule.contentSelector)
            if (content == null) {
                KeydroidxLog.d(TAG, "提空：正文选择器未命中 ${rule.contentSelector} url=$url")
                return ChapterFetchResult.Empty
            }
            val text = content.text()
            if (text.length < rule.minContentChars) {
                KeydroidxLog.d(
                    TAG,
                    "提空：正文只有 ${text.length} 字（阈值 ${rule.minContentChars}）url=$url"
                )
                return ChapterFetchResult.Empty
            }
            val next = pickNextUrl(doc, rule, finalUrl, recorded)
            KeydroidxLog.d(
                TAG,
                "取章成功 url=$url 字数=${text.length} next=${next ?: "-"} " +
                    "标题=${titleOf(doc, rule)} 通道=A"
            )
            if (next == null) return ChapterFetchResult.End
            return ChapterFetchResult.Ok(
                url = finalUrl,
                title = titleOf(doc, rule),
                html = content.html(),
                next = next
            )
        }
    }

    /**
     * 找下一章链接。
     *
     * 顺序：规则里显式选择器 → 通用启发式（遍历 `a[href]` 按文案判）。
     * 显式选择器命中但解析为空时也走启发式 —— 多一次机会，代价是零点几毫秒。
     *
     * 返回 null 表示「这一页确实没有可去的下一章」：
     * - 压根没找到链接（多半是最后一章）；
     * - 找到的链接指向自己、或全部是已读章节（成环）。
     */
    private fun pickNextUrl(
        doc: Document,
        rule: ReadingSiteRule,
        selfUrl: String,
        recorded: Set<String>
    ): String? {
        val prevUrl = rule.prevLinkSelector?.let { sel -> doc.selectFirst(sel)?.absUrl("href") }
        val candidates = ArrayList<String>(4)

        rule.nextLinkSelector?.let { sel ->
            doc.selectFirst(sel)?.absUrl("href")?.takeIf { it.isNotBlank() }?.let(candidates::add)
        }
        candidates.addAll(genericNextCandidates(doc, prevUrl))

        val seen = HashSet<String>(recorded.size + candidates.size)
        seen.addAll(recorded)
        seen.add(selfUrl)
        seen.add(stripFragment(selfUrl))

        val usable = candidates
            .map { stripFragment(it) }
            .filter { it.isNotBlank() && !it.startsWith("javascript:", ignoreCase = true) }
            .distinct()
        val picked = usable.firstOrNull { it !in seen }
        if (picked == null && usable.isNotEmpty()) {
            KeydroidxLog.d(TAG, "下一章候选全部成环/指向自己：${usable.take(3)}")
        }
        return picked
    }

    /** 通用启发式：文案命中「下一章 / 下一页 / next」且不是「上一章 / prev」 */
    private fun genericNextCandidates(doc: Document, prevUrl: String?): List<String> {
        val result = ArrayList<String>()
        for (anchor in doc.select("a[href]")) {
            val label = anchor.text().trim()
            if (label.isEmpty() || label.length > MAX_LINK_LABEL_LEN) continue
            val href = anchor.absUrl("href")
            if (href.isBlank() || href == prevUrl) continue
            if (looksLikePrev(label)) continue
            if (!looksLikeNext(label)) continue
            result.add(href)
        }
        return result
    }

    private fun looksLikeNext(label: String): Boolean {
        val lower = label.lowercase()
        return NEXT_LABELS.any { lower.contains(it) }
    }

    private fun looksLikePrev(label: String): Boolean {
        val lower = label.lowercase()
        return PREV_LABELS.any { lower.contains(it) }
    }

    /** 标题：规则选择器优先，退回 `<title>` 的首段（站点标题通常是「章节-书名-作者」） */
    private fun titleOf(doc: Document, rule: ReadingSiteRule): String {
        rule.titleSelector?.let { sel ->
            val el: Element? = doc.selectFirst(sel)
            val text = el?.text()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return doc.title()
            .substringBefore('-')
            .substringBefore('_')
            .substringBefore('|')
            .trim()
    }

    private fun stripFragment(url: String): String = url.substringBefore('#')

    private companion object {
        const val TAG = "KeyphoneReadingFetch"

        /** 「下一章」类链接的文案上限：导航按钮都很短，长文本是正文里的巧合 */
        const val MAX_LINK_LABEL_LEN = 12

        val NEXT_LABELS = listOf("下一章", "下一頁", "下一页", "下页", "下一节", "next")
        val PREV_LABELS = listOf("上一章", "上一頁", "上一页", "上页", "prev", "previous")
    }
}
