# 阅读模式取章解耦 — 实现规格（给实现者）

> 设计依据见 [`reading-mode-fetch-decouple-plan-20261022.md`](./reading-mode-fetch-decouple-plan-20261022.md)（顶层设计与 A/B 切换/兜底论证）。
> 现象与证据见 [`reading-mode-next-chapter-lost-20261022.md`](./reading-mode-next-chapter-lost-20261022.md)。
> 本文是**可照着写代码的规格**：文件清单、类/接口形状、签名、与现有代码的衔接点、单测、实施顺序。
>
> **范围**：把"取下一章"从"借用浏览标签的 WebView"解耦成"A：原生 HTTP（OkHttp+jsoup+Cookie）为主、B：阅读模式专属取章 WebView 为兜底"的双层结构。**显示层（`KeyphoneReaderView`/`KeyphoneReaderPager`）不动。**

---

## 0. 实现者必读的现有代码锚点

先读这几处，否则会接错线：

| 锚点 | 位置 | 作用 |
|---|---|---|
| 取章总入口 | `KeyphoneBrowserActivity.requestNextChapter`（`:1446`） | 翻到章末催取下一章；本规格要改它走新通道 |
| 取章结果分发 | `KeyphoneBrowserActivity.handleChapterResult`（`:1527` 起） | 解析 JS 回传 JSON；A/B 通道的结果也走这条入口 |
| 章节入库 | `KeyphoneBrowserActivity.appendChapter`（`:1639`） | `html→plain→ChapterText→pager.appendChapter`；**复用，不动** |
| 取章外壳 | `KeyphoneBrowserActivity.startReaderFetch` / `rebootReaderFetch`（`:1394`/`:1688`） | 旧 WebView 通道；B 通道复用外壳 HTML，但挂到专属 WebView |
| 会话状态 | `KeyphoneBrowserActivity.ReadingSession`（`:1038`） | `nextUrl` / `recorded` / `currentUrl` / `fetchInFlight` / `fetchToken` / `endVerified` / `channelLostReported`；**结构不动，新增 1 个字段** |
| 正文提纯 | `KeyphoneBrowserActivity.htmlToPlainStatic`（`:5507`） | `Html.fromHtml(LEGACY)` → CharSequence；**A 通道复用它**，jsoup 只负责定位+截 HTML 片段，提纯仍走 fromHtml（与显示层口径一致） |
| Cookie 获取范式 | `download/DefaultFileDownloader.kt:46` | `CookieManager.getInstance().getCookie(url)` —— **照抄** |
| OkHttp 依赖 | `di/AppModule.kt`（`:171` `@NoCacheClient`） | 现成的无缓存 client，**注入复用** |
| UA | `useragent/DefaultUserAgentProvider` | DEFAULT/MOBILE/DESKTOP/CUSTOM，注入复用 |
| JS 取章提取 | `js/KeyphoneReadingMode.js`：`chapterFrom`（`:658`）/`extractHtml`（`:459`）/`findNextUrl`（`:479`）/`parseHtml`（`:214`） | **B 通道仍用这套 JS**（不动）；A 通道要把 `extractHtml`+`findNextUrl` 的启发式 port 成 jsoup 规则 |
| 显示回调 | `KeyphoneBrowserActivity.onReadingChapterShown`（`:1701`） | 翻到新章时更新 session/落盘；**不动** |
| 分页引擎 | `KeyphoneReaderPager` | 接收 `ChapterText`；**不动** |

> 关键不变量：**A/B 两条通道产出的章节，最终都汇入 `appendChapter(...)` → `pager.appendChapter(ChapterText)`**。新通道只换"怎么拿到 url/title/html/next"那一段，下游全不变。

---

## 1. 新增文件清单

```
keyphone/
├─ reading/
│  ├─ ReadingSiteRule.kt          # 单条站点规则（数据类）
│  ├─ ReadingSiteRules.kt         # 规则表（按域名查；内置 uukan.org）
│  ├─ ReadingDomainVerdict.kt     # 域名 SSR/JS 判定缓存（内存级）
│  ├─ ChapterFetchResult.kt       # 通道统一结果（成功/提空/网络错/end）
│  ├─ ReadingChapterFetcher.kt   # 方案 A：OkHttp+jsoup+Cookie 实现
│  └─ KeyphoneFetchWebView.kt    # 方案 B：专属取章 WebView（不进池）
└─ （现有文件改动见 §2）
```

包名建议 `acr.browser.lightning.keyphone.reading`，与显示层（`keyphone` 根包）分开，职责清晰。

---

## 2. 逐个文件的规格

### 2.1 `ReadingSiteRule.kt` — 单条站点规则

```kotlin
package acr.browser.lightning.keyphone.reading

/** 站点取章规则。A 用选择器直提（SSR），B 用 JS 兜底（CSR）。 */
data class ReadingSiteRule(
    /** 规则匹配的 eTLD+1 或完整 host（小写，无端口） */
    val host: String,
    /** 正文容器 jsoup 选择器（如 "#content" 或 ".chapter-content"） */
    val contentSelector: String,
    /** 「下一章」链接的 jsoup 选择器；为空表示用通用启发式（a 文本匹配"下一章"等） */
    val nextLinkSelector: String?,
    /** 「上一章」链接选择器（仅用于排除，避免把 prev 当 next） */
    val prevLinkSelector: String?,
    /** 传输方式：SSR_OK=静态 HTML 可直提；JS_REQUIRED=必须跑 JS；AUTO=运行时判定 */
    val transport: Transport
) {
    enum class Transport { SSR_OK, JS_REQUIRED, AUTO }
}
```

