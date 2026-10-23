# 阅读模式「取章通道」根治方案（顶层设计）

> 现象与证据见 [`reading-mode-next-chapter-lost-20261022.md`](./reading-mode-next-chapter-lost-20261022.md)。
> 本文不改代码，只做顶层设计与方案选择。
>
> 一句话结论：**阅读模式的「显示」已经独立于 WebView，但「取章」还拴在浏览标签的 WebView 上——这是唯一要修的架构错误。把取章也独立出来即可。**

---

## 一、先回答用户的两个疑问（通俗版）

### Q1：「浏览器有 WebView 不是天经地义吗，它怎么能被杀？」

能。要分清两个东西：

- **App 进程**（`acr.browser.lightning`，前台 Activity 所在进程）——这个一般不会被杀，它在前台。
- **WebView 的渲染进程**（`com.android.webview:sandboxed_process*`）——这是一个**独立的、由系统/Chromium 管理的进程**，跑的是网页排版和脚本。它有自己的一份内存账本和优先级，系统或 Chromium 自己都能把它回收，`didCrash=false` 就是「被外部杀掉」的意思（不是网页自己崩了）。

所以「整机有 2.5GB、应用才 200 多 M」**不能**保证渲染进程不被杀——它不是看整机剩多少，而是看系统那一刻对它这个进程的判断（内存压力、CPU 占用、Chromium 自己的 OOM）。**渲染进程会死，是 Android WebView 生活的常态，不是 bug。** 真正的 bug 是「它一死，阅读模式就取不到下一章」。

### Q2：「就算被杀了，怎么就会重启失败？这没道理啊。」

对，这确实没道理——这是**实现层面的 bug**，不是天经地义。根因在 WebView 的「对象池」设计：

- 浏览器的 WebView 实例是**多个标签共用一个池**（`LimitedObjectPool`）。
- 每次从池里拿一个 WebView 时，会顺带登记一个回调：「以后这个 WebView 被池淘汰时，就把它 freeze/destroy 掉」。
- 渲染进程死后要重建，重建要走池。**重建 A 标签的 WebView 时，池满了就会淘汰最老的一个；如果那个老的家伙恰好是（或牵连到）正在重建的 A 标签，它的「淘汰回调」就会把刚建好的新 WebView 一起销毁**——这就是日志里 `重载后仍无 WebView` 的真身。
- 现有代码加了个「失败重试 3 次」的补丁，但**根上的耦合没解开**，而且「渲染进程被杀」这个场景在开发机上根本没法复现验证（shell 没权限杀 sandboxed 进程），所以补丁到底灵不灵是未知的。

换句话说：**Q2 的答案是——不该重启失败，是当前实现把它做坏了。但与其去修一个坏掉的重启逻辑，不如从根上让"阅读模式不再依赖这个会坏的重启"。**

---

## 二、真正的根因（一句话）

> **阅读模式的「显示层」已经从 WebView 里搬出来（原生 TextView），但「取章层」还留在浏览标签的 WebView 里。**

显示层搬出来是对的，所以正文翻页、字号、背景全都不受渲染进程死活影响（日志实证：渲染进程 20:01 死了，20:01~20:10 阅读正文一直 `next=20~28ms` 正常）。

但取章层没搬。它现在的工作方式是：

1. 进阅读模式时，把**当前浏览标签的那个 WebView**（`currentWebView()`）拿来，`loadDataWithBaseURL(章节URL, 取章外壳)`——把它临时改造成一个「同源、带 Cookie 的取章工具」。
2. 翻到章末要下一章时，在这个 WebView 上 `evaluateJavascript(fetchNext)`，让 JS 用同步 XHR 去取下一章 HTML，回传给原生层。

**问题就在这里**：这个取章工具就是浏览标签自己的 WebView。它死了，取章通道就断了；而它的重建又得走那套坏掉的池逻辑。**显示层独立了，取章层还拴在浏览标签的死活上——这就是架构错误。**

用户说的「有后台页面和前台页面」其实正中要害：阅读模式**本该有一个属于自己的、跟前台浏览标签无关的取章后端**，而不是借用前台标签。

---

## 三、顶层设计原则

> **阅读模式必须是一个自包含的子系统：显示（已原生）和取章（待独立）都不能依赖任何浏览标签的 WebView 的持续存活。**

把"取章"从"浏览标签的 WebView"上解耦。解耦有两条路，二选一或组合：

---

## 四、方案 A（根治 SSR 站点）：原生 HTTP 取章，彻底不碰 WebView

### 4.1 思路

