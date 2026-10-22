# 阅读模式原生 TextView 化方案（第一步：显示层换原生）

> 目标：把阅读模式的**显示**从 WebView 挪到原生 TextView，根治"渲染进程被系统
> `excessive cpu` 杀掉导致空屏"。第一步只动显示层，取章/提取仍走隐藏 WebView 里的 JS
> （同源 fetch），不 port 到 OkHttp+jsoup（留给第二步）。
>
> 背景与上一版方案的证伪见
> [`reading-mode-excessive-cpu-20261022.md`](./reading-mode-excessive-cpu-20261022.md)。
> 本文不改代码，先供审核。

---

## 一、目标与范围

### 目标

- 阅读时用户看到的是**原生 TextView**，不是 WebView。
- WebView 在阅读态下**隐藏**，只当"取章+提取"的工具进程，不参与显示。
- 删掉三个常驻心跳（`probeReaderFrame` 3s 帧回调、`sampleReadingState` 2s 采样、
  JS `pump` 800ms 心跳）——它们的全部职责在新架构下失效或改事件驱动。
- 翻页改为**分页翻页**（一次一页，像主流阅读器），不是滚动。

### 范围（第一步做）

- 显示层：新建 `KeyphoneReaderView`（单 TextView + 分页游标）。
- 取章层：复用主界面 WebView 隐藏后跑精简 JS，正文经 bridge 回传 Kotlin。
- 按键：`1`/`3` 字号、`7` 背景、`左/4`/`右/6`/`上`/`下` 翻页；`2`/`8` 取消。
- 看门狗：三个定时闹钟全删；保留 `onRenderProcessGone` 事件回调重建隐藏 WebView。
- 轨迹/历史/退出：`ReadingSession.trail`/`lastUrl` 仍由 Kotlin 维护，退出导航回 `lastUrl`。

### 范围（第一步不做）

- 取章不 port 到 OkHttp+jsoup（仍 JS `fetch()`，靠隐藏 WebView 的
  `loadDataWithBaseURL(章节URL)` 同源）。
- cookie/UA/字符集桥接不做（WebView 仍帮我们处理）。
- 不动主页、菜单、标签、设置页。

---

## 二、现状回顾（哪些要改 / 哪些不动）

| 组件 | 现状 | 第一步处置 |
|---|---|---|
| `enterReadingMode` | 源页面 `extract()` → `loadDataWithBaseURL(外壳)` 显示 | 提取仍用；显示改投喂 TextView，WebView 隐藏 |
| `buildReaderShell` | 外壳含显示+取章+翻页全套阅读 JS | 外壳只留"取章+提取+回传"部分，删除显示/翻页/trim/字号主题代码 |
| JS `pump`(800ms) | 心跳催续章+trim | **删**，改 Kotlin 翻页事件驱动催章 |
| JS `enterWithContent`/`appendChapter`/`trimChapters`/`injectStyle` | 在 WebView DOM 里渲染正文 | **删**，正文不经 DOM，直接 bridge 回传 HTML |
| `probeReaderFrame`(3s) | 逼渲染进程出帧防合成卡死 | **删**，显示层不是 WebView，无意义 |
| `sampleReadingState`(2s) | 现场采样诊断 | **删**（保留 `onRenderProcessGone` 单点重建） |
| `adjustReadingFont`/`cycleReadingTheme` | `evaluateJavascript` 让 JS 改样式再回传 | 改成直接操作 TextView + 落盘 `KeyphoneReadingPrefs` |
| `handleReadingModeKeys` | `scrollBy` 整屏翻页 | 改成 `KeyphoneReaderView` 上一页/下一页 |
| `pullReadingProgress` | 拉 JS trail 进历史 | 改成从 Kotlin 侧的 `ReadingSession.trail` 直接取（不再问 JS） |
| `ReadingSession` | Kotlin 内存态 trail/lastUrl | **不动**，数据源从 JS 回传改成 bridge 事件 |
| `KeyphoneReadingPrefs` | 字号/主题 SP + 缓存 | **不动**，只改写入触发点（从 JS 回传改成按键直写） |

---

## 三、新架构总览（数据流）