要点：
- `host` 用 `KeyphoneGuardStore` 同款的 eTLD+1 归一化（复用，不重复造轮子）。
- `nextLinkSelector == null` 时走通用启发式（port 自 JS 的 `findNextUrl`：`a[href]` 文本匹配"下一章/下一页/next"等，排除"上一章/prev"）。**通用启发式是 fallback，规则表里登记的站优先用显式选择器。**

### 2.2 `ReadingSiteRules.kt` — 规则表

```kotlin
object ReadingSiteRules {
    private val rules: Map<String, ReadingSiteRule> = mapOf(
        // uukan.org：正文在 #Content / .Content CSS 容器，下一章链接在页脚导航
        // 实现者需在真机/PC「查看源代码」确认选择器后填准；下面是结构占位
        "uukan.org" to ReadingSiteRule(
            host = "uukan.org",
            contentSelector = "#content, .content, .chapter-content",
            nextLinkSelector = null,          // 用通用启发式（uukan 的 next 链接文本是"下一章"）
            prevLinkSelector = null,
            transport = ReadingSiteRule.Transport.SSR_OK
        )
        // 后续站点在此追加
    )

    /** 按页 URL 查规则；无匹配返回 null（调用方走 B 通用路径） */
    fun forUrl(url: String): ReadingSiteRule? { /* 取 host → 归一化 → 查表 */ }
}
```

实现者第一项任务：用 PC 浏览器打开 uukan.org 任一章，"查看网页源代码"，确认正文容器和下一章链接的真实选择器，填进表。**这是方案 A 能不能在 uukan 上跑通的钥匙，必须实测填准。**

### 2.3 `ReadingDomainVerdict.kt` — 域名判定缓存

```kotlin
/** 域名 → SSR/JS 的运行时判定缓存（内存级，重启重判） */
class ReadingDomainVerdict {
    private val map = HashMap<String, Verdict>()
    enum class Verdict { SSR, JS }

    fun get(host: String): Verdict? = map[host]
    fun set(host: String, v: Verdict) { map[host] = v }
    fun clear(host: String) { map.remove(host) }
    fun clearAll() { map.clear() }
}
```

单例（DI `@Singleton` 或 Activity 持有，二选一；建议 DI 单例，跨阅读会话复用）。

### 2.4 `ChapterFetchResult.kt` — 通道统一结果

把 A 和 B 的产出统一成一个类型，下游（`handleChapterResult` 的接法）只认它，不再解析 JSON：

```kotlin
sealed class ChapterFetchResult {
    /** 取到一章。next 空串=这章未发现下一章链接（不是"全书完"，见 End） */
    data class Ok(
        val url: String,
        val title: String,
        val html: String,        // 正文 HTML 片段（交给 htmlToPlainStatic 提纯）
        val next: String         // 下一章 URL，空串=没认出来
    ) : ChapterFetchResult()

    /** 提空（HTTP 200 但正文不达标）——A 走这条触发降级 B */
    data object Empty : ChapterFetchResult()

    /** 网络/HTTP 错误——不算提空，不污染域名缓存，走"取章失败可重试" */
    data class Error(val message: String) : ChapterFetchResult()

    /** 该站确实没有下一章（next 指向自己 / 已在 recorded 里且复核过） */
    data object End : ChapterFetchResult()

    /** 通道尚未就绪（B 的外壳还在 boot）——下次按键重试 */
    data object NotReady : ChapterFetchResult()
}
```

### 2.5 `ReadingChapterFetcher.kt` — 方案 A 实现

```kotlin
/** 方案 A：OkHttp + jsoup + CookieManager 取章。纯 JVM 可单测。 */
class ReadingChapterFetcher(
    private val client: Deferred<OkHttpClient>,   // 注入 @NoCacheClient
    private val userAgentProvider: DefaultUserAgentProvider,
    private val rules: ReadingSiteRules,
    private val verdict: ReadingDomainVerdict
) {
    /**
     * 取一章。挂起函数，跑在 IO 调度器。
     * @param url 章节 URL
     * @param recorded 已读 URL 集合（用于"next 成环=结束"判定，与 JS 同口径）
     */
    suspend fun fetch(url: String, recorded: Set<String>): ChapterFetchResult
}
```

实现要点（照 `DefaultFileDownloader` 的 Cookie 套路）：

1. 建 `Request`：
   - `url(url)`
   - `header("User-Agent", userAgentProvider.current)`（注意用浏览标签同款 UA；如果是 MOBILE/DESKTOP 要对齐，否则站点回不同 HTML）
   - `header("Cookie", CookieManager.getInstance().getCookie(url).orEmpty())` —— **必须在主线程调 `getCookie`**（`CookieManager` 是主线程 API），调用方在 `withContext(Main)` 取一次 Cookie 传进来，或在 fetcher 里 `withContext(Main){ getCookie }` 取后切回 IO。**这一条是新坑，别在 IO 线程直接调。**
   - `Accept-Encoding: gzip` OkHttp 自动加、自动解。
2. `client.await().newCall(req).execute()` → `resp.body!!.string()`。
3. 非 2xx → `ChapterFetchResult.Error("HTTP ${resp.code}")`。
4. `Jsoup.parse(html, url)`：
   - 正文：`doc.selectFirst(rule.contentSelector)` → 取 `html()`；不存在/文本 < `MIN_CONTENT_CHARS`(200) → `Empty`。
   - 下一章：`rule.nextLinkSelector` 命中则取其 `absUrl("href")`；否则跑通用启发式（port 自 JS `findNextUrl`：遍历 `doc.select("a[href]")`，文本匹配"下一章/下一页/next"正则，排除"上一/prev"，`absUrl` 解析）。
   - 标题：`doc.title()` 或正文容器前一个标题元素（与 JS `pageTitleOf` 同口径）。
