# 阅读模式渲染隔离方案（评审稿）

> 目标：彻底消除「全屏阅读看着看着文字全部消失成背景色、退出后一直卡在『正在打开』」。
> 本文先摆问题与日志证据，再给方案，供评审。排查记录见
> [`reading-mode-blackscreen-diagnostics.md`](./reading-mode-blackscreen-diagnostics.md)，
> 本次复现的完整日志在 `logs/repro-20261020-0929/`。

---

## 一、存在的问题

### 现象（用户口述）

- 全屏阅读模式下，看着看着**文字全部消失、只剩背景色**；
- 退出全屏后，**一直卡在「正在打开…」提示**，退不出去；
- 复现成本高，要连续读很久才会触发。

### 根因分两层

#### 第一层：渲染进程陷入 CPU 死循环 / 合成停滞（→ 空屏）

阅读模式连读多章后，WebView 渲染进程开始持续烧 CPU，合成管线停住，屏幕不再出新帧，
文字就消失成背景色。证据（`logs/repro-20261020-0929/`）：

- 帧回调停滞 15 分钟未返回（`postVisualStateCallback` 注册后一直没回调）：
  ```
  [10-20 09:28:31.917][E][KeyphoneBrowser] 【阅读现场】帧回调已等待 905291ms 未返回
    （页面可能不再出帧 / 合成卡住） home=false tab=6 wv=true progress=100 ... fullscreen=true
  ```
  `905291ms ≈ 15 分钟`，即自 **09:13 左右**起合成管线就已停死，而渲染进程直到 09:29 才被杀。
- 拼章把多章合并进同一 DOM，DOM 体量随翻页只增不减：
  ```
  [10-20 09:27:15.532][D] 阅读 JS 现场: active=1 loading=0 ... chapters=4 domPages=4 seq=5
    next=m.qidian.com/chapter/1037806577/763880334/ root=1 scroll=33435 viewport=563
    docHeight=47276 nearBottom=0
  ```
  ```
  [10-20 09:28:31.313][D] 阅读视觉现场: ... docH=47275 rootH=47276 textLen=9928
  ```
  `textLen=9928` 字符、4 章——**纯文字体量极小**，正常一次性渲染不到 100ms。
  能烧 16 分钟 CPU，**必然不是「渲染一次」，而是有东西在循环触发 reflow/composite**。
- 约 16 分钟后系统按 excessive cpu 杀掉渲染进程：
  ```
  10-20 09:29:29.893 I/ActivityManager: Killing 3049:com.android.webview:sandboxed_process0:...
    (adj 900): excessive cpu 9100 during 300061 dur=962977 limit=2
  ```

#### 第二层：渲染进程死亡后恢复失败（→ 卡在「正在打开」）

`onRenderProcessGone` 触发后 `TabAdapter` 试图重载，但恢复链路有两个缺陷：

- `webView()` 仍返回已 `destroy()` 的尸体，`loadUrl` 是空操作：
  ```
  10-20 09:29:29.984 W/cr_AwContents: Application attempted to call on a destroyed WebView
    at ...WebViewChromium.loadUrl(...)
    at android.webkit.WebView.loadUrl(WebView.java:743)
    at acr.browser.lightning.browser.tab.TabAdapter$setup$5$3.invokeSuspend(TabAdapter.kt:222)
  ```
  （同样的告警在 08:58:34 对 tab=1 也出现过，**不是偶发**。）
- 尸体没从对象池摘掉，后续被池驱逐时触发 `awaitRelease→freeze()`，把刚重建的替换 WebView
  一起 destroy：
  ```
  [10-20 09:29:35.987][E][TabAdapter] 重载后仍无 WebView tab=6
    url=https://m.qidian.com/chapter/1037806577/763691857/
  ```
- 于是 `currentWebView()` 永远返回 null，`updatePageLoading` 在 `webView==null` 时无条件显示
  「正在打开…」，死活退不出去。看门狗识别到异常但当时只记录、不恢复：
  ```
  [10-20 09:29:31.122][E][KeyphoneBrowser] 【阅读异常现场】阅读态但无 WebView
    （渲染进程可能已死亡） | ... 章数=4 | 最近章节=https://m.qidian.com/chapter/1037806577/763880303/
  ```
  设备直到安装修复包前，一直停在 `wv=false tab=6` 的死态。

### 为什么会烧 CPU：寄生式设计，站点后台脚本没被中和

当前阅读模式是「寄生式」——阅读 JS 注入到**起点的页面里**，在源页面上提取正文、原地重排：

- `KeyphoneReadingMode.js` 的 `enter()` 用 `document.body.innerHTML = ''`（第 1229 行）清空 body，
  只剔掉**提取内容里**的 `<script>`（第 109 行 `NOISE_TAGS` 含 `'script'`）；
- 但 `innerHTML = ''` **不会取消站点在页面加载时就已注册的副作用**：起点的
  `setInterval`/`setTimeout`、`requestAnimationFrame` 循环、挂在 `window`/`document` 上的
  scroll/resize 监听、`IntersectionObserver`、埋点/广告脚本，全都继续跑；HEAD 里的
  `<script>` 更是碰都没碰。
- 阅读模式下 DOM 随拼章越拼越大（4.7 万 px），这些站点后台脚本每次跑都读布局属性
  （`getBoundingClientRect`/`offsetTop`/`scrollHeight`）→ 在大 DOM 上触发**强制同步 reflow**，
  且是循环触发 → 分钟级 sustained CPU → 合成管线被拖死 → 空屏。

> 结论：瓶颈不是文字量，是**站点后台脚本在越拼越大的 DOM 上循环 reflow**。
> 阅读 JS 自己的 `pump`（每 800ms，第 1264 行）、`onScroll`（翻页时）也读 `scrollHeight`
> 会强制 reflow，但都做了节流，是次要贡献。

---

## 二、方案：渲染隔离——提取后关掉源页，在干净页渲染