取章这件事，本质就是「带 Cookie + UA，GET 一个章节 URL，拿到 HTML，提出正文 + 下一章链接」。

- **Cookie**：浏览器本来就有，`android.webkit.CookieManager.getInstance().getCookie(url)` 一行就能拿到当前站点的全部 Cookie——**这个代码库里已经在用了**（`DefaultFileDownloader.kt:46` 就是这么干的）。
- **UA**：浏览器本来就有（`DefaultUserAgentProvider`，DEFAULT/MOBILE/DESKTOP/CUSTOM）。
- **HTTP 客户端**：OkHttp 已经在依赖里（`okhttp3:5.5.0`），而且 `DefaultFileDownloader` 已经示范了「OkHttp + CookieManager 取 Cookie」的完整写法，直接抄。
- **HTML 解析**：jsoup 已经在依赖里（`org.jsoup:1.23.2`）。下一章链接、章节正文都能用 jsoup 提。

→ 于是取章变成：`OkHttp 带 Cookie+UA GET 下一章 URL → jsoup 解析 → 喂给现有的 KeyphoneReaderPager`。**全程不碰任何 WebView。**

### 4.2 为什么对本案（uukan.org）特别合适

文档 §二已经实证：「直接抓 HTML：183 → 184 → 185 → 186 的「下一章」链接齐全」——说明这个站的章节链接和正文都在**静态 HTML**里（SSR，服务端渲染好），不依赖 JS 执行。这正是 jsoup 的主场。当前用 JS 同步 XHR 取章，**唯一的原因是为了借用 WebView 的同源 Cookie**——而上面说过，Cookie 可以原生拿到。所以这个站用原生 HTTP 取章，**没有功能损失**。

### 4.3 收益

1. **取章通道再也不会因为渲染进程死而断**——根本没有 WebView 可以死。
2. 重建逻辑那套坏掉的池耦合**直接消失**，不用修、不用碰、不用验证。
3. 取章不再有「外壳 boot」「evaluateJavascript 回调」「同步 XHR」这套异步链路，变成一次同步 HTTP 调用 + jsoup 解析，**逻辑大幅简化、可单测**（OkHttp 可 MockWebServer，jsoup 纯 JVM）。
4. CPU/内存占用更低（一次 HTTP 请求 vs 一个常驻 WebView），从源头降低整机压力。

### 4.4 代价 / 边界

- **只对 SSR 站点成立**。如果某站是 CSR（章节链接/正文靠 JS 跑出来），原生 HTTP 拿到的是空壳，jsoup 提不出正文——对这种站要走方案 B。
- 要处理：字符集（jsoup 自己会按 Content-Type/`<meta charset>` 判，多数情况自动）、gzip（OkHttp 的 BridgeInterceptor 自动解）、跳转（OkHttp 自动跟）、反爬（UA 已带，复杂的如 JS 挑战仍需 WebView）。
- 站点列表去重、`end` 判定逻辑要从 JS 搬到 Kotlin（其实更可控，jsoup 选择器比 DOM 里的 next 解析更稳）。
- **`KeyphoneReadingMode.js` 里的取章提取逻辑要 port 到 jsoup 选择器**——这是主要工作量，但 jsoup 比 DOM API 好写、好测。

### 4.5 站点适配策略

阅读模式的站点提取规则（"正文容器是哪个选择器、下一章链接在哪"）目前写在 JS 里。port 到原生后，这些规则应该变成**按域名注册的 jsoup 选择器配置**（一个 `ReadingSiteRule(url → 选择器集)` 表），新站靠规则文件增补，不靠改 JS。这本身也是阅读模式可维护性的提升。

---

## 五、方案 B（兜底 / CSR 站点）：阅读模式专属的、独立的取章 WebView

### 5.1 思路

保留「用 WebView 取章」这条 JS 路径，但**不再借用 `currentWebView()`（浏览标签的 WebView）**，而是阅读模式自己拥有一个**专属的、单一的、轻量的取章 WebView**：

- 进阅读模式时 new 一个，**不进 `LimitedObjectPool`**（不参与多标签共用、不被淘汰）。
- 它只 `loadDataWithBaseURL` 取章外壳，**不跑任何站点脚本、不渲染正文、不滚动**——纯当「带同源 Cookie 的 JS 运行器」用。
- 退出阅读模式时 destroy。

### 5.2 重建变得极简单

这个专属 WebView 的渲染进程要是被杀了，重建逻辑变成一条直线：

```
取章 → 发现 WebView 死了 → new 一个新的 → loadDataWithBaseURL 外壳 → ready
```