5. next 处理（与 JS `chapterFrom` 同语义）：
   - next 指向自己 → 视为 `End`。
   - next 在 `recorded` 里 → 不在这里判 End，**把 next 置空返回 Ok**（让宿主层按 `recorded` 去重，与现有 `appendChapter` 的"URL 重复返回 false"逻辑一致）。
   - 否则返回 `Ok(url=title对应的章节url, title, html=正文片段, next)`。
6. **正文 HTML 片段**：A 返回的是 `element.html()`（章节容器 innerHTML），交给现有 `htmlToPlainStatic` 提纯——与 B（JS 返回的也是 innerHTML）完全同口径，下游不变。

**单测要点**：用 MockWebServer 起一个假站点，返回写死的章节 HTML，断言 `fetch` 返回 `Ok` 且 next 正确；再返回空壳 HTML，断言 `Empty`；再返回 500，断言 `Error`。`ReadingSiteRules.forUrl` 用假 host 表。

### 2.6 `KeyphoneFetchWebView.kt` — 方案 B：专属取章 WebView

```kotlin
/**
 * 阅读模式专属取章 WebView（方案 B / CSR 兜底）。
 *
 * 不进 LimitedObjectPool，没有 awaitRelease 回调，没有跨标签 freeze ——
 * 重建是一条直线（见 [rebuild]）。渲染进程被杀只表现为"下次取章慢一次"。
 */
class KeyphoneFetchWebView(
    private val context: Context,
    private val onResult: (rawJson: String) -> Unit   // 回传 evaluateJavascript 结果
) {
    /** 外壳是否就绪（onPageFinished） */
    val isReady: Boolean

    /** 当前外壳代表的章节 URL（baseUrl） */
    val currentUrl: String?

    /** 载入取章外壳（同源 baseUrl = 章节URL）；复用 buildReaderFetchShell */
    fun loadShell(baseUrl: String, bootJson: String)

    /** 取下一章：evaluateJavascript(READER_JS_FETCH_NEXT)，结果回调 onResult */
    fun fetchNext()

    /** 渲染进程死了吗（onRenderProcessGone 置位）；fetchNext 时据此懒重建 */
    val isDead: Boolean

    /** 退出阅读模式时调用：destroy + 置 null；不在 onRenderProcessGone 里立即重建 */
    fun destroy()
}
```

实现要点：

- **WebView 创建**：`WebView(context)`，`settings.javaScriptEnabled = true`，`webViewClient = ` 自带 `WebViewClient`，重写 `onPageFinished`→`ready=true`，`onRenderProcessGone`→`dead=true`+`destroy()`+置空（**返回 `true`，宿主接管**，与 `TabAdapter` 同语义；但这里**不立即重建**，等下次 `fetchNext` 懒重建）。
- **`setDataDirectorySuffix`**：必须调 `WebView.setDataDirectorySuffix(...)` 或确保与本进程其它 WebView 数据目录隔离——**实现者必须验证**：本 App 已有多个 WebView 共存（浏览标签 + 隐身），查 `WebViewFactory` 怎么处理 data path 的，照同款处理，否则会 `DataSettableDir` 崩。这一条要实测。
- **外壳 HTML**：复用 `KeyphoneBrowserActivity.buildReaderFetchShell(baseUrl, session)`（把 boot 载荷 + `provideJs()` 拼成 HTML）。把它从 Activity 抽成可注入的 `ReadingFetchShellBuilder`（见 §3），A/B 都能复用（A 不需要外壳，但 A 的 boot 载荷构造逻辑可共用）。
- **JS 注入**：`KeyphoneReadingMode.provideJs()`（Mezzanine 生成），A/B 都用同一个 JS 文件。**B 通道的 JS 完全不动。**
- **`fetchNext`**：若 `dead || webView==null` → 先 `rebuild()`（new + loadShell(currentUrl)），等 `onPageFinished` 再 `fetchNext`；若 `!ready` → 返回 `NotReady`（宿主走现有 `scheduleShellNotReadyRetry`）。
- **结果回传**：`evaluateJavascript(READER_JS_FETCH_NEXT) { raw -> onResult(raw) }`，宿主的 `onResult` 解析 JSON（**复用现有 `handleChapterResult` 的 JSON 解析路径**，把 `JSONObject` 包成 `ChapterFetchResult`，见 §3）。
- **重建路径**（文档 §5.6）：
  ```
  fetchNext → isDead/new==null
    → new WebView + setup
    → loadShell(lastBaseUrl, lastBootJson)
    → onPageFinished → ready
    → evaluateJavascript(fetchNext) → onResult
  ```
  全程不碰 `LimitedObjectPool`。

**单测**：Robolectric 下 `KeyphoneFetchWebView` 可直接测（WebView 在 Robolectric 是 shadow）；构造后 `destroy()`，再 `fetchNext`，断言走了重建分支（用 spy/计数）。或抽一个 `FetchWebViewState` 纯逻辑类（dead/ready 状态机），单测状态机，WebView 实例部分手测。

---

## 3. 现有文件的改动

### 3.1 `KeyphoneBrowserActivity.kt`