### 核心思路

把「渲染正文」和「取正文」从同一个源页面上彻底分开：

1. **提取**：在源页面上跑一次提取，拿到当前章正文 HTML + 下一章 URL；
2. **关掉源页**：把 WebView 导航到干净页（`about:blank` 或 asset 页），源页面连同它的所有
   脚本、定时器、监听器一起消亡——CPU 死循环的根从结构上消失；
3. **干净页渲染**：把提取到的纯文字 HTML 注入干净页，阅读 JS 只负责排版/翻页/主题，
   **不自己发网络请求**；
4. **后台取下一章**：取下一章的工作放在**后台**做（不经过阅读页的 JS），取到正文 + 下一章
   地址后注入阅读页，取完即丢弃后台上下文。

### 为什么没有 CORS 障碍（澄清一个常见误判）

CORS 限制的是**页面里 JS 发的跨域请求**。本方案里：

- 阅读页是干净页（`about:blank` / asset），**它不发任何 fetch**，不存在跨域；
- 取下一章要么走 **native（OkHttp）**、要么走**一个挂在源 origin 上的后台 WebView**——
  二者都不经过阅读页的 JS，CORS 根本不适用。

所以「阅读页一旦离开源页面就没法 fetch 下一章」是伪约束——它源于现在寄生式实现把 fetch
写在了阅读页 JS 里（`KeyphoneReadingMode.js:1077` `fetch(url, {credentials:'include'})`，
靠的是当前页面就在 m.qidian.com 这个 origin 上同源拿 cookie）。把取数挪出阅读页即可，
不需要让阅读页留在源页面上。

### 后台取章：两种实现，二选一

| 维度 | 方案 A：OkHttp + jsoup（native 取 + native 提取） | 方案 B：后台 WebView（挂源 origin，复用现有提取 JS） |
|---|---|---|
| 取数 | Kotlin OkHttp 带 cookie 取下一章 HTML | 临时 WebView 加载下一章 URL（同源） |
| 提取 | jsoup 在 native 侧做正文提取 + 找下一章 URL | 复用现成 `extractHtml`/`findNextUrl` JS |
| 站点脚本 | **完全不执行** | 每章**短暂**执行一次，取完即 destroy |
| 主要工作量 | 把 `extractHtml`/`findNextUrl` 的选择器从 JS port 到 jsoup（逻辑现成，翻译即可） | 改动小，但要管理临时 WebView 的生命周期/池化 |
| 风险 | 选择器 port 漏站点适配 → 取不到正文 | 每章瞬间跑一下站点脚本（翻页节奏由人控制，不是持续 16 分钟，可接受） |
| 资源 | 轻（一次 HTTP + jsoup 解析） | 重（每章起一个 WebView） |

**推荐方案 A**：既然要动这一刀，干脆让站点脚本一行都不跑最彻底；jsoup port 是确定性工作、
不依赖站点脚本能正常跑完。若担心选择器 port 漏适配，可先用方案 B 过渡，验证提取逻辑无误后
再迁到 A。

### 阅读页宿主选择

- `about:blank`：origin 为 null，注入的阅读 JS 照常跑，最干净；**切勿为图方便让干净页去
  fetch 起点**（那就又跨域了），所有取章必须走后台；
- 或 asset 页（`file:///android_asset/reader.html`）：可预置阅读 JS 外壳，宿主只填正文。
  注意 `file://` origin 的 file 访问策略，别开不必要的口子。

### 拼章 DOM 体量控制（搭配项）

迁移到干净页后，站点脚本已不存在，DOM 大一点也不会被 reflow 烧穿；但为省内存、翻页更顺，
建议同时把 `KEEP_PAGES`（现为 5）改成**真正 `remove()` 旧页**、只保留当前页前后各一页，
把 docHeight 压在几千 px 内。这条在干净页上做更安全。

---

## 三、过渡期已有的兜底修复（已构建并安装到设备）

在架构迁移落地前，已先合入两层兜底，确保空屏不再持续 15 分钟：

1. **`TabAdapter.kt` 渲染进程死亡恢复**：新增 `rendererDead` 标记，`onRenderProcessGone`
   不再仅 `_acquiredWebView=null`（尸体留在池 deque 里是病根），而是置标记；`webView()`
   检测到标记后先把尸体从池里 `release` 摘掉（`LimitedObjectPool.release` 只移出 deque、
   不触发 `awaitRelease`），再 `acquire` 全新 WebView。修复「重载后仍无 WebView」。
2. **`KeyphoneBrowserActivity.kt` 阅读看门狗自动恢复**：`probeReaderFrame` 在帧回调停滞
   超 15s 时、`detectBrokenReaderState` 在「阅读态无 WebView / 遮罩久挂」时，自动恢复
   （WebView 活着就原地刷新逼管线重来；WebView 死了就新开标签载入最后读到的那章），
   带 30s 冷却防循环。

兜底是治标：让空屏最多 15 秒自愈、渲染进程死后能正确重建。但**不消除 CPU 飙升的源头**——
源头要靠本方案的渲染隔离根治。

---

## 四、迁移步骤（建议）

1. **port 提取逻辑到 jsoup**：把 `KeyphoneReadingMode.js` 的 `extractHtml`/`findNextUrl`
   选择器翻译成 Kotlin/jsoup，写单测覆盖已适配站点（至少起点）。
2. **后台取章服务**：Kotlin 侧用 OkHttp（复用现有多客户端 + cookie）取下一章 HTML →
   jsoup 提取正文 + nextUrl → 通过 `evaluateJavascript` 注入阅读页。
3. **干净阅读页**：选 `about:blank` 或 asset 外壳；阅读 JS 精简为「只排版/翻页/主题/拼页
   trim，不 fetch」。
4. **进入/退出阅读模式流程改造**：进阅读 = 在源页提取一次 → 导航到干净页 → 注入正文；
   退阅读 = 导航回源章 URL（或按现有 `lastUrl` 走原生导航），`ReadingSession` 的
   `lastUrl`/`trail` 逻辑保留。