```
进入阅读
  │
  ├─ 主 WebView（可见）跑 extract() → JSON{url,title,html,next}   （不动）
  │
  ▼
Kotlin：ReadingSession 新建，entry.html 入"当前章文本缓冲"
  │
  ├─ 主 WebView 隐藏（visibility=GONE），转为"隐藏取章 WebView"
  │     └─ loadDataWithBaseURL(章节URL, 精简外壳)  ← origin 同源，JS fetch 带 cookie
  │          └─ JS：fetch 下一章 → extractHtml → bridge.onEvent("chapter", JSON)
  │
  ▼
KeyphoneReaderView（可见，单 TextView）：
  ├─ 分页引擎：StaticLayout 把"当前章文本"切成页，维护游标 [pageStart, pageEnd)
  ├─ 显示当前页 [pageStart, pageEnd) 的文字
  └─ 翻页键 → 移动游标；接近页尾 → 催隐藏 WebView 取下一章
```

**核心不变量**：用户看到的永远是 TextView 的当前页。隐藏 WebView 死了/慢了都不影响当前页显示，最多影响"下一页能不能续上"。

---

## 四、显示层设计：`KeyphoneReaderView`

### 4.1 视图结构

在 `keyphone_browser.xml` 的 `keyphone_content_frame` 里新增：

```xml
<acr.browser.lightning.keyphone.KeyphoneReaderView
    android:id="@+id/keyphone_reader"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:visibility="gone" />
```

阅读态：`reader.visibility = VISIBLE`、`browser_frame_host.visibility = GONE`、
`home_scroll.visibility = GONE`。退出阅读反之。

`KeyphoneReaderView` 内部 = 一个 `TextView`（`match_parent`，`gravity=left|top`，
`padding` 与 JS 现有 `10px` 对齐）+ 一个分页游标引擎。**没有 ScrollView、没有 RecyclerView。**

### 4.2 文本来源：章节文本缓冲

Kotlin 侧维护一个**有界的章节文本队列**（`ArrayDeque<ChapterText>`）：

```kotlin
data class ChapterText(
    val url: String,
    val title: String,
    val plain: Spanned   // Html.fromHtml(html) 后的纯文本+基本段落
)
```

- `plain` 用 `Html.fromHtml(html, FROM_HTML_MODE_LEGACY)` 得到，`<p>`/`<br>`/`<b>`/`<i>`
  保留，其余样式丢弃（符合"不需要复杂样式"）。
- 队列只保留**当前章 + 后续预取章**，超过当前章 10 章之前的 `removeFirst` 丢掉
  （用户不回翻，trail 的 url/title 已入 `ReadingSession` 不丢）。
- "全局字符流"= 队列里所有 `plain` 拼接的逻辑流，游标用"章序号 + 章内字符偏移"定位，
  不真拼接成一个大字符串（按需取段）。

### 4.3 分页游标模型（核心）

**游标**：

```kotlin
data class PageCursor(
    val chapterIndex: Int,   // 在章节队列里的序号
    val startOffset: Int,   // 当前页首字在该章 plain 里的字符偏移
    val endOffset: Int      // 当前页尾字偏移（开区间）
)
```

- 当前页显示 = `chapterQueue[chapterIndex].plain.subSequence(startOffset, endOffset)`。
- 翻"下一页"：`startOffset = endOffset`，重新测出新的 `endOffset`（见 4.4）。
  - 若 `startOffset` 到本章末，`chapterIndex++`、`startOffset=0`，先翻一张"标题页"
    （见 4.6），再翻正文第一页。
- 翻"上一页"：以新的 `startOffset` 为目标，**向前**测量上一页的边界（从 0 或更早的偏移
  二分/线性推到"填满一屏"的前一页起点）。回翻只在当前章 + 紧邻上一章范围内有效
  （队列里没有更早的章了就停在最前页）。

### 4.4 分页测量：StaticLayout

```kotlin
fun measurePageEnd(text: CharSequence, start: Int, width: Int, fontSizePx: Int): Int {
    var lo = start
    var hi = text.length
    // 二分：找最大的 end，使 StaticLayout(text, start..end).height <= viewportHeight
    while (lo < hi) { ... }
    return lo
}
```

- 给定 `start`、屏幕宽（减 padding）、字号、行高（1.8，与 JS `line-height` 对齐），
  用 `StaticLayout.Builder` 构造一段 `[start, end)` 的布局，读 `.height`。
- 二分 `end`：最大且 `height <= viewportHeight` 的 `end` 即本页尾字。
- `viewportHeight` = TextView 可见高度（`onSizeChanged` / `post` 后取）。
- 一章几千字，二分约 13 次，每次 StaticLayout 几毫秒 → **单次分页 < 100ms**，
  在后台协程算，主线程只 set 文本。

**预分页**：翻到当前页时，后台协程顺便把"下一页""下下一页"的边界也算好缓存
（一个 `Map<Int, Int>`：start→end），翻页时直接取，零延迟。

### 4.5 字号变化（按键 1/3）的游标规则

