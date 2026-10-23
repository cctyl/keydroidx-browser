package acr.browser.lightning.keyphone.reading

import acr.browser.lightning.concurrency.CoroutineDispatchers
import acr.browser.lightning.useragent.UserAgentProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * 方案 A（原生 HTTP 取章）的行为测试。
 *
 * 用 **OkHttp 拦截器**当假站点：直接返回写死的 HTML，并顺手记下真实请求头，
 * 于是「Cookie / UA 注入」「提空判定」「HTTP 错误不降级」都能在纯 JVM 上断言
 * （不引入 MockWebServer 依赖，避免离线环境挂）。
 */
class ReadingChapterFetcherTest {

    private val dispatchers = object : CoroutineDispatchers {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val network: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override fun createDatabaseDispatcher(): CoroutineDispatcher = Dispatchers.Unconfined
    }

    private val rules = ReadingSiteRules { url ->
        if (url != null && url.contains("uukan.org")) "uukan.org" else null
    }

    private var lastRequest: Request? = null

    private fun fetcher(
        code: Int = 200,
        html: String = CHAPTER_HTML
    ): ReadingChapterFetcher {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            lastRequest = chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "ERR")
                .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }).build()
        return ReadingChapterFetcher(
            client = CompletableDeferred(client),
            userAgentProvider = object : UserAgentProvider {
                override suspend fun getUserAgent(): String = "TestUA/1.0"
            },
            cookieProvider = object : ReadingCookieProvider {
                override suspend fun cookieFor(url: String): String? = "sid=abc"
            },
            rules = rules,
            dispatchers = dispatchers
        )
    }

    @Test
    fun `正常章节返回 Ok 且带正确的 next 与标题`() = runTest {
        val result = fetcher().fetch(CHAPTER_URL, emptySet())

        assertThat(result).isInstanceOf(ChapterFetchResult.Ok::class.java)
        val ok = result as ChapterFetchResult.Ok
        assertThat(ok.title).isEqualTo("184.最年轻的长老，雷长老")
        assertThat(ok.next).isEqualTo("https://www.uukan.org/chapter/abc/next.html")
        assertThat(ok.html).contains("<p>")
        assertThat(ok.url).isEqualTo(CHAPTER_URL)
    }

    @Test
    fun `请求必须带 Cookie 与 UA`() = runTest {
        fetcher().fetch(CHAPTER_URL, emptySet())

        assertThat(lastRequest).isNotNull
        assertThat(lastRequest!!.header("Cookie")).isEqualTo("sid=abc")
        assertThat(lastRequest!!.header("User-Agent")).isEqualTo("TestUA/1.0")
    }

    @Test
    fun `正文太短判提空（多半是 CSR 空壳）`() = runTest {
        val result = fetcher(html = SHELL_HTML).fetch(CHAPTER_URL, emptySet())
        assertThat(result).isEqualTo(ChapterFetchResult.Empty)
    }

    @Test
    fun `HTTP 500 是 Error，不是提空（不能污染域名判定）`() = runTest {
        val result = fetcher(code = 500).fetch(CHAPTER_URL, emptySet())
        assertThat(result).isInstanceOf(ChapterFetchResult.Error::class.java)
        assertThat((result as ChapterFetchResult.Error).message).contains("500")
    }

    @Test
    fun `最后一章没有 next 链接判 End`() = runTest {
        val result = fetcher(html = LAST_CHAPTER_HTML).fetch(CHAPTER_URL, emptySet())
        assertThat(result).isEqualTo(ChapterFetchResult.End)
    }

    @Test
    fun `next 指向自己判 End（成环）`() = runTest {
        val selfLoop = LAST_CHAPTER_HTML.replace(
            "</div>",
            "</div><a id=\"linkNext\" href=\"$CHAPTER_URL\">下一章</a>"
        )
        val result = fetcher(html = selfLoop).fetch(CHAPTER_URL, emptySet())
        assertThat(result).isEqualTo(ChapterFetchResult.End)
    }

    @Test
    fun `没有规则的站点直接报错（由 router 走 B，不该进来）`() = runTest {
        val result = fetcher().fetch("https://example.com/chapter/1.html", emptySet())
        assertThat(result).isInstanceOf(ChapterFetchResult.Error::class.java)
    }

    private companion object {
        const val CHAPTER_URL = "https://www.uukan.org/chapter/abc/def.html"

        /** 正文 > 200 字，带 h1 与 #linkNext，与真实 uukan 结构一致 */
        val CHAPTER_HTML = """
            <html><head><title>184.最年轻的长老，雷长老-书名-作者</title></head><body>
            <h1 class="pt10">184.最年轻的长老，雷长老</h1>
            <div role="main" id="content" class="readcontent">
              ${"<p>所谓后患，可能不会立马显现。随着个人修为与际遇变化，将来也未尝没有化解的可能。</p>".repeat(8)}
            </div>
            <div class="mulu-box"><p>
              <a class="btn" href="/chapter/abc/prev.html" id="linkPrev">上一章</a>
              <a class="btn" href="/chapter/abc.html" id="linkIndex">章节目录</a>
              <a class="btn" href="/chapter/abc/next.html" id="linkNext">下一章</a>
            </p></div>
            </body></html>
        """.trimIndent()

        /** 有容器但正文极短：CSR 空壳的典型形态 */
        val SHELL_HTML = """
            <html><body><div id="content">加载中…</div></body></html>
        """.trimIndent()

        /** 最后一章：没有 #linkNext，正文充足 */
        val LAST_CHAPTER_HTML = """
            <html><head><title>185.赏功-书名</title></head><body>
            <h1>185.赏功</h1>
            <div id="content">
              ${"<p>所谓后患，可能不会立马显现。随着个人修为与际遇变化，将来也未尝没有化解的可能。</p>".repeat(8)}
            </div>
            <div class="mulu-box"><p>
              <a class="btn" href="/chapter/abc/prev.html" id="linkPrev">上一章</a>
              <a class="btn" href="/chapter/abc.html" id="linkIndex">章节目录</a>
            </p></div>
            </body></html>
        """.trimIndent()
    }
}