5. **DOM 体量控制**：`KEEP_PAGES` 真正删除旧页。
6. **回归**：长读（连续翻几十章）验证 CPU 不再 sustained、空屏不再出现。

### 风险与边界

- **站点适配**：方案 A 的 jsoup 选择器必须覆盖现支持站点；过渡期可保留方案 B 兜底。
- **cookie/登录态**：OkHttp 客户端要复用与浏览同源的 cookie，否则起点可能返回登录墙。
- **历史记录**：干净页 URL 不是真实章节 URL，`ReadingSession.lastUrl`/`trail` 仍记真实 URL，
  退出时导航回真实 URL，保证历史记录正确。
- **不在干净页发任何跨域 fetch**——这是硬边界，守住设计就稳。
- **主页红线**（见 AGENTS.md）：本方案只动阅读页宿主，不碰主页纯原生 View 红线。

---

## 五、待评审决策点

1. 走方案 A（OkHttp+jsoup）还是方案 B（后台 WebView）？倾向 A，可否？
2. 干净页宿主选 `about:blank` 还是 asset 外壳？
3. 是否同时做 `KEEP_PAGES` 真删旧页的 DOM 体量控制？
4. 迁移与当前兜底修复的并存策略：兜底先跑一阵积累信心，再开始迁移？还是直接开迁？

---

## 六、评审意见（2026-10-20）

### 事实核查结论：通过

- 文档引用的代码行号全部属实：`NOISE_TAGS` 含 `'script'`（`KeyphoneReadingMode.js:108-110`）、
  `fetch` 在 :1077、`innerHTML=''` 在 :1229、`pumpTimer=setInterval` 在 :1264、
  `KEEP_PAGES=5` 在 :48。
- 「寄生式设计」根因判断成立，且**提取算法确认不依赖布局信息**：`pickContainer`/
  `scoreCandidate`/`extractHtml` 全部基于 `textContent` 纯 DOM 打分（:296-518），
  `getBoundingClientRect` 只出现在阅读侧排版（trim/滚动锚点 :658-687），不进提取路径。
  因此「jsoup port 是确定性翻译工作」这个核心假设**成立**——方案 A 的最大不确定性被排除。
- 兜底修复已合入属实：`TabAdapter.kt:194-238` 的 `rendererDead` 标记与尸体摘除、
  `LimitedObjectPool.release` 只移出 deque 不触发 `awaitRelease`（`LimitedObjectPool.kt:39-43`）
  均与文档描述一致。
- CORS 澄清正确。

### 主要问题与建议（按严重度排序）

#### P0-1：cookie 桥是方案 A 的隐藏工作量，文档严重低估

代码里目前**不存在** WebView↔OkHttp 的 cookie 共享机制。现有 OkHttp 客户端都不带
WebView cookie；唯一先例是 `DefaultFileDownloader.kt:46` 每次请求前手动
`CookieManager.getInstance().getCookie(url)` 塞进请求头。方案 A 落地必须新增其一：

- 每章请求前在后台线程取 `CookieManager.getInstance().getCookie(url)` 作为 `Cookie`
  请求头（注意这是 binder IPC，严禁在主线程）；或
- 实现一个桥接 `CookieManager` 的 OkHttp `CookieJar`。

并且要有**登录墙检测**：响应体正文长度低于 `MIN_CONTENT_CHARS` 或命中登录页特征时，
不得误判为「本章无正文 / 已到最后一页」。建议在「风险与边界」中把这条从一句话
扩成独立工作项。

#### P0-2：干净页架构下，渲染进程死亡恢复链路会把正文弄丢（方案遗漏）

现有死亡恢复是「重载 `deadUrl`」。寄生式下 `deadUrl` 就是源章节 URL，重载即可；
干净页架构下 WebView 当前 URL 是 `about:blank`，死亡恢复重载出来的是**空白页，
正文全丢**，用户读了几章的阅读现场无法恢复。

修复方向：**native 侧必须持有已取章节的正文缓存**（entry 章 + 已拼 trail），
「阅读态 + 干净页」的死亡恢复 = 重建 WebView → 重新注入缓存正文 + 恢复到
`ReadingSession.lastUrl` 对应的滚动位置。这恰好是方案 A 的结构性优势——jsoup
提取结果天然就在 native 内存里；方案 B 的提取产物在 JS 侧，需要额外地把每章正文
回传 native 存档。**建议把「正文缓存 + 阅读态死亡恢复语义」补进方案正文，
并把「native 持有正文缓存」列为方案 A 的选型理由之一。**

#### P1-3：`about:blank` 宿主的 WebView 历史栈副作用，文档未提

进阅读 = `loadUrl("about:blank")` 会在历史栈压一条；退出阅读再 `loadUrl(lastUrl)`
又压一条。若阅读态的 BACK 语义有漏洞（比如某些路径先弹回空白帧），用户会看到一次
闪白。两个更稳的替代：

- 注入正文后 `WebView.clearHistory()`（进阅读清一次，BACK 直接回源页）；或
- 用 `loadDataWithBaseURL(entryUrl, html, "text/html", "utf-8", null)` 承载阅读页：
  单条历史、BACK 语义天然正确、`baseUrl` 仍可解析正文里的相对链接。宿主 DOM 同样干净。

**建议把「历史栈管理」列为干净页宿主的必选工作项，并优先评估 `loadDataWithBaseURL`。**

#### P1-4：方案 B 的「每章起一个 WebView」可以复用，但不改变推荐

方案 B 可优化为**一个常驻后台 WebView 反复 `loadUrl` 复用**（与池化 WebView 冲突
面更小），但站点脚本每章仍会跑一遍，低内存 240×320 机型上多了一个常驻渲染进程。
维持推荐 A 不变；方案 B 的最佳用途是**作为 jsoup 提取结果的 golden 对照**：
同一章分别用 JS `extractHtml` 与 jsoup port 提取，diff 正文，作为单测与切换门闩，
而不是作为线上过渡形态。这样「先 B 过渡再迁 A」的建议可以删掉，直接
「B 只做离线对照、线上一步切 A」。