**改动总原则**：`requestNextChapter` 不再无条件调 `currentWebView()` 当取章工具，改成"先选通道"。

#### 3.1.1 注入新依赖

Activity 的 `BrowserComponent` 注入里加：`ReadingChapterFetcher`、`ReadingDomainVerdict`、`ReadingFetchShellBuilder`（§3.2 新抽出的）。`KeyphoneFetchWebView` 由 Activity 自己 `lazy` 持有（生命周期跟随 Activity，不进 DI 图）。

#### 3.1.2 `ReadingSession` 加一个字段

```kotlin
private class ReadingSession(val entryUrl: String) {
    // ...现有字段不动...
    /** 当前会话该域名的取章通道选择（缓存 verdict 之后不再每章重判） */
    var transportChoice: TransportChoice = TransportChoice.UNDECIDED
    enum class TransportChoice { UNDECIDED, A_NATIVE, B_WEBVIEW }
}
```

#### 3.1.3 改 `requestNextChapter`

伪代码（实现者按真实签名落地）：

```kotlin
private fun requestNextChapter(showHint: Boolean): Boolean {
    val tabId = selectedTabId() ?: return false
    val session = readingModeTabs[tabId] ?: return false
    val next = session.nextUrl ?: return false            // null=已到末章
    if (session.fetchInFlight) return false
    session.fetchInFlight = true
    session.fetchToken += 1
    val token = session.fetchToken
    if (showHint) showReadingToast(R.string.keyphone_reading_wait_next)
    readerFetchHandler.postDelayed({ onReaderFetchTimeout(session, token, next) }, READER_FETCH_TIMEOUT_MS)

    lifecycleScope.launch {
        val result = chooseAndFetch(session, next)        // 见下
        if (session.fetchToken != token) return@launch     // 过期丢弃
        session.fetchInFlight = false
        routeFetchResult(tabId, session, result, next)
    }
    return true
}

private suspend fun chooseAndFetch(session, next): ChapterFetchResult {
    val rule = ReadingSiteRules.forUrl(next)
    // 通道选择（文档 §6.1）
    val useA = when {
        rule == null -> false                              // 无规则→B
        rule.transport == JS_REQUIRED -> false
        verdict.get(rule.host) == JS -> false
        else -> true                                       // SSR_OK / AUTO / 缓存=SSR
    }
    if (!useA) return fetchViaB(session, next)              // B 通道
    // A 通道
    return when (val r = fetcher.fetch(next, session.recorded)) {
        is Ok -> r
        is Empty -> { verdict.set(rule.host, JS); fetchViaB(session, next) }  // 降级+回填
        is Error, is NotReady, is End -> r                 // 不污染缓存
    }
}
```

`fetchViaB`：拿到/懒建 `KeyphoneFetchWebView`，`loadShell(session.currentUrl, bootJson)`（若需），`fetchNext()`；结果经 `onResult` 回调转成 `ChapterFetchResult`（见 3.1.4）。B 是异步回调式，要把它适配成 suspend：用 `suspendCancellableCoroutine` 包 `onResult`，超时复用现有 `READER_FETCH_TIMEOUT_MS`。

#### 3.1.4 `routeFetchResult`（统一结果分发，复用现有 `appendChapter`/`handleChapterEnd`）

```kotlin
private fun routeFetchResult(tabId, session, r: ChapterFetchResult, next: String) {
    when (r) {
        is Ok -> {
            // 复用 appendChapter（它内部 htmlToPlainStatic + pager.appendChapter + session.nextUrl=next）
            appendChapter(tabId, session, r.url, r.title, r.html, r.next)
            session.channelLostReported = false
        }
        is Empty -> { /* A 已降级 B；B 也提空才算失败 */ showReadingToast(R.string.keyphone_reading_page_failed) }
        is Error -> { showReadingToast(R.string.keyphone_reading_page_failed); /* 可重试，不置 nextUrl=null */ }
        is NotReady -> scheduleShellNotReadyRetry(session)
        is End -> handleChapterEnd(tabId, session, /* reason="no-next" */, /* 用 B 的 webView? null */)  // 见 3.1.5
    }
}
```

> `handleChapterEnd` 现签名要 `webView: WebView`（用来 `rebootReaderFetch`）。B 通道下"重建外壳"= `fetchWebView.loadShell(...)`，不是 `currentWebView()`。**改 `handleChapterEnd` 的重建分支**：若 `transportChoice==B`，调 `fetchWebView.loadShell(session.currentUrl,...)`；若 `A`，`End` 不需要重建（A 的 End 是 jsoup 确认无 next，直接信）。详见 3.1.5。

#### 3.1.5 `handleChapterEnd` 适配 A/B

- A 通道：`End` 是 jsoup 确认 next 指向自己 → **直接信，不复核**（jsoup 是确定性解析，没有 JS 那种"半截 HTML"噪声）。置 `session.nextUrl=null` + 提示 `keyphone_reading_end`。
- B 通道：保留现有"第一次不轻信、`rebootReaderFetch` 复核"逻辑，但 `rebootReaderFetch` 改成调 `fetchWebView.loadShell(...)`，不再调 `currentWebView()`。

#### 3.1.6 进/出阅读模式

- `openReaderPage`：**不再调 `startReaderFetch(currentWebView(), ...)`**。第一章正文已由 `entry` 提供（`reader.startChapter(entry.toChapter(1))`），取章外壳**懒建**：第一次 `requestNextChapter` 走 B 时才 `loadShell`。A 通道根本不需要外壳。
- `exitReadingMode`：**新增 `fetchWebView?.destroy()`**（销毁专属 WebView，释放进程）。`currentWebView()` 不再被阅读模式触碰，恢复成普通浏览标签，它的渲染进程死活与阅读模式无关。