按 4.3 不变量：**`startOffset` 不变，只调 `endOffset`**。

- 按 3（增大字号）：当前页装不下原 `[start, end)` → 用新字号重测，`end` 前移
  （吐出末尾几字），吐出的字成为下一页的新 `start`。当前页前部内容稳定。
- 按 1（减小字号）：当前页还有空间 → `end` 后移多吃几字（本章没了就跨章接下一章
  `plain`），当前页已显示的字不动，尾部追加。

**跨章衔接**：缩小到本章末仍不满一屏，按 4.2 把下一章 `plain` 接进当前章文本流
（逻辑拼接，不是物理拼接），`endOffset` 继续后移进入下一章。章序号在游标里记两层
（chapterIndex + 章内 offset），显示时跨章拼接取文本。

字号档位沿用 `FONT_SIZES = [14,16,18,20,22,24,28,32,36,40]`（搬到 Kotlin 常量），
`KeyphoneReadingPrefs.fontSize` 不变。

### 4.6 标题页

每章正文第一页前插一页"标题页"：单 TextView 居中显示 `第N章\n标题`（`N` 取自 trail
计数，`标题` 取 `ChapterText.title`）。标题页是"页"的一种（`PageCursor` 加一个
`isTitlePage` 标记），翻页键能翻到，翻过它就是正文第一页。

首章（进入阅读的第一章）也插标题页，让用户明确"读到第几章了"。

### 4.7 背景与字色（按键 7）

把 JS `THEMES` 表（5 档：米白/护眼黄/护眼绿/夜间灰/夜间纯黑，每档 bg/text/head/link）
搬到 Kotlin 常量。按 7 轮换 `themeIndex`，直接：

```kotlin
textView.setBackgroundColor(THEMES[themeIndex].bg)
textView.setTextColor(THEMES[themeIndex].text)
KeyphoneReadingPrefs.setThemeIndex(context, themeIndex)
```

**不触发重分页**（只换颜色）。标题页字色用 `head`。

### 4.8 翻页动画（可选，先不做）

第一版翻页瞬切（直接 `setText`）。若用户觉得硬，后续加 `ObjectAnimator` 做左右滑入
淡出（300ms）。功能机瞬切可接受，先把正确性做稳。

---

## 五、取章层设计：隐藏 WebView + 精简 JS

### 5.1 隐藏 WebView = 复用主界面 WebView

阅读态：把 `keyphone_browser_frame_host` 里的 WebView 设 `visibility=GONE`，
加一个 `isReaderFetchMode` 标志位隔离普通浏览逻辑。复用而不新建，省一个渲染进程。

退出阅读：`loadDataWithBaseURL` 清掉（`loadUrl("about:blank")`），恢复 `visibility`，
再 `loadUrl(lastUrl)` 导航回最后读的章（退出语义不变）。

### 5.2 精简外壳

`buildReaderShell` 重写，只保留取章+提取+回传，**删除**显示/翻页/trim/字号/主题：

```js
window.__keyphone_rm__ = {
    fetchNext: function(url) { ... fetch(url) → DOMParser → extractHtml →
                               bridge.onEvent('chapter', JSON{url,title,html,next}) },
    cancel: function() { ... }
};
```

- `extractHtml`/`findNextUrl`/`normalizeUrl`/`seen` 等纯提取逻辑**原样保留**
  （port 风险在第二步才承担）。
- `chapterCache`/`prefetchNext` 逻辑搬到 Kotlin（Kotlin 决定何时催下一章），JS 只暴露
  `fetchNext(url)` 一个方法。
- 删除 `pump`、`setInterval`、`enterWithContent`、`appendChapter`、`trimChapters`、
  `injectStyle`、`THEMES`、`FONT_SIZES`、`buildCss`、所有 DOM 渲染代码。

### 5.3 bridge 事件

`KeyphoneReadingBridge.onEvent` 现有通道不动，新增/复用事件类型：

| type | payload | Kotlin 处理 |
|---|---|---|
| `chapter` | `JSON{url,title,html,next}` | 解析 → `ChapterText` 入队列尾部 → 触发预分页 |
| `fail` | `errorMsg` | toast"加载下一章失败，重试中"；保留 `nextUrl` 可重试（沿用现有 FAIL_RETRY 语义） |
| `end` | `""` | 标记无下一章，不再催 |
| `link` | `url` | 正文内点链接（TextView 无点击，此事件不再触发，可删） |

`enter`/`exit` 事件删除（进入/退出由 Kotlin 直接控制 TextView，不经 JS）。

### 5.4 取章时序