#### P2-5：native 化时保留现有重试/结束语义

`KeyphoneReadingMode.js:1137-1143` 的语义是「只有确认无下一页才结束会话，网络失败
保留 `nextUrl` 可重试」。jsoup 版取章必须原样保留这套 FAIL_RETRY 退避与
endNotified 一次性语义，否则弱网下会提前误报「已到最后一页」。

#### P2-6：`KEEP_PAGES` 真删的优先级可降，但验证点要保留

渲染隔离后站点脚本已不存在，DOM 大不再触发 reflow 风暴，trim 的价值只剩
内存与翻页流畅度，不再是正确性问题——可从「搭配项」降为「后续优化」。
若做，需回归验证 `trimPages` 的滚动补偿（:868-885）在大字号（keydroidx_font_*）
+ 小屏（viewport 563px）下仍精确，删除旧页不引起跳动。

#### P2-7：watchdog 兜底不删

隔离失效（jsoup 选择器漏适配、cookie 过期、未知站点结构）时，看门狗仍是最后一道
防线。迁移完成后 `probeReaderFrame`/`detectBrokenReaderState` 保留，冷却参数可放宽。

### 对决策点的回应

1. **选 A**。新增两个工作项：cookie 桥（P0-1）、正文缓存 + 阅读态死亡恢复（P0-2）。
   方案 B 降级为 jsoup port 的离线 golden 对照，不做线上过渡。
2. **宿主倾向 `loadDataWithBaseURL`**（单条历史、BACK 语义正确），`about:blank`+
   `clearHistory` 为备选；asset 外壳引入 file:// 配置面，不建议。
3. **`KEEP_PAGES` 真删同意但降级为后续优化**，不与迁移捆绑发布。
4. **并存策略**：兜底继续跑积累信心；jsoup port 本身不改线上路径（纯新增 +
   单测），可以立即开工；切换门闩 = 「jsoup 提取与 JS 提取一致率」达标 +
   cookie 桥在登录态下实测通过。

### 其它小项

- 文末「主页红线（见 AGENTS.md）」引用有误：本仓库是 `CODEBUDDY.md`，
  keyphone 红线原文在 `../keydroidx-core/docs/NOKIA_DEVELOPMENT_RULES.md`。
- jsoup port 注意：jsoup 的 `text()` 与浏览器 `textContent` 在空白处理上略有差异，
  port `textLen`/`normalizeText` 时以「去空白后字符数」语义对齐即可，无实质障碍。
- 方案 A 的 OkHttp 请求要带与 JS 版一致的 `Cache-Control: no-cache` 语义
  （`KeyphoneReadingMode.js:1074-1077` 的注释说明了原因：章节可能原地更新）。

---

## 七、评审意见（第二轮）

### 对第一轮评审的核实与补充

- 第一轮的事实核查我抽查复核过，属实；以下只列**第一轮未覆盖**的点。

### P1-A（事实修正）：方案里的「`KEEP_PAGES` 真删旧页」其实已经是现状

方案正文与迁移步骤第 5 条写「把 `KEEP_PAGES`（现为 5）改成**真正 `remove()` 旧页」——
但 `trimPages`（`KeyphoneReadingMode.js:847-888`）**现在就是 `removeNode(oldest)` 真删**，
且带 `topVisibleChild` 锚点滚动补偿、且注释明确记录了「不能用被删高度做补偿，否则
往回跳两章」的实测教训。方案这一条属于对现状的误读，应改为「`KEEP_PAGES`
**调小**（如 3）」，并保留第一轮 P2-6 的验证要求（大字号 + 563px viewport 下
补偿精度回归）。

### P1-B：方案 A 有三个第一轮没点的实现坑，port 前必须先验证

1. **字符集**：`Jsoup.parse(htmlString, url)` **不会**按 `<meta charset>` 重解码——
   字符串在 OkHttp 侧已按 HTTP 头（或默认 ISO-8859-1）解码完成，meta 声明为时已晚。
   国产小说站大量 GBK，必须 `Jsoup.parse(body.byteStream(), null, url)` 让 jsoup
   自己嗅探，或显式按 meta/站点表指定。这是「jsoup port 是确定性翻译」论断的
   最大例外，单测必须含一个 GBK 站点样本。
2. **User-Agent**：OkHttp 默认 UA（`okhttp/x.y.z`）会被起点等站点返回不同页面
   甚至直接拦截。请求必须带 WebView 当前 UA（`WebSettings.getUserAgentString()`），
   并在单测/golden 对照中用同一 UA 取样，否则「离线对照一致、线上取不到正文」。
3. **SSR 验证前置**：方案 A 成立的隐含前提是章节正文**服务端渲染**。JS 版对
   拼进来的第 2..N 章本就是 `fetch` 静态 HTML + `DOMParser` 提取（:1077-1107），
   说明拼章路径已证明静态 HTML 够用——这是方案 A 的强力佐证，**但 entry 章是
   在 live DOM 上提取的**，不排除某些站点首屏 SSR、后续页 CSR 的差异。开工前
   用一条命令验证：`curl -A "<WebView UA>" <章节URL> | grep 正文首句`，通过再动手。

### P2-C：干净页架构下的注入通道与桥的生命周期

- 正文注入走 `evaluateJavascript` 传大字符串可行（单章 ~10k 字符，远低于限制），
  但要 JSON 转义；更稳的做法是宿主用 `loadDataWithBaseURL` 载入外壳，正文经
  `evaluateJavascript("appendPage(...)")` 分章追加。