**没有池、没有 `awaitRelease`、没有跨标签 freeze、没有「重载后仍无 WebView」**。因为它从来不在池里，没有任何别的标签的回调会来销毁它。重建 100% 可控、可单测、可在开发机上复现验证（直接 `webView.destroy()` 模拟死）。

### 5.3 为什么它更不容易被杀

- 不跑站点脚本 → 没有 DOM 增长、没有 reflow 死循环、没有定时器。
- 不渲染正文（正文在 TextView）→ 没有合成帧压力。
- 体积恒定 → 内存画像稳定。

所以它被系统按 `excessive cpu` 杀的概率远低于一个跑着站点脚本的浏览标签。即便被按内存压力杀，5.2 的干净重建也能在下一次取章时无感恢复，**用户不需要退出重进阅读模式**。

### 5.4 代价

- 多一个 WebView 实例（内存增量很小，因为它只载外壳、不渲染）。
- CSR 站点的 JS 提取规则仍要维护（但和方案 A 共用一套站点规则更好：A 用选择器直提，B 用选择器 + JS 兜底）。

### 5.5 方案 B 自身的问题，解决了没有？解决了

逐条对照：

| B 的问题 | 解没解 | 依据 |
|---|---|---|
| 渲染进程被杀后**重建失败**（旧 bug 的真身） | **解了** | B 的 WebView **不进 `LimitedObjectPool`**，从来没有 `awaitRelease` 回调，没有跨标签 freeze，没有"重载后仍无 WebView"。重建是一条直线（见 5.6）。 |
| B 仍可能被系统杀（内存压力等） | 不再是问题 | 杀了就杀了，5.6 的重建在下一次取章时自动恢复；用户最多感到这次取章慢 1~2 秒，绝不会变成"全书读完"。 |
| 能不能在开发机验证 | **能** | 直接 `fetchWebView.destroy()` 模拟"渲染进程死"，下一次取章必须自动恢复。不再像旧池子路径那样靠真机运气。 |
| Cookie 连续性 | 没问题 | `android.webkit.CookieManager` 是全局存储，所有 WebView 共享；`new WebView()` 后 `loadDataWithBaseURL` 用的就是用户浏览时积累的那套 Cookie，和旧设计同源同 Cookie。 |
| B 的生命周期边界 | 见 5.7 | 阅读进/出时创建/销毁；没取过章就根本不创建（省一个进程）。 |

### 5.6 重建路径（方案 B 的核心，一条直线）

```
取章 → 发现 fetchWebView == null 或 dead
  → new WebView(context)            // 不进池，没有 awaitRelease 登记
  → setDataDirectorySuffix / setup  // 与主 WebView 隔离，避免 data path 冲突
  → loadDataWithBaseURL(chapterUrl, 取章外壳, ...)  // 同源，Cookie 自动带上
  → onPageFinished → ready，发 evaluateJavascript 取章
```

全程没有 `LimitedObjectPool.acquire`、没有 `removeLast().release()`、没有别的标签的 `awaitRelease` 回调被触发——**所以"刚建好的新实例被旧回调销毁"这件事在 B 里物理上不可能发生**。这就是 B 比旧路径根本性的改进：不是"把重建 bug 修好"，是"让这个 bug 没有发生的前提"。

### 5.7 B 的生命周期

- **懒创建**：第一次真正要取章时才 new，用户只读一章不取下一章就永远不创建（省一个 WebView 进程）。
- **销毁时机**：退出阅读模式时 destroy + 置 null；切标签离开阅读会话时同样销毁。
- **取章在途时渲染进程死**：那次取章回调收不到 → 走现有的一次性超时兜底 → 下一次用户按键翻页时 `requestNextChapter` 再来一次，触发 5.6 的懒重建。**无需任何定时器、无心跳**（红线）。
- **`onRenderProcessGone` 处理**：只做两件事——`dead = true` + `destroy()` + 置 null。**不立即重建**（避免空转一个 WebView），等下一次取章按需重建。

### 5.8 B 与 A 共用站点规则

B 用的 JS 提取规则和 A 用的 jsoup 选择器，**应是同一张站点规则表的两面**：
- 规则表 `ReadingSiteRule`：`domain` + `contentSelector`（jsoup） + `nextLinkSelector`（jsoup） + `transport`（`SSR_OK` / `JS_REQUIRED` / `AUTO`）。
- A 路径：用 `contentSelector` / `nextLinkSelector` 直提。
- B 路径（CSR / 反爬兜底）：JS 仍可读同一组选择器定位正文容器，但内容取自 JS 执行后的 DOM。