1. 进入阅读：主 WebView（仍可见）跑 `extract()` 拿 entry → 入队列第一章 → TextView 分页显示。
2. 紧接 `loadDataWithBaseURL(章节URL, 精简外壳)` 起隐藏 WebView，注入 `__keyphone_rm_boot__`，
   JS 立即预取下一章（不等用户翻页，首发就预取）。
3. 用户翻页接近页尾（剩余页数 ≤ 1）：Kotlin 调 `evaluateJavascript("...fetchNext(nextUrl)")`
   催取；JS 取到回传 `chapter` 事件 → Kotlin 入队列 + 预分页。
4. 队列超过 10 章：`removeFirst`，`ReadingSession.trail` 已记 url/title 不丢历史。

---

## 六、按键映射

### 6.1 新映射（阅读态）

| 键 | 行为 |
|---|---|
| `↓` / `→` / `6` / 确认键 | 下一页 |
| `↑` / `←` / `4` | 上一页 |
| `1` | 减小字号（游标 start 不变，调 end） |
| `3` | 增大字号（游标 start 不变，调 end） |
| `7` | 轮换背景主题 |
| `0` | 循环切标签（不变） |
| `*` | 页面菜单（不变） |
| `#` | 全局菜单（不变） |
| LSK | 选项页（不变） |
| RSK | 返回上一页 / 退出（不变） |
| `2` / `8` | **取消**（原整屏翻页，分页模型下无意义） |

### 6.2 `handleReadingModeKeys` 改写

删掉 `scrollBy` 整套、`pullReadingProgress`（trail 改从 Kotlin 取）、`isRendererLikelyBusy`
判断（不再同步读 WebView 布局）。分支精简为：

- 翻页键 → `readerView.nextPage()` / `prevPage()`
- 字号键 → `readerView.changeFont(±1)` + toast
- 主题键 → `readerView.cycleTheme()` + toast
- 其余放行（软键/返回/`*`/`#`）

### 6.3 规范文档同步

AGENTS.md §4.3 阅读模式按键表要改：`↑/2`、`↓/8` 改为 `↑/←/4`、`↓/→/6`；删除 `2`/`8`
整屏翻页说明；`1`/`3`/`7` 描述从"经 JS"改成"原生 TextView"。`docs/` 相关文档一并改。

---

## 七、看门狗与恢复逻辑处置

### 7.1 全删的

| 组件 | 现位置 | 处置 |
|---|---|---|
| `probeReaderFrame` + `postVisualStateCallback` | `:3961` | **删**，显示层非 WebView，无意义 |
| `installReadingFrameWatchdog` + tick | `:5596` | **删** |
| `sampleReadingState` + `installReadingWatchdog` | `:3681`/`:3662` | **删** |
| `detectBrokenReaderState` | `:3811` | **删**（无 WebView 显示态可判"无 WebView"了） |
| `recoverReaderPage` | `:3852` | **删**（无显示态崩溃可恢复，Kotlin 数据在内存） |
| JS `pump`/`setInterval`/`pumpTimer` | JS `:1359`/`:1560` | **删** |
| `readingFramePending`/`readerFrameRequestedAt` | `:3956` 附近 | **删** |

### 7.2 保留的

- `TabAdapter.onRenderProcessGone`：**保留**（事件回调，0 开销）。隐藏 WebView 的渲染进程
  被杀时，回调里重建：`loadDataWithBaseURL(当前章URL, 外壳)` 重新起取章 JS，重新预取。
  显示层（TextView）完全不受影响，用户无感。**这是唯一保留的"恢复"路径，事件驱动非闹钟。**
- `ReadingSession` 持久化（落盘 entry/lastUrl/trail）：**保留**，Activity 重建后恢复阅读
  仍靠它（重进阅读 = 重新 extract 当前章 + 重新分页，trail 从落盘恢复）。

### 7.3 "卡在正在打开"的根除

原"卡在正在打开"是显示层 WebView 死了、`currentWebView()` 返回 null、`updatePageLoading`
死显示遮罩。新架构显示层是 TextView，不存在"WebView 死导致显示死"。加载遮罩只在
**进入阅读的分页期间**短暂显示（分页 <100ms，几乎无感），分页完即撤。

---

## 八、文件改动清单

### 新增

- `keyphone/KeyphoneReaderView.kt` — 自定义 View（TextView + 分页游标引擎）。
- `keyphone/KeyphoneReaderThemes.kt` — 5 档主题常量（从 JS `THEMES` 搬）。
- `keyphone/KeyphoneReaderPager.kt` — 分页测量 + 游标逻辑（StaticLayout 二分，可独立单测）。
- 单测 `src/test/.../KeyphoneReaderPagerTest.kt` — 分页/字号变化/跨章衔接的纯逻辑测试。