- 注意 Activity 里已有实测注释（`KeyphoneBrowserActivity.kt:1390` 附近）：
  **对已加载完成的页面 `addJavascriptInterface` 不生效**。干净页若要用
  `__keyphoneReadingBridge__` 回传事件，桥必须在 `loadDataWithBaseURL` **之前**
  挂上；外壳重建 / WebView 重建后重挂。这条直接决定干净页外壳的加载顺序设计，
  方案正文没提。

### P2-D：`findNextUrl` 的 port 细节

`findNextUrl`（:521-554）有两层：`link[rel~=next]` 优先，退化为锚文本正则
（`NEXT_TEXT_RE`，且排除 `PREV_TEXT_RE`），还有 `SCAN_LIMIT` / `MAX_LINK_TEXT_LEN`
截断与 `normalizeUrl` 去重（`seen`）。port 时这些都得原样搬，`seen` 集合与
FAIL_RETRY 状态要放 native 侧（正好与 P0-2 的正文缓存放一起）。jsoup 对应物现成
（`select("link[rel~=next]")`、`absUrl("href")`），无技术障碍。

### 对决策点的回应（第二轮）

1. 选 A，同意。前置验证：P1-B 的 curl 冒烟（半小时成本，一票否决权）。
2. 宿主同意第一轮的 `loadDataWithBaseURL` 结论；补充约束：桥必须先挂后载（P2-C）。
3. `KEEP_PAGES` 条目按 P1-A 改写为「调小数值」，优先级同第一轮（后续优化）。
4. 并存策略同意第一轮。补充一条切换门闩：**线上双跑灰度**——迁移后首个版本
   保留 JS 拼章路径为 fallback，native 提取失败（正文 < `MIN_CONTENT_CHARS` 或
   登录墙特征）时自动落回现有寄生式，并打日志统计落回率；落回率收敛后再删旧路径。
   这比「离线对照达标即全量切」更适合复现成本极高的本 bug。

---

## 八、评审意见（第三轮）

### 事实核查（抽查复核）

- 前两轮引用的代码位置全部复核属实：`NOISE_TAGS`（:108-113）、`fetch`（:1077）、
  `innerHTML=''`（:1229）、`pumpTimer`（:1264）、`KEEP_PAGES=5`（:48）、
  `trimPages` 真删 + 锚点补偿（:849-891，含「不能拿被删高度做补偿」的实测注释）、
  `onRootClick`（:1163-1183）、`TabAdapter.rendererDead`（:290-352）、
  `exitReadingMode` 双分支（:1184-1229）、看门狗 `probeReaderFrame` /
  `detectBrokenReaderState`。
- 以下只列**前两轮未覆盖**的问题。

### P0-8（新增）：标签持久化 / 进程死亡恢复路径在干净页架构下会恢复出空白页

第一轮 P0-2 只覆盖了「渲染进程死亡」这一条恢复链路，但 `TabAdapter` 还有另外两条
同样依赖 `urlStateFlow`（= WebView 当前 URL）的路径：

- **App 进程死亡后重启**：阅读到第 N 章时浏览器被系统回收，下次启动恢复标签 →
  `BundleInitializer(bundle)` 按 bundle 里的 URL `loadUrl`。寄生式下该 URL 全程停在
  进入时的章节 URL，恢复 = 回到真实章节页；干净页下 bundle 里是 `about:blank` /
  `data:...`，恢复 = 空白标签。
- **标签冻结 / 池驱逐**：`awaitRelease → freeze() → save()` 同样把 `urlStateFlow`
  写进 bundle，解冻路径同上。

修复方向与 P0-2 互补且应一并设计：**「展示 / 持久化用 URL」与「WebView 实际 URL」
必须分离**——`save()` / 会话快照 / 恢复链路一律取 `ReadingSession.lastUrl`（阅读态
标签），P0-2 的 native 正文缓存负责渲染进程死亡时恢复「阅读现场」，本条负责
App / 标签死亡时恢复「落点」。只做其一，另一条路径仍会产出空白页。

### P1-9（新增）：地址栏与标签标题的显示语义

寄生式下源页面就是章节页，地址栏 / 标签标题天然显示真实章节 URL。干净页下 WebView
URL 是 `about:blank` / `data:...`，若不做覆盖，用户阅读几十章全程地址栏显示空白或伪
URL——与 `writeTrailHistory` 已写进历史的真实 URL 语义不一致。方案「风险与边界」只
写了历史记录，没写显示层。需要 host 侧显示态覆盖（可与 P0-8 的 URL 分离共用同一份
状态），属于用户可见回归，应在验收项里明确。

### P1-10（新增）：退出阅读的「只读一章」分支语义变化，方案未挑明

现有 `exitReadingMode` 有两个分支（:1205-1210）：只读了进入章 → `READING_JS_EXIT`
原地还原 DOM（**零网络、保留滚动位置**）；多章 → 导航 `lastUrl`。干净页架构下前者
必然消失——退出阅读**永远**要重新加载源页。方案第 4 步只写了「导航回源章 URL」，
没有把这条 UX 回退写出来（每次退阅读多一次完整页面加载，弱网下可能卡很久）。
建议：明确接受该回退并写入验收项「退出阅读最坏情况 = 一次完整导航」；同时决策
「重进阅读时是否 scrollTo 恢复到上次位置」（现状 `backup.scrollY` 机制随寄生式
一起消失）。

### P1-11（新增，备选路线「方案 C」）：`loadDataWithBaseURL(entryUrl, ...)` 可让干净页继承源 origin，现有 fetch 拼章原样可用

两轮评审都推荐 A（OkHttp+jsoup），但都忽略了第一轮 P1-3 自己提出的
`loadDataWithBaseURL` 有一个结构性红利：**baseUrl 决定文档 origin**。宿主以
`entryUrl` 为 baseUrl 载入干净外壳后，文档 origin 就是 `m.qidian.com`：