#### 3.1.7 `currentWebView()` 的去留

`currentWebView()` 仍用于浏览态（地址栏、JS 弹窗判定等），**阅读模式下不再用它取章**。`KeyphoneJsDialogs` 里 `view === currentWebView()` 的判定不受影响（阅读模式下浏览标签的 WebView 还在，只是藏起来了）。

### 3.2 抽出 `ReadingFetchShellBuilder`

把 `KeyphoneBrowserActivity.buildReaderFetchShell(baseUrl, session)` 抽成独立类（B 通道和将来可能的"A 失败兜底外壳"都用）：

```kotlin
class ReadingFetchShellBuilder(private val js: String /* = KeyphoneReadingMode.provideJs() */) {
    fun build(baseUrl: String, session: ReadingSession): String
}
```

抽出来是为单测（shell 拼装纯字符串，好测）+ A/B 共用。

### 3.3 DI（`BrowserComponent` / `AppModule`）

- `AppModule` 加 `@Provides @Singleton ReadingSiteRules`（单例 object，其实不用 provides）。
- `BrowserComponent` 注入 `ReadingChapterFetcher`（构造参数：`@NoCacheClient Deferred<OkHttpClient>`、`DefaultUserAgentProvider`、`ReadingSiteRules`、`ReadingDomainVerdict`）。
- `ReadingDomainVerdict` `@Singleton`。
- `ReadingFetchShellBuilder` `@Singleton`（依赖 `KeyphoneReadingMode.provideJs()`，Mezzanine 生成的常量）。

### 3.4 `KeyphoneReadingMode.js`（B 通道）—— **不动**

B 通道仍用现有 JS。A 通道把 `extractHtml`/`findNextUrl` 的启发式 port 成 jsoup 选择器 + 通用启发式函数（Kotlin 实现），但 **JS 文件本身不改**（B 还要用）。

> 后续如果 B 通道也无人用了（全站都 SSR），可再删 JS；现在保留。

### 3.5 `keyphone_strings.xml`

新增/调整文案：
- `keyphone_reading_channel_lost`（A 失败+B 也失败时）："取章暂时不可用，稍后重试"（不再是"渲染进程被回收"，因为 A 通道根本不碰渲染进程）。
- 其余现有文案沿用。

### 3.6 `AGENTS.md` §3.2 文件表 / §7 速查表

新增 6 个文件的条目；§7「阅读模式（提取/取章）」一行改成"A：`reading/ReadingChapterFetcher`；B：`reading/KeyphoneFetchWebView`；规则表 `ReadingSiteRules`"。

---

## 4. A/B 切换的精确逻辑（实现者照此写 `chooseAndFetch`）

```
输入：nextUrl, session.recorded, ReadingSiteRules, ReadingDomainVerdict
─────────────────────────────────────────────────────
rule = ReadingSiteRules.forUrl(nextUrl)

if rule == null:                                   → B（无选择器，A 没法提）
elif rule.transport == JS_REQUIRED:               → B
elif verdict.get(rule.host) == JS:                → B
else:                                              → A
    r = fetcher.fetch(nextUrl, recorded)
    if r is Ok:            verdict.set(rule.host, SSR); return r
    if r is Empty:         verdict.set(rule.host, JS);  return fetchViaB(...)   # 本次也降级
    if r is Error/NotReady/End: return r          # 不污染缓存
```

**"A 提空"判定（`Empty`，在 `ReadingChapterFetcher` 内部）**：
- HTTP 200 + 拿到 HTML + 正文容器选择器命中但 `element.text().length < 200` → `Empty`。
- HTTP 200 + 正文不空但 next 通用启发式没找到且 rule 声明应有 next → 仍返回 `Ok`(next="")，**不判 Empty**（next 缺失不等于 CSR；交给 `handleChapterEnd` 走 End 路径）。
- HTTP 4xx/5xx/超时 → `Error`，不进 `Empty` 分支。

---

## 5. 红线遵守 checklist（实现者自检）

- [ ] A 通道无 WebView、无定时器、无心跳。
- [ ] B 通道无 `setInterval`/`requestAnimationFrame`/常驻心跳；`onRenderProcessGone` 是事件回调（允许），不立即重建（懒建）。
- [ ] B 的 `KeyphoneFetchWebView` 不进 `LimitedObjectPool`，不调 `webViewPool.acquire`。
- [ ] `CookieManager.getCookie` 调用在主线程（`withContext(Main)`），不在 IO 线程调。
- [ ] `catch` 分支全打 `KeydroidxLog.e`，无空 catch。
- [ ] JS 文件若被改（本规格说不改）必须保持纯 ASCII。
- [ ] A 通道返回的正文 HTML 仍交 `htmlToPlainStatic` 提纯，不另造提纯逻辑（与显示层口径一致）。
- [ ] Lint `:app:lintDebug` + `:app:assembleDebug` 必过（NewApi：`onRenderProcessGone`/`didCrash` 按 `TabAdapter` 现有 `SDK_INT` 判版本范式）。

---

## 6. 单测清单