### 改

- `res/layout/keyphone_browser.xml` — 加 `KeyphoneReaderView`。
- `keyphone/KeyphoneBrowserActivity.kt`：
  - `enterReadingMode`/`openReaderPage` — 投喂 TextView，不 `loadDataWithBaseURL` 显示。
  - `buildReaderShell` — 精简成取章外壳。
  - `handleReadingModeKeys` — 改翻页/字号/主题走 TextView。
  - `adjustReadingFont`/`cycleReadingTheme` — 改原生直操作。
  - `exitReadingMode` — trail 从 Kotlin 取，导航回 lastUrl 不变。
  - 删 `probeReaderFrame`/`sampleReadingState`/`detectBrokenReaderState`/`recoverReaderPage`
    及相关常量、Handler、字段。
  - `onReadingBridgeEvent` — 处理 `chapter`/`fail`/`end` 事件。
- `js/KeyphoneReadingMode.js` — 删显示/翻页/trim/字号/主题，留 extract/fetch/bridge。
- `res/values/keyphone_strings.xml` — 翻页提示、章节标题页文案。
- `AGENTS.md` §4.3 + `docs/` 相关 — 按键映射与架构描述同步。

### 不动

- `KeyphoneReadingPrefs.kt`（字号/主题 SP，只改触发点）。
- `KeyphoneReadingBridge.kt`（bridge 通道不变）。
- `browser/tab/`（WebView 创建/池化/`onRenderProcessGone` 不变）。
- 上游 `BrowserPresenter`/`BrowserContract`（阅读模式不经 MVP 事件，本就不动）。

---

## 九、风险与边界

### 9.1 分页测量的主线程开销

StaticLayout 在主线程构造会短暂占 CPU。策略：**首页分页同步做**（用户等着看，<100ms 可接受），
**后续页预分页在后台协程**（`concurrency/CoroutineDispatchers` 的 IO 调度器），
主线程只 `setText`。若后台分页未完成用户已翻到，临时同步补一次 + 短暂"排版中"提示。

### 9.2 跨章衔接的文本边界

缩小字号跨章拼接时，`PageCursor` 需表达"跨章页"（chapterIndex + offset 跨两章）。
实现上：当前页文本 = 当前章 `[start, end)` 不足时，拼接下一章 `[0, k)`。游标记录
`endChapterIndex` + `endOffsetInChapter`（可能跨到下一章）。显示时跨章取文本。
改字号/翻页时游标推算要处理跨章，单测必须覆盖。

### 9.3 章节文本队列的有界性

队列只留当前章 + 后续预取（默认预取 2 章即可），超过当前章 10 章前的丢。`ReadingSession.trail`
存全部已读 url/title（不存正文），历史记录完整。回翻只在队列范围内有效（用户明确不回翻，
超范围回翻停在队列最早章第一页）。

### 9.4 隐藏 WebView 的生命周期

复用主 WebView 隐藏，要确保阅读态期间不被 `TabPager`/标签切换误操作（加 `isReaderFetchMode`
隔离）。切走标签 = 退出阅读（沿用现有 `clearReadingModeOnNavigation`）。Activity 重建：
`ReadingSession` 落盘恢复，重新 extract 当前章 + 重新分页（用户读到哪页靠落盘的
`lastUrl` + 章内偏移，可考虑落盘 `pageStartOffset`，但首版可只恢复到章首，损失一页位置）。

### 9.5 主页红线

本方案只动阅读页宿主与 `keyphone_browser.xml` 内容区，不碰主页纯原生 View 红线。
`KeyphoneReaderView` 是原生 View，字号走 `keydroidx_font_*` token（StaticLayout 的
`textSize` 用 token 解析后的 px 值，不裸 sp）。

### 9.6 不在干净页发任何跨域 fetch

隐藏 WebView 的 origin = 章节 URL（`loadDataWithBaseURL` baseUrl），JS `fetch` 同源，
不引入新跨域面。第二步 port 到 OkHttp 时才需处理 cookie/UA/字符集。

---

## 十、迁移步骤（建议顺序）

1. **`KeyphoneReaderThemes` + `KeyphoneReaderPager` + 单测**：纯逻辑，先写死一批样例文本
   验证分页/字号变化/跨章衔接/标题页。这一步无 UI，可独立验证。
2. **`KeyphoneReaderView`**：自定义 View，接入 pager，先在 Activity 里硬编码一段文字
   跑通翻页/字号/主题。