这样加一个新 SSR 站点 = 加一条规则；加一个 CSR 站点 = 加一条规则 + 标 `transport=JS_REQUIRED`。单一事实源，不再 JS 一套、Kotlin 一套。

---

## 六、推荐：A 为主、B 为辅的双层结构

### 6.1 切换的真正设计（之前只画了原则分支，这里钉死）

切换不是"有规则就 A、没规则就 B"这么一刀切——因为一个登记了规则的站也可能是 CSR（规则里的选择器在静态 HTML 里取不到内容），而且"A 失败"和"网络偶发错误"必须分开，否则会误降级。真正的决策：

```
fetchNextChapter(url):
  rule = ReadingSiteRules.forUrl(url)        // 按域名查规则
  verdict = domainVerdictCache[rule?.domain]  // 该域名上次的 SSR/JS 判定缓存

  # ① 无规则：A 没有选择器可用，直接 B（用 JS 的通用启发式提取）
  if rule == null: → B

  # ② 规则显式声明 CSR：直接 B，不浪费一次 A 的 HTTP
  if rule.transport == JS_REQUIRED: → B

  # ③ 缓存已判定该域为 CSR：直接 B
  if verdict == JS: → B

  # ④ 其它情况（SSR_OK / AUTO / 缓存说 SSR）：走 A
  → A
    if A 成功（正文够长，且非末章时 next 存在）:
        回填缓存 verdict = SSR
    else if A 提空（正文 < 阈值 且非末章却没 next）:
        回填缓存 verdict = JS     # 后续该域直接走 B，不再每次都试 A
        → B（本次也降级 B）
    else if A 是网络/HTTP 错误:
        不回填缓存（不把偶发错误当 CSR），按取章失败重试逻辑处理
```

### 6.2 "A 提空"的判定标准（避免误降级到 B）

只判"HTTP 成功但内容不对"，不判网络错误：

- HTTP 200 + 抓到了 HTML + jsoup 提出来的**正文长度 < 阈值**（如 50 字）且 rule 声明非末章 → 判空（多半是 CSR 空壳）。
- HTTP 200 + 正文不空但 **next 链接缺失**且 rule 声明该站应有 next → 判空（链接靠 JS 跑出来）。
- HTTP 4xx/5xx / 超时 / 连接失败 → **不判空**，归入取章失败重试，不污染域名缓存。

阈值与"非末章"判断都来自规则表，可调。

### 6.3 域名判定缓存

- `domainVerdictCache`：`域名 → SSR | JS`，内存级即可（重启重判一次代价很低，且规则更新后自动失效）。
- 被 `transport == JS_REQUIRED` 直接覆盖（规则作者的话最权威）。
- 提供"重新检测"入口（用户手动清缓存），应对站点改版（SSR→CSR）。

### 6.4 决策示意图

```
翻到章末，催取下一章
   │
   ▼
 查规则表（按域名）
   │
   ├─ 无规则 ───────────────────────► 方案 B（JS 通用启发式）
   ├─ transport=JS_REQUIRED ────────► 方案 B
   ├─ 缓存判定=JS ─────────────────► 方案 B
   └─ 其它（SSR_OK / AUTO / 缓存=SSR）► 方案 A
                                         │
                                    A 成功？─ 否（提空）─► 回填 JS 缓存 + 本次降级 B
                                         │
                                         是
                                         │
                                         ▼
                                   喂给 KeyphoneReaderPager（不变）
```

### 6.5 关键不变量

- **A 失败永远不表现为"全书读完"**：要么降级 B（B 能取就取到），要么走现有"取章失败可重试"路径（用户再按一次键重试），绝不会把 `session.nextUrl` 置空。缺陷 C（一次 `end` 误判就终结会话）在原生侧用"第一次 end 不采信、重认一次"根治，和取章通道解耦一起做。

- **方案 A 是主路径**：覆盖本案（uukan.org）和绝大多数 SSR 站点。本案（"读到一半说没有下一章"）由 A 彻底根治——根本不再有 WebView 可以死。
- **方案 B 是兜底**：覆盖需要 JS 才能取章的站点，以及 A 被反爬挡住（JS 挑战）的情况。它的重建干净，所以即便被杀也只表现为"这次取章慢一点/重试一次"，绝不表现为"全书读完"。

**两者都遵守同一个顶层原则**：取章通道与浏览标签的 WebView **完全解耦**。浏览标签的死活、切换、池淘汰，都不再影响阅读模式。

---

## 七、为什么不选「修池子的重建 bug」这条路

文档 [`reading-mode-next-chapter-lost-20261022.md`](./reading-mode-next-chapter-lost-20261022.md) §六 列了三条，其中「方案1：修对象池释放回调」。这条路我们不选为主，原因：