| 测试 | 覆盖 |
|---|---|
| `ReadingChapterFetcherTest`（MockWebServer） | uukan 规则下：正常章→`Ok`+next 正确；空壳→`Empty`；500→`Error`；Cookie header 注入断言 |
| `ReadingSiteRulesTest` | `forUrl` 按 host 命中；eTLD+1 归一化；无规则返 null |
| `ReadingDomainVerdictTest` | set/get/clear；不持久化 |
| `ChapterFetchRouterTest`（纯逻辑：把 `chooseAndFetch` 的判定抽成可测函数） | rule=null→B；JS_REQUIRED→B；verdict=JS→B；SSR_OK→A；A.Ok→缓存SSR；A.Empty→缓存JS+调B；A.Error→不缓存 |
| `KeyphoneFetchWebViewStateTest`（状态机：dead/ready） | 初始→fetchNext→NotReady；onPageFinished→ready；onRenderProcessGone→dead→fetchNext 触发 rebuild |
| `ReadingFetchShellBuilderTest` | shell 含 boot 载荷 + js；baseUrl 正确注入 |
| 现有 `KeyphoneReaderPagerTest` | 不动，回归 |

`ReadingChapterFetcher` 是 `suspend` + OkHttp，用 `runTest` + MockWebServer + `CoroutineDispatchers` 的 IO 调度器替身。`CookieManager` 在 Robolectric 下是 shadow，`getCookie` 返回 null 时 fetcher 要能处理（测试可注入一个 `cookieProvider: (String)->String?` 接口，生产实现包 `CookieManager`，测试传假）。

---

## 7. 实施顺序（建议分 4 步，每步可独立验证）

1. **抽 `ReadingFetchShellBuilder` + `ChapterFetchResult` + `ReadingSiteRule/Rules/Verdict`**（纯数据/字符串，无网络）。先不接 Activity。配套单测过。
   - 验证：`./gradlew :app:testDebugUnitTest` 绿。
2. **`ReadingChapterFetcher`（方案 A）+ 单测（MockWebServer）**。仍不接 Activity。
   - 验证：单测覆盖 uukan 正常/空/错。
   - **此时实现者要用 PC 确认 uukan 真实选择器并填进 `ReadingSiteRules`**。
3. **`KeyphoneFetchWebView`（方案 B）+ 状态机单测**。仍不接 Activity 的取章主链路。
   - 验证：单测覆盖 dead→rebuild；Robolectric 下 `destroy()` 后 `fetchNext` 走重建分支。
4. **接 Activity 主链路**：改 `requestNextChapter`→`chooseAndFetch`→`routeFetchResult`；`ReadingSession` 加 `transportChoice`；`handleChapterEnd` 分 A/B；`exitReadingMode` 加 `fetchWebView.destroy()`；`openReaderPage` 去掉 `startReaderFetch(currentWebView())`。DI 注入。strings/AGENTS 同步。
   - 验证：`./gradlew :app:lintDebug :app:assembleDebug :app:testDebugUnitTest` 全绿。
   - 真机：uukan.org 长读，连取多章成功；模拟 B 死（开发机 `adb` 无法杀 sandboxed，改为代码里临时调 `fetchWebView.destroy()` 验证重建分支）；退出重进阅读模式正常。

> 第 4 步是唯一碰用户可见行为的步骤；前 3 步是纯增量、可独立单测、零回归风险。建议每步一次 `:app:lintDebug`。

---

## 8. 验收标准（对齐设计文档 §9）

1. **本案不复现**：uukan.org 长读，渲染进程死/切标签/退出重进，取下一章始终成功（A 通道，无 WebView 依赖）。
2. **B 重建可验证**：开发机上 `KeyphoneFetchWebView.destroy()` 后，下一次取章自动重建成功（不再依赖真机运气）。
3. **A/B 切换正确**：uukan（SSR_OK）走 A；人为把规则改成 `JS_REQUIRED`，走 B 且能取到；人为把 `contentSelector` 改错，A 提空→自动降级 B→域名缓存标 JS→后续直接 B。
4. **缺陷 C 根治**：A 通道的 `End` 是 jsoup 确认 next 指向自己，直接信；B 通道保留"第一次不轻信"复核。任一通道的"失败"永不置 `session.nextUrl=null`（只有 `End` 才置），用户不会再看到"明明还有下一章却说没有"。
5. **回归**：excessive-cpu / 空屏 / ANR / prev 整章遍历 四条不复发（本规格不动显示层与分页）。

---

## 9. 风险与回退

| 风险 | 应对 |
|---|---|
| uukan 真实选择器填错 → A 永远 `Empty` → 每章都降级 B | 第 2 步必须实测填准；即便填错也只会降级 B（与现状等同，不更差） |
| `WebView.setDataDirectorySuffix` 冲突崩溃 | 查 `WebViewFactory` 现有处理方式照抄；若无法隔离，B 的 WebView 复用浏览标签同进程数据目录（实测 uukan 走 A 根本不创建 B 的 WebView） |
| OkHttp UA 与浏览标签不一致导致站点回不同 HTML | `userAgentProvider.current` 与浏览标签同源；单测断言 UA header |
| Cookie 在 IO 线程调 `getCookie` 崩 | 主线程取 Cookie 后传进 fetcher（接口注入 `cookieProvider`） |
| 站点改版 SSR→CSR | `ReadingDomainVerdict` 自动重判；用户可"重新检测"清缓存 |

**回退**：若第 4 步接主链路后出问题，可保留 A/B 新代码但把 `chooseAndFetch` 临时改回"恒走 B（旧 WebView 通道）"——等价于现状，零回归。新代码不影响显示层。

---

## 10. 不做的事（明确边界）