- 现有 `fetch(url, {credentials: 'include'})` 的同源拿 cookie 机制**原样可用**，
  发请求的是 WebView 自己——UA、Accept-Language、cookie store、字符集解码全部与
  现网行为一致。**P0-1（cookie 桥）、P1-B.2（UA）、P1-B.1（charset）三个坑全部
  自动消失**，`KeyphoneReadingMode.js` 的取数 / 拼章 / 重试 / 结束语义一行不改；
- 站点脚本同样一行不跑：页面不是真从网络加载的，HEAD 里的 `<script>` 根本不存在；
- 代价：需要真机半小时验证该 WebView 版本上 data-loaded 页面发同源 fetch 的 cookie
  行为（个别 Chromium 版本对 `loadDataWithBaseURL` 页面 origin 有特殊处理；验证成本
  与第二轮 P1-B.3 的 curl 冒烟同级）。另注意服务器 CSP 不会套用到该页（对注入
  阅读样式反而是利好）。

若验证通过，**C 可作为迁移第一阶段**（宿主隔离先行、取数链路后动），把 A 的大头
工作（jsoup port + cookie 桥 + golden 对照）从关键路径上摘下来；A 仍可作为第二阶段
的彻底 native 化。C 与 P0-2 / P0-8 的正文缓存、URL 分离需求正交，不冲突。
建议把 C 列入「待评审决策点 1」一并裁决，而不是直接锁死 A。

### P2-12（更正）：第二轮 P1-B.1 的「默认 ISO-8859-1」不适用于 OkHttp

OkHttp 的 `ResponseBody.string()` 在响应头无 charset 时默认按 **UTF-8** 解码
（HTTP 规范的 ISO-8859-1 默认值是 `HttpURLConnection` 的行为）。结论不变——GBK
站点仍会解出乱码、必须走 `response.body.byteStream()` 交 jsoup 嗅探——但机制描述
要修正，否则排查方向会走偏。

### P2-13（新增）：干净页的事件通道选择必须写死，不能留两可

现有 JS→native 有两条通道：JS 桥 `notify()`（push）与 `progress()` 脚本回传
（pull，字号 / 主题 / 轨迹同步已在用）。`probeReadingBridge` 的实测注释
（`KeyphoneBrowserActivity.kt:1390` 附近）确认**桥对已加载完成的页面不生效**。
干净页若按第二轮 P2-C「先挂桥后载入」执行则 push 通道可用；但若最终选 pull 轮询
路线，`link`（阅读页内链接转 native 导航）、`fail`、`end` 三个 push 事件就必须
改为轮询可见。方案应明确选定一种通道并覆盖全部事件，避免实现期随手混用导致
「链接点不动 / 失败无提示」这类静默丢失。

### 对决策点的回应（第三轮）

1. 维持选 A 作为终态，但**建议先花半小时真机验证方案 C**（data 页同源 fetch 带
   cookie）；通过则按 C→A 两阶段迁移，不通过再直接 A。无论哪条路，P0-8 的
   「URL 分离 + 持久化按 lastUrl 落地」都是前置必做项。
2. 宿主维持 `loadDataWithBaseURL` 结论；补充：baseUrl 恰好同时是 C 路线的 origin
   载体，一举两得。
3. 同意前两轮：`KEEP_PAGES` 调小为后续优化。
4. 并存策略维持；灰度 fallback 与 C 的关系需要理清——若走 C，fallback 形态就是
   「C 页面内 fetch 失败 → 退回寄生式重进」，比 A 的 native 提取失败判定更简单。

---

## 九、方案 C 真机验证结果（2026-10-20 11:57，通过）

### 验证环境

- 设备：`192.168.1.5:5555`（Q968 / Android 13 / WebView `101.0.4951.61`），即本次复现的同机型；
- 被测章节：`https://m.qidian.com/chapter/1037806577/763880334/`（09:27 日志里正在读的那章）；
- 手段：debug-only 临时探针（`app/src/debug/.../ReaderBaseUrlProbeActivity.kt` +
  `app/src/debug/AndroidManifest.xml`，release 不含、验证完删除），三阶段各做一次
  「读环境 + 同源 fetch 本章 URL」，结果走 logcat tag `ReadingProbe`。

### 三阶段结果

| 阶段 | 宿主方式 | `location.origin` | `document.cookie` | fetch | 长度 | 耗时 |
|---|---|---|---|---|---|---|
| 1 `1-real-page` | `loadUrl(章节URL)`（基准） | `https://m.qidian.com` | 450 字节 | 200 | 43157 | 385ms |
| 2 `2-data-baseurl` | `loadDataWithBaseURL(章节URL, 外壳, …)` | `https://m.qidian.com` | 450 字节 | 200 | 43113 | 352ms |
| 3 `3-data-nobase` | `loadDataWithBaseURL(null, 外壳, …)`（对照） | — | `SecurityError: Access is denied` | `Failed to fetch` | — | — |

补充：阶段 2 的 `shellRan=true`（data 页内联 `<script>` 正常执行），`href` / `document.URL`
均为真实章节 URL，`navigator.userAgent` 为标准 WebView UA（`…wv… Chrome/101…`）。

### 结论

**方案 C 成立**：`loadDataWithBaseURL` 以章节 URL 为 baseUrl 载入外壳后，文档 origin 就是
`https://m.qidian.com`，cookie 可读、同源 fetch 拿到与真实页面**同量级（43113 vs 43157，
差 0.1%）** 的章节 HTML。阶段 3 的对照失败证明阶段 2 的成功不是「什么页面都能 fetch」。

由此，第三轮 P1-11 里列的三个坑在 C 路线下**全部不需要**：

- P0-1 cookie 桥 —— 不需要，WebView 自己带 cookie；
- P1-B.2 UA —— 不需要，请求由 WebView 发出，UA / Accept-Language 与现网完全一致；
- P1-B.1 字符集 —— 不需要，WebView 按响应头 + meta 自行解码，GBK 站天然正确。

A 路线（OkHttp + jsoup）仍需要这三项，故 C 作为第一阶段的风险显著低于 A。