1. **它治标不治本**：修好了重建，取章通道仍拴在浏览标签上——浏览标签切走、被用户关掉、被系统回收，取章照样断。
2. **它不可验证**：渲染进程被杀的场景在开发机上无法复现，"修好了"只是纸面推断，下次真机崩了才知道。
3. **池子的跨标签 `awaitRelease` 设计本身就是脆弱的**——它是为"多标签共用有限 WebView 实例"设计的，拿来当"阅读模式的稳定取章后端"是用错了工具。

用户要"从根源上"解决，根源就是"取章不该依赖浏览标签"。修池子是在根源之外打补丁。

> 注：池子本身的健壮性（浏览标签自己的渲染进程死后重建）是另一个独立议题，可以另案处理；它不影响阅读模式。阅读模式的修复不该被它阻塞。

---

## 八、与现有架构的衔接（落地形状，先不写代码）

| 现有部件 | 改动 |
|---|---|
| `KeyphoneReaderPager` / `KeyphoneReaderView` | **不变**。它们已经是纯显示层，接受 `ChapterText` 即可。 |
| `KeyphoneReadingMode.js` | **取章提取规则 port 成 jsoup 选择器配置**（方案 A）；CSR 兜底逻辑保留（方案 B）。 |
| `KeyphoneBrowserActivity` 取章链路 | `requestNextChapter` 改走新通道：有规则→方案 A（OkHttp），无规则→方案 B（专属 WebView）。**不再调 `currentWebView()` 当取章工具**。 |
| `TabAdapter` / `LimitedObjectPool` | **不动**。阅读模式与它们解耦后，浏览标签的重建 bug 与阅读模式无关。 |
| 新增 `ReadingSiteRules` | 站点→jsoup 选择器表（按域名），uukan.org 首条规则。 |
| 新增 `ReadingChapterFetcher`（OkHttp 实现） | 抄 `DefaultFileDownloader` 的 Cookie+UA 套路；返回 `ChapterText(url, title, plain, nextUrl)`。 |
| 新增 `KeyphoneFetchWebView`（方案 B 专属） | 不进池，阅读模式进/出时创建/销毁，重建路径独立。 |

### Cookie 一致性

`CookieManager.getInstance()` 是 WebView 的全局 Cookie 存储，OkHttp 用 `getCookie(url)` 取出来的就是用户浏览时积累的那套——**和当前 JS XHR 取章用的是同一份 Cookie**，不会有登录态丢失。

### 去重 / `end` 判定

JS 里「next 指向已访问章就当结束」的兜底，port 到 Kotlin 后由 `ReadingSession.recorded` 集合判断，逻辑不变但更可控（可在单测里覆盖各种 next 成环场景）。`end` 误判的缺陷 C 也由此根治：Kotlin 侧可以轻易做「第一次 end 不采信，重认一次」，不像 JS 那样难调试。

---

## 九、验证标准

1. **本案不复现**：在 uukan.org 上长读，模拟/等待渲染进程被杀（或直接退出阅读再进、切换标签），取下一章始终成功。
2. **可复现验证**：方案 B 的专属 WebView 重建，在开发机上 `destroy()` 后下一次取章必须自动恢复（开发机可直接验证，不再依赖真机运气）。
3. **单测**：`ReadingChapterFetcher` 用 MockWebServer 覆盖 Cookie 注入、字符集、跳转、下一章提取；`ReadingSiteRules` 覆盖 uukan.org 正文/链接选择器。
4. **回归**：旧的 excessive-cpu / 空屏 / ANR 三条不复发（它们已由显示层原生 + 心跳删除 + 分页测量收敛解决，本方案不动那部分）。

---

## 十、实施顺序建议

1. **先做方案 A**：针对 uukan.org 落地 `ReadingChapterFetcher`（OkHttp+jsoup+Cookie+UA）+ 站点规则表，把 `requestNextChapter` 的 SSR 分支切过去。**这一步就能让本案"没有下一章"消失**，因为 uukan.org 不再走 WebView 取章。
2. **再做方案 B**：专属取章 WebView，作为 CSR/反爬兜底。此时浏览标签已与阅读模式无关，B 的重建干净可验证。
3. **池子重建 bug**（`TabAdapter`/`LimitedObjectPool`）另案处理，不阻塞阅读模式。

第 1 步上线即可解锁用户当前的"读到一半断章"阻塞；第 2 步补齐 CSR 兜底；第 3 步是浏览标签自身的健壮性，与阅读模式解耦。