- 不改 `KeyphoneReaderView` / `KeyphoneReaderPager` / `KeyphoneReaderThemes`（显示层与分页）。
- 不改 `KeyphoneReadingMode.js`（B 通道仍用）。
- 不改 `TabAdapter` / `LimitedObjectPool`（浏览标签自身的池子重建 bug 另案处理，与阅读模式解耦后不再阻塞）。
- 不做"取章从 JS port 到 OkHttp"之外的其它 JS 改造（如把 B 的 JS 也 port）。
- 不碰设置页 / 阅读偏好 / 历史落盘 / 拦截规则。

---

## 11. 实施记录（2026-10-23）

### 11.1 落地文件

```
app/src/main/java/acr/browser/lightning/keyphone/reading/
├─ ChapterFetchResult.kt        # Ok / Empty / Error / End / NotReady
├─ ReadingSiteRule.kt           # 单条规则（选择器 + transport + 最小正文字数）
├─ ReadingSiteRules.kt          # 规则表（按 eTLD+1 查；内置 uukan.org）
├─ ReadingDomainVerdict.kt      # 域名 SSR/JS 判定缓存（@Singleton）
├─ ReadingChapterRouter.kt      # A/B 选择 + 判定回填（纯逻辑）
├─ ReadingFetchShellBuilder.kt  # 取章外壳 + FETCH_NEXT_SCRIPT（从 Activity 下沉）
├─ ReadingCookieProvider.kt     # 接口 + WebViewCookieProvider（主线程取 Cookie）
├─ ReadingChapterFetcher.kt     # 方案 A：OkHttp + jsoup + Cookie
└─ KeyphoneFetchWebView.kt      # 方案 B：专属 WebView + FetchWebViewState 状态机
```

单测（`app/src/test/java/.../keyphone/reading/`）：`ReadingSiteRulesTest`、`ReadingDomainVerdictTest`、
`ReadingChapterRouterTest`、`ReadingChapterFetcherTest`、`KeyphoneFetchWebViewStateTest`。

### 11.2 uukan.org 选择器（对真实 HTML 核实，非占位）

```html
<div role="main" id="content" class="readcontent"><p>…</p></div>
<a class="btn btn-default" href="/chapter/…/X.html" id="linkPrev">上一章</a>
<a class="btn btn-default" href="/chapter/….html"   id="linkIndex">章节目录</a>
<a class="btn btn-default" href="/chapter/…/Y.html" id="linkNext">下一章</a>
<h1 class="pt10">184.最年轻的长老，雷长老</h1>
```

→ `contentSelector=#content`、`nextLinkSelector=#linkNext`、`prevLinkSelector=#linkPrev`、`titleSelector=h1`。

### 11.3 与规格的偏差（都是有意的）

| 项 | 规格 | 实际 | 原因 |
|---|---|---|---|
| A 通道单测 | MockWebServer | **OkHttp 拦截器返回写死响应** | 仓库没有 mockwebserver 依赖，加依赖要联网拉包；拦截器同样能断言 Cookie/UA 头与状态码分支 |
| B 通道 UA | `userAgentProvider.current` 对齐浏览标签 | B 用 WebView 默认 UA | 取 UA 是 `suspend`，在 `fetchNext` 的主线程路径上同步取会阻塞；A 通道已严格对齐 UA，B 只服务 CSR 兜底 |
| `handleChapterEnd` | 签名带 `JSONObject` + `WebView` | 改成 `(tabId, session, reason, verify)` | 结束后不再依赖浏览标签的 WebView；A 直接信 jsoup，B 才重建复核 |
| 旧取章方法 | —— | **删除** `startReaderFetch` / `rebootReaderFetch` / `handleChapterResult` / `READER_JS_FETCH_NEXT` / `READER_VIEWPORT` | 外壳与脚本下沉到 `ReadingFetchShellBuilder`；旧通道整个不再使用 |
| 单测环境 | —— | `app/build.gradle.kts` 加 `unitTests.isReturnDefaultValues = true` | 否则 `KeydroidxLog` 内部的 `android.util.Log` 在纯 JVM 单测里抛 "not mocked" |

### 11.4 验证结果

- `:app:testDebugUnitTest --tests "acr.browser.lightning.keyphone.*"`：**全绿**（分页 19 例 + 新增取章 25 例）。
- `:app:lintDebug`、`:app:assembleDebug`：通过。

#### 真机验证（192.168.1.5:5555，Android 13，2026-10-23）

**① A 通道（生产默认）—— 通过。** 从 183 章进入阅读模式后连续跨章，日志逐条如下：

```
取章通道=A（rule=uukan.org）url=…/3veJ…      取章成功 字数=5579 next=…/2NSU… 标题=184.…  通道=A    (1.15s)
取到第 2 章: 184.最年轻的长老，雷长老
阅读显示第 2 章: 184.…
取章通道=A（rule=uukan.org）url=…/2NSU…      取章成功 字数=5198 next=…/73O5… 标题=185.赏功  通道=A    (0.30s)
取到第 3 章: 185.赏功
```

另一次从 185 章起：`取章通道=A` → 186 章（1.14s）。**全程 `ps -A | grep -c sandboxed` = 1**
（只有浏览器自己的渲染进程），证实 A 通道**没有创建任何 WebView**。

**② 降级 + B 通道 —— 通过。** 临时把 `contentSelector` 改成不存在的选择器后：