### 附带事实（对 A 也有用）

- **章节页确为 SSR**：同 UA 直接 `curl` 得到 55947 字节 HTML，`<div id="app" data-pid=mqd_P_bookread>`
  内已含正文 DOM，`<title>` 含章节名 → P1-B.3 的 SSR 前置验证对起点通过（其它站点仍需各自确认）。
- 起点的章节标题格式是 `10.潜龙灵体，脱胎换骨`（数字+点，**不是**「第 10 章」），探针里
  `hasChapter` 的正则因此为 false —— 是标题格式问题，不是正文缺失，不要误读。
- 阶段 2 的 `document.referrer` 为空（data 页没有导航来路）；fetch 请求头的 Referer 由文档 URL
  派生，起点实测不校验（200 正常返回）。若后续接入防盗链严格的站点，需要抓包确认这一项。

### 决策更新

- 决策点 1 由「选 A」改为 **「M0 = 方案 C 宿主隔离，M1 = 方案 A 可选」**：
  M0 只需要 `loadDataWithBaseURL` 外壳 + 正文缓存（P0-2）+ URL 分离（P0-8），
  **不需要** jsoup port、cookie 桥、UA/charset 处理；A 可在一个版本稳定后再评估是否值得做。
- 前两轮留下的 P1-B.3 / P2-12 / P2-D 仍是 A 路线的工作项，不阻塞 M0。
- 临时探针代码（`app/src/debug/AndroidManifest.xml`、`ReaderBaseUrlProbeActivity.kt`）在
  路线锁定后删除，**不得提交**。

---

## 十、M0 实施记录（2026-10-20）

M0（方案 C 宿主隔离）已落地，真机验证通过。改动三个文件：

- `app/src/main/js/KeyphoneReadingMode.js`
  - 新增 `extract()`：只读提取（复用 `extractHtml` / `findNextUrl` / `pageTitleOf`），
    不清 body、不动 viewport、不挂监听，返回 JSON（url / title / html / next / textLen）；
  - 新增 `enterWithContent(payload)`：在干净外壳上用外部正文起阅读器，`backup` 保持 null
    （外壳是我们自己的页，没有「原页面」可还原）；
  - boot 分流：`__keyphone_rm_boot__`（宿主已提取好正文）→ `enterWithContent`；
    `__keyphone_rm_noenter__`（宿主只想要 API，用于源页提取）→ 直接返回；
    都没有 → 旧的就地重排行为（保留，作为兜底与回归对照）。
- `app/src/main/java/.../keyphone/KeyphoneBrowserActivity.kt`
  - `enterReadingMode()` 改为三步：源页 `extract()` → `loadDataWithBaseURL(章节URL, 外壳)`
    → 外壳内联脚本用正文启动；
  - `buildReaderShell()`：外壳 = viewport + `<title>` + `__keyphone_rm_init__`（主题/字号）
    + `__keyphone_rm_boot__`（正文）+ 阅读脚本，**不含任何站点脚本**；脚本位于 body 末尾，
    执行时 body 已存在，省掉「等 onPageFinished」的竞态；
  - `verifyReaderBoot()`：350ms 起检查 `isActive()`，最多重试 4 次，失败则导航回章节页，
    绝不把用户留在空白 data 页上；
  - `exitReadingMode()`：删除「只读一章就地还原 DOM」分支——外壳上没法原地还原，
    一律 `loadUrl(lastUrl)`；拉取失败时用会话里记着的章节兜底；
  - `pullReadingProgress()` 自带自愈：`progress`/`diag` 都为空说明 `__keyphone_rm__`
    已不存在（阅读页被替换），直接清阅读态，避免按键卡在阅读路由；
  - 刷新（页面菜单 1 / 选项页刷新）在阅读态下改为退出阅读并回到真实章节页。
- `app/src/main/java/.../KeyphoneBrowserActivity.kt` 的 `ReadingModeJs`
  新增 `extractScript()`（注入 `__keyphone_rm_noenter__` + 阅读脚本 + 提取调用）。

### 真机实测补充的两个事实（决定实现细节）

- **`webView.getUrl()` 在 data 页上返回 `about:blank`**（`originalUrl` 是 data URL），
  只有 `onPageFinished` 的 `url` 参数与页面内 `document.URL` 是 baseUrl。因此：
  - 地址栏 / 标签标题 / URL 历史 / 会话快照不受影响（它们走 `onPageFinished` 的 url）；
  - 任何需要真实地址的新代码必须用 `currentRealUrl()`（阅读态下取 `ReadingSession.lastUrl`），
    `addCurrentPageToHome` 已按此修正，否则会存成 `about:blank`。
- **`postVisualStateCallback` 在 data 页上 8ms 就返回**，帧看门狗不会误判「合成卡死」；
  `canGoBack() = true`（data 页会在历史栈里压一条）。

### 真机验证（Q968 / Android 13 / WebView 101）

| 验证项 | 结果 |
|---|---|
| 进入阅读模式 | 日志 `进入阅读模式提取结果: textLen=2725 next=…/763880334/` → `打开阅读页: baseUrl=…/763771959/ shellChars=69515` → `阅读页已就绪`（约 200ms） |
| 拼章（同源 fetch） | 翻到页尾后 `fetch#1 https://m.qidian.com/chapter/…/763880334/` → 桥事件 `append 2`，正文接着往下排 |
| 章节入库 | `阅读模式章节入库: 8.上上签12`、`10.潜龙灵体，脱胎换骨`（历史记录与退出落点正确） |
| 退出（菜单 6 / 返回键） | `退出阅读模式导航: -> …/763880334/`，落到真实章节页（站点原版排版、地址栏正常） |
| 二次进入 / 字号 / 主题 | 正常（字号实时变化，主题轮换提示正确） |
| 阅读态 CPU | 渲染进程 0.0%（旧实现在大 DOM 上持续烧 CPU） |
| 进程被杀后重启 | 回到主页，历史里最后读的章节在最上（标签快照 URL 为真实章节 URL） |