3. **JS 精简**：删显示/翻页/trim，留 extract/fetch/bridge；`buildReaderShell` 重写。
4. **`enterReadingMode` 接通**：entry.html → TextView 显示；隐藏 WebView 起取章；
   `onReadingBridgeEvent` 处理 `chapter` 事件入队列 + 预分页。
5. **`handleReadingModeKeys` 改写**：翻页/字号/主题全走 TextView。
6. **删看门狗**：`probeReaderFrame`/`sampleReadingState`/`detectBrokenReaderState`/
   `recoverReaderPage` 及 JS `pump`。
7. **退出/轨迹/历史**：`exitReadingMode` trail 从 Kotlin 取；`pullReadingProgress` 删除。
8. **规范与文档同步**：AGENTS.md §4.3、`docs/` 相关。
9. **回归**：长读（连续翻几十页）验证 CPU 归零、空屏不再出现；字号/背景切换不跳页；
   跨章衔接连贯；隐藏 WebView 被杀后自动重建无感。

### 切换门闩

新架构是**显式切换**（显示层从 WebView 换 TextView，没有灰度可言）。第一步上线后，
取章仍依赖隐藏 WebView 的同源 fetch——若某站点 fetch 异常（登录墙/CSR），回退路径是
**退出阅读用普通浏览模式读该章**（用户手动翻页），不阻塞。第二步 port 到 OkHttp+jsoup
后再覆盖更多站点。

---

## 十一、待审核确认点

1. 翻页键映射：`↓/→/6/确认` 下一页、`↑/←/4` 上一页、`2/8` 取消、上下方向键也翻页
   （与左右等价）—— **已确认**。
2. 字号变化规则：`start` 不变调 `end`，缩小跨章补、放大末尾吐到下一页 —— **已确认**。
3. 标题页：每章前插一页居中标题 —— **已确认**。
4. `onRenderProcessGone` 保留（事件回调，重建隐藏 WebView）—— **我决定保留**。
5. 显示层单 TextView + 分页游标（不用 RecyclerView/ScrollView）—— **已确认**。
6. 第一步不 port 取章到 OkHttp+jsoup（仍 JS fetch）—— **范围已定**。

---

## 十二、实施记录（第一步已落地）

实现与本文档的对应关系，以及落地时对方案的**三处调整**（都写在代码注释里，这里汇总）：

### 12.1 新增 / 改动的文件

| 文件 | 说明 |
|---|---|
| `keyphone/KeyphoneReaderThemes.kt`（新） | 5 档主题 + 字号档位 `[14,16,18,20,22,24,28,32,36,40]` + 行高 1.8；外观唯一事实源 |
| `keyphone/KeyphoneReaderPager.kt`（新） | 分页引擎，**零 Android 依赖**（测量策略 `PageMeasurer` 由外部注入），章队列上限 12 |
| `keyphone/KeyphoneReaderView.kt`（新） | 单 TextView + StaticLayout 二分测量；字号 / 背景直接改原生属性 |
| `app/src/test/.../KeyphoneReaderPagerTest.kt`（新） | 13 个纯 JVM 用例：标题页 / 翻页 / 回翻 / 换字号不动游标 / 队列有界 / 空章跳过 |
| `js/KeyphoneReadingMode.js`（重写，66 KB → 约 30 KB） | 只剩 `extract()`（源页面只读提取）+ `fetchNext()`（取章外壳里同步 XHR）；无 `setInterval` / 无渲染 / 无主题 |
| `KeyphoneBrowserActivity.kt` | 新增「阅读模式取章」region（`requestNextChapter` / `handleChapterResult` / `appendChapter` / `rebootReaderFetch`）；删掉 4 个看门狗与整套 JS 拉取；按键改分页 |
| `res/layout/keyphone_browser.xml` | 内容区新增 `KeyphoneReaderView`（`@id/keyphone_reader`，默认 gone） |
| `res/values/keyphone_strings.xml` | 章节标题页文案 `keyphone_reading_chapter_title`、`keyphone_reading_wait_next`，引导文案改新键位 |

### 12.2 调整一：取章用**同步 XHR**，不是 async fetch + 桥

方案 §5.3 假定 `KeyphoneReadingBridge` 的 `chapter` 事件可用。实测本机 WebView 对**已加载完成**的
页面 `addJavascriptInterface` 不生效（旧代码注释里就记着这件事），异步 fetch 没有可靠回程。

落地做法：`fetchNext()` 在取章外壳里用 `XMLHttpRequest(..., async=false)` 同步取章，
**把章节正文直接作为 `evaluateJavascript` 的返回值**交给 Kotlin —— 一次调用完成「取章 + 解析 + 提取」。
阻塞发生在渲染进程的脚本线程，**不在 UI 线程**；外壳是隐藏页面，用户无感。