```
取章通道=A（rule=uukan.org）url=…/2NSU…
提空：正文选择器未命中 #content-not-exist-…（KeyphoneReadingFetch）
A 提空，降级 B（域名 uukan.org 记为 JS）
创建专属取章 WebView（方案 B）→ 载入取章外壳 shellChars=34407 → 取章外壳就绪（97ms）
取章外壳尚未就绪，第 1 次延时重试 → 取章请求 #2
取章通道=B（rule=uukan.org verdict=JS）      ← 缓存命中，不再白试 A
取章回调(B) ok=true … → 取到第 2 章: 185.赏功
```

紧接着翻到章末第二次取章：`取章通道=B（verdict=JS）` → 外壳按新 baseUrl 重载一次 → `取到第 3 章`。
验证完已把选择器改回 `#content` 并重装生产包（单测复跑全绿）。

**③ 退出阅读模式 —— 通过。** `退出阅读模式: 已读 2 章 … -> …/2NSU…`，回到真实页面；
专属取章 WebView 由 `exitReadingMode` / `clearReadingModeOnNavigation` 销毁，`sandbox` 计数回到 1。

**未覆盖**：`JS_REQUIRED` 分支只由单测覆盖（真机没单独造这个构建）；
B 通道「渲染进程被回收后自动重建」仍未在真机上强制复现（本机 shell 无权限杀 sandboxed 进程），
但状态机已由 `KeyphoneFetchWebViewStateTest` 6 例覆盖。

### 11.6 长跑压测：22 章 / 26 分钟 / 每秒一页（2026-10-23）

复现原故障场景（长时间慢速阅读、连续跨章）的自动化压测。脚本跑在**设备侧**
（`nohup sh page.sh`，`input keyevent 20` + `sleep 1`），避免 Host 端 adb 往返影响节奏。

| 项 | 结果 |
|---|---|
| 起点 → 终点 | 第 183 章 → 第 204 章（第 23 章是「关于最近的事」公告章，也正常取回） |
| 时长 / 页面数 | 10:54:48 → 11:20:39（26 分钟），每秒一页约 1450 页 |
| 取章通道 | **全部 A**（`取章通道=A（rule=uukan.org）` 22 次，无一次降级 B） |
| 取章失败 / 跳过 | **0 / 0** |
| `判结束`（误报没有下一章） | **0** |
| `渲染进程死亡` | **0**（上一轮同机测试时 10:48 还死过一次，本轮压测期间一次都没死） |
| 主线程连续忙 | **0** |
| `ps \| grep -c sandboxed` | 全程 **1**（只有浏览器自身，A 通道确实不建 WebView） |
| 内存 | TOTAL PSS 212 MB → 224 MB；Native Heap 39.5/52 MB → 48.9/62.8 MB（12 章队列窗口，增长有界） |
| 退出 | `退出阅读模式: 已读 23 章 … -> …/1Rlas…`（落到第 204 章），页面正常 |

**结论**：原故障的两个触发条件都被绕开 —— ① 取章不再依赖浏览标签的 WebView（渲染进程死不死都无所谓，
且 A 通道压根不建 WebView，没有可死的对象）；② 即使失败也只会提示「取章失败、可再按一次」，
`nextUrl` 不会被置空，不会再把「暂时取不到」说成「全书读完」。

### 11.7 起点（m.qidian.com）实测：无规则的 CSR 站走 B 通道（2026-10-23）

用户要求「拿起点页面测 20 章」。实测站点：`https://m.qidian.com/chapter/1012584111/…`（《神秘复苏》），
规则表里**没有**这条规则 → 每次都走 B（日志 `取章通道=B（rule=- verdict=null）`）。

| 项 | 结果 |
|---|---|
| 起点 → 终点 | 第 1 章 → 第 23 章（11:43:30 → 11:56:58，13.5 分钟，每秒一页） |
| 通道 | **全部 B**（专属取章 WebView；46 次请求里含 notready 重试，成功入库 23 章） |
| 取章失败 / 成环 / 判结束 | **0 / 0 / 0** |
| 渲染进程死亡 / 主线程卡顿 | **0 / 0**（唯一一次 4.5s 卡顿在 11:43:26，是**进入阅读模式前**加载起点页面本身，属站点脚本，不是阅读模式） |
| 正文质量 | 纯文本干净（无广告、无弹窗），页脚 `76% 11:56` 正常 |
| 退出 | `已读 24 章 … -> …/421114477/`，正常落到第 23 章 |

**起点的一个特性（值得记录）**：它的章节 URL 会被服务端**重定向**——请求 `419590035` 实际落到
`419454186`，而这一页的「下一章」又指回 `419590035`。因为每次重定向都会落到一个**新**的章节页，
链路仍然单调前进（我们实测连读 23 章没问题）；但如果落在已读章节上就会变成死循环。
为此在 `routeFetchResult` 里加了**成环检测**：

- `appendChapter` 返回 false（取回的章已在队列里）或 `next` 落在已读轨迹里 → 判定成环；
- 成环时**不置空 `nextUrl`**、不报「已到最后一页」，而是提示
  「这一章之后取不到新章节（可能需要登录或购买）」并暂停取章；
- `session.loopReportedFor` 去重，同一个回环只提示一次；用户登录/购买后再按一次即可自然继续。

A 通道也会在日志里打 `站点重定向: <请求> -> <落地>`，排查这类站时一眼可见。

### 11.5 回退

把 `chooseAndFetch` 第一行改成 `return fetchViaB(session, next)` 即可退回「只用专属 WebView」的等价形态；
再不行可 `git revert` 本次提交 —— 显示层（`KeyphoneReaderView`/`Pager`）完全没动，回退零风险。