### 与评审结论的差异（有意为之）

- **P0-2「native 正文缓存」未做**：渲染隔离后正文由 JS 侧持有，native 只在进入时传一次。
  渲染进程死亡时 `TabAdapter` 重载 `deadUrl`（真实章节 URL）→ 用户回到可读的章节页，
  按阅读键可重新进入；缓存整条 trail 的 HTML 只为了省一次重新提取，不值得这份复杂度。
  若后续要做「恢复后自动回到阅读态」，再补缓存（届时正文已在 native 手里，成本更低）。
- **P0-8「URL 分离」大部分免费**：地址栏 / 历史 / 快照都走 `onPageFinished` 的 url，
  只需修掉直接问 `webView.getUrl()` 的地方（已修 `addCurrentPageToHome`）。
- **P1-10「只读一章退出要重载」** 已按评审预期接受：退出恒为一次完整导航。

### 已知边界（后续）

- 标签冻结 / `saveState` 恢复：恢复出来的是进入时那一章的外壳（`__keyphone_rm_boot__`
  会重跑），拼过的后续章节不在了；Kotlin 侧阅读会话也已丢失，按键回到普通浏览语义。
  不影响可读性，属于后续优化。
- 看门狗恢复后不自动重进阅读态（仍停在真实章节页），需要用户再按一次。
- `KEEP_PAGES`（trim）未动，仍按第二轮 P1-A 的结论列为后续优化。

---

## 十一、M1：连续文字阅读 + 高度窗口 + 丢失自愈（2026-10-20）

### 演进过程（两次修正）

1. **M0 之后仍复现**「文字消失 → 重载 → 退出阅读」两次（12:39、21:11）。日志证明：帧看门狗
   静默（合成器正常）、`webView.url` 变 `about:blank`、视口高度突变（`389→192`）、滚动指令
   失灵。根因是 **阅读页是 data 页**：被 WebView 池回收 / 标签冻结 / Activity 重建后，
   `restoreState` 恢复不出 data 页 → `about:blank` → `isTabHome()` 当主页 → 阅读态丢失。
   普通页面在同一条链路上恢复完好，所以「正常浏览没事，只有阅读模式出事」。
2. **第一版修复「一章一页」硬切章**：内存问题解决了，但阅读体验退化——章尾不足一屏的内容被
   同一个按键吞掉、下一章从顶部硬切，用户感觉「跳」。这一版**已废弃**。

### 现在的形态（M1.5）

> 源页只读提取一次 → 源页随导航销毁 → 阅读页里**连续追加纯文字**（章与章的接缝就是一条
> 分隔标题，滚动不中断、不跳）；窗口按**文档高度**上限裁剪，超出就丢弃最早读过的章节。

**关键实测**：这台设备上，拼到 **9 章左右（docHeight ≈ 10 万 px）时沙箱渲染进程被系统杀掉**
（logcat `cr_AwContents` 警告 + `TabAdapter: 渲染进程死亡后已重新加载`）。所以窗口不能按章数
给（10 章 ≈ 15 万 px，必死），必须按高度给：`MAX_DOM_PX = 40000`（约 2~3 章）。

**JS（`KeyphoneReadingMode.js`）**

- `appendChapter()`：把「章节标题条 + 正文」追加到 root 尾部，**不动滚动位置**；
- `appendNext()`：页尾附近由宿主/心跳触发；命中 `chapterCache.next` 即刻追加，未命中才
  `fetchChapter()`（页内显示「正在加载下一章」）；
- `pump()`（800ms）：保持视口下方至少 `PRE_READ_SCREENS = 1.5` 屏未读文字，并重试预读；
- `trimChapters()`：`docHeight() > MAX_DOM_PX` 且最早章节已在视口上方
  `TRIM_SAFE_SCREENS = 2` 屏之外时才丢弃；补偿用**可见锚点相对 `scrollBy`**
  （不能用被删块高度：Chromium 锚定已补偿一次）；
- 删除：`turnNext/turnPrev`（章首回退）、`renderChapter`（清空重渲染）、
  `KEEP_PAGES` 按章数裁剪。

**宿主（`KeyphoneBrowserActivity.kt`）**

- 按键路由：`remaining <= 1.5 屏` 时按 `READING_JS_APPEND` 催追加，其余按键只拉章节轨迹；
- **阅读页丢失自愈 `recoverReaderPage()`**：按键拉取发现 `__keyphone_rm__` 消失，或看门狗
  2 秒采样发现「阅读态下 WebView 的 url 变成 http 页」→ 清阅读态 → 导航回
  `ReadingSession.lastUrl` → `maybeReenterReading()` 自动重进阅读（主页状态下保留待重进标记，
  等回到该标签再重进）；
- `readerSettledAt` 记录就绪时刻，作为看门狗 3 秒静默窗口。

### 真机验证（Q968 / Android 13 / WebView 101）

| 验证项 | 结果 |
|---|---|
| 进入阅读 | 提取 → `打开阅读页` → `阅读页已就绪`（约 0.5 s） |
| 章接缝 | 追加后 `docHeight 15924→30318`，`scroll 17471→17859` 连续，接缝处是章节标题条 |
| 长跑 400+ 键 / 9 章 | `dom=2~3`、`docHeight` 稳定在 29k~46k、`fails=0`、无渲染进程死亡 |
| 裁剪补偿 | `trim dom=2 scroll=14125 / 14062 / 13288…`，滚动位置只差几十 px，无回跳 |
| 自动追加 | `fetch#n` 预读 + `append … dom=2/3` + 桥事件 `turn` |
| 退出（返回键） | 导航到 `lastUrl` 真实章节页，`reading=false` |
| 标题正确性 | 追加章节标题与站点 `<h1>` 一致（14→16 是站点自身跳号，非丢章） |
| lint | `:app:lintLightningPlusDebug` 通过 |