三条兜底：
- 同步 XHR 被拒（极少见）→ JS 转异步 fetch 并缓存结果，下次 `fetchNext()` 命中缓存；
- 外壳还没 boot（`notready`）→ Kotlin 做**最多 3 次**的延时重试（一次性计时，不是心跳）；
- 外壳丢了（`missing` / 返回 null）→ `rebootReaderFetch` 重建外壳，显示层无感。

### 12.3 调整二：不做跨章拼页（标题页天然隔开）

方案 §4.5/§9.2 的「字号缩小跨章补字」在落地时被去掉：**每章正文前都有一张标题页**，
一页正文不可能跨过标题页接到下一章。于是游标永远只落在一章内，
上一页 / 下一页 / 换字号全部是单章内的一维运算，`PageCursor` 不需要 `endChapterIndex`，
单测也就能穷举边界。代价：每章最后一页可能装不满（本来就要翻标题页，观感一致）。

### 12.4 调整三：测量预算少排一行

TextView 真正排版时的断行策略可能与 StaticLayout 的默认值（`BREAK_STRATEGY_SIMPLE`）有细微差异，
一旦多出一行，页尾那个半行会被裁掉 —— 阅读器最不该出现的就是「字看不到」。
所以测量预算取「视口高度 − 一行」，最坏情况只是底部多一条空白。

### 12.5 看门狗处置（已全删）

`probeReaderFrame` / `installReadingFrameWatchdog` / `sampleReadingState` / `installReadingWatchdog` /
`detectBrokenReaderState` / `recoverBrokenReader` / `recoverReaderPage` / `pullReadingProgress` /
`dumpWebViewThreads` / `ReadingModeJs`（JS 片段对象）及其全部常量、字段、Handler 均已删除；
JS 侧 `pump` / `setInterval` / `trimChapters` / `appendChapter` / `enterWithContent` / `injectStyle` /
`THEMES` / `FONT_SIZES` / `buildCss` 一并删除。

保留的唯一恢复路径：`evaluateJavascript` 返回 `missing` 时重建取章外壳（事件驱动，0 空闲开销）。

### 12.6 多标签处置

`KeyphoneReaderView` 是**每个 Activity 一份**，装不下第二个标签的章节队列，所以
`switchToTab()` 会先 `exitReadingMode()`（导航回该标签最后读到的章节）再切标签 ——
即方案 §9.4 的「切走标签 = 退出阅读」。

### 12.7 正文排版口径（2026-10-22 追加）

真机截图暴露了两个排版问题，都是"字符级"的，因此在**文本层**解决（不动分页算法）：

| 现象 | 原因 | 处理 |
|---|---|---|
| 段落之间空一大截 | `Html.fromHtml` 把 `<p>` 变成 `\n\n`，空行在 1.8 行距下等于**整整一行**的空白；一屏只 6~7 行时每段少显示一行 | `collapseParagraphGaps()`：把「空白+换行+空白」压成一个换行，段落改由**首行缩进两个全角空格**区分（中文书常规排版）；首段若是加粗标题则不缩进 |
| 页面第一行只有孤零零一个「。」 | 硬切字符时页界正好落在句末标点前；另外页首若停在 `\n` 上，会先渲染一条空行 | `skipLeadingNewlines()`：页首跳过段落分隔符（`\n` 只作分隔，不属于任何一页）；`pullOrphanPunctuation()` 避头尾：把页首的收尾标点拉回本页（最多 2 个，且允许吃掉预算里预留的那一行） |

缩进用**字符**（`\u3000\u3000`）而不是 `LeadingMarginSpan`：缩进随字号自动缩放，
且分页测量与 TextView 排版看到的是同一段字符，不会出现"量的时候有缩进、画的时候没有"。

#### 12.7.1 页尾被裁 + 下一页吞字（2026-10-22 二次真机反馈，已修）

现象：一页最下面那行只露出半截，下一页开头与上一页尾接不上（少了一整行正文）。

真机日志对照（22px 字号，视口高 369）：

```
测量 [70,160) 高=299 行=8 tops=[0, 45, 83, 121]      ← StaticLayout 眼里每行 38px
排版核对 实际高=375 行=8 tops=[0, 49, 98, 147]      ← TextView 眼里每行 49px
```

**根因**：`StaticLayout.Builder` 的两个开关与 TextView 不一致，量出来的行高比真实排版小 30%，
于是每页多塞进一行，渲染时被 View 高度裁掉（正文没丢，但用户看不见 = 丢字）：

| 开关 | StaticLayout.Builder 默认 | TextView 默认 | 处理 |
|---|---|---|---|
| `setUseLineSpacingFromFallbacks`（Android P+） | false | **true** | 跟随 `textView.isFallbackLineSpacing` |
| `setBreakStrategy` | SIMPLE | **HIGH_QUALITY** | 跟随 `textView.breakStrategy` |

CJK 字形走回退字体，`fallbackLineSpacing` 一开，行高按**回退字体**的 ascent/descent 算
（22px 字号：38 → 49px/行），这正是之前"每页莫名少一行"的真凶。

修完后测量与实际逐像素相同（`高=326 == 实际高=326`，`高=369 == 369`），
于是**取消了"预算少排一行"的保底**（`usableHeight` 直接用整屏高）—— 之前那一行保底
是在给这个口径差异打补丁，补丁去掉后每页反而能多显示一行。
`阅读页` / `测量` / `排版核对` 三条 debug 日志保留，作为这类"量与画不一致"的长期哨兵。

#### 12.7.2 底部总空着大半行 → 行距按视口标定（2026-10-22 三次反馈）

裁字修好后，用户又反馈"底部多出一条空行"。真机数据：10 行只用了 434px，视口 458px，
**底部恒定空 24~43px**，刚好不够再放一行 —— 观感就是白扔一行。

根因有两层：

1. 行高从来没真正等于设计值 `字号 × 1.8`：倍数是用**主字体**的 `descent-ascent` 反算的，
   而中文正文实际由**回退字体**渲染，它的指标大一圈（20px 字号：23px vs 28px），
   于是实际行高 = 设计值 × 1.2（20px 字号实测 43.4px/行，应为 36px）。
2. 即使行高准了，`视口高 / 行高` 一般除不尽，余量必然留在底部。

处理（`lineMultiplierFor` / `calibrateMultiplier`）：

- 自然行高改用**含回退字体**的探针量（`"中"` 与 `"中\n中"` 两次排版求差，避开 `includePad` 的头尾 padding），
  基准倍数 = `字号 × 1.8 / 该行高`；
- 行数取 `floor(视口高 / 基准行高)`，再用真实排版**标定**倍数，让「行数 × 行高」尽量等于视口高。
  标定只接受「排得进视口」的解（宁可少 1px，多 1px 会导致这一页装不下 N 行、底部反而空出整行），
  结果按「字号 + 视口高」缓存，翻页不再重算。

效果（真机）：20px 字号 10 行 = 362 / 369（余 7px），22px 字号 9 行 = 368 / 369（余 1px），
底部不再出现"空一行"的观感；同时因为行高回到设计值，每页比之前多显示 1~2 行。

#### 12.7.3 页脚状态条：电量 + 时间（2026-10-22 新增）

阅读页右下角常驻一条 `32%  19:06`（`KeyphoneReaderView.statusView`）：

- **位置**：底部内边距里的一条窄带（高 = 内边距 10dp + 额外 4dp = 14dp，正好放下 11sp 数字），
  右对齐、用主题的 `dim` 色，深浅背景都够看；
- **只从可用高里扣 4dp**：页脚条原本整条都占空间，实测会让 22px 字号白丢一行；
  压在底部内边距里之后行数不变（22px 9 行、20px 10 行），正文也绝不会被它盖住
  —— 「浮在字上」的叠加层会遮住最后一行，那正是最不能犯的错；
- **零常驻开销**：时间靠系统 `ACTION_TIME_TICK`（每整分钟一次）刷新，
  在 `onAttachedToWindow` 注册、`onDetachedFromWindow` 注销；电量用
  `BatteryManager.BATTERY_PROPERTY_CAPACITY` 读一次属性（不可用时回退 sticky 广播）。
  没有 Handler 轮询、没有 Service。

### 12.8 回归清单（真机）

1. 长读验证：连续翻几十页 / 读半小时，logcat 无 `excessive cpu` 杀进程，无空屏；
2. 字号 1/3 连按：当前页前部文字不跳，仅页尾变化；
3. 背景 7 连按：不跳页、不闪白；
4. 章末翻页：出现「第 N 章」标题页，然后再是正文；
5. 弱网 / 断网点下一页：提示「正在加载下一章…」，恢复网络后再按一次能续上；
6. 阅读中退出（RSK / * 菜单 6）：回到最后读到的那一章真实页面，历史记录含读过的每一章；
7. 阅读中切标签：先退出阅读，旧标签停在章节页而不是空白外壳。
