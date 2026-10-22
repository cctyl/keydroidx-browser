# 阅读模式「空屏」复发分析（2026-10-22）

> 复现日志：`logs/repro-20261022/`（`20261022.log` 为应用日志，`logcat-main.txt` / `logcat-system.txt`
> 为系统日志）。本文不改代码，只做根因分析。前置材料：
> [`reading-mode-render-isolation-plan.md`](./reading-mode-render-isolation-plan.md)、
> [`reading-mode-blackscreen-diagnostics.md`](./reading-mode-blackscreen-diagnostics.md)。

---

## 一、用户现象与本次复现的实际机制

### 现象

全屏阅读看了很久（~71 分钟、读了 15 章），突然文字消失只剩背景色，紧接着自动退出阅读模式、
加载原网页。

### 实际机制（与上一次复现不同）

本次**不是合成管线停死**，而是渲染进程被系统**直接杀掉**：

```
10-22 14:32:27.695 I/ActivityManager: Killing 28067:com.android.webview:sandboxed_process0:...
  (adj 900): excessive cpu 6020 during 300048 dur=4271072 limit=2
10-22 14:32:27.777 W/TabAdapter: 渲染进程死亡 tab=1 didCrash=false url=...
```

- 渲染进程 `pid=28067` 从 13:21:14 起活到 14:32:27，共 **71 分钟**，被 AM 以
  `excessive cpu` 杀掉（`didCrash=false` = 系统主动杀，非 native crash）。
- `6020 during 300048`：在最后 5 分钟窗口内，渲染进程消耗约 **60.2 秒 CPU
  ≈ 20% 持续占用**；`dur=4271072` 即该进程活了 71 分钟。
- 杀进程瞬间 WebView surface 失效 → 用户看到空屏（背景色）。
- 1 秒后看门狗 `detectBrokenReaderState` 命中「阅读态但无 WebView」→ 自动恢复（导航到最后读
  的那一章真实 URL → 重进阅读模式）。**本次没有卡在「正在打开」**，恢复链路工作了；但用户
  仍看到一次「空屏 → 原网页闪现 → 重进」的中断。

对照第一次复现（`logs/repro-20261020-0929/`）：

| | 2026-10-20（迁移前） | 2026-10-22（迁移后） |
|---|---|---|
| 宿主 | 源页面（m.qidian.com）寄生 | `loadDataWithBaseURL(章节URL, 外壳)` 干净页 |
| 站点脚本 | 在跑（死循环 reflow） | **不跑**（外壳只有阅读 JS） |
| DOM | 随拼章无限增长（4.7 万 px） | trim 到 `MIN_CHAPTERS=2`，但单档仍有 9.3 万 px |
| 渲染进程存活 | 16 分钟 | **71 分钟** |
| CPU | 9100 / 300s ≈ 30% | 6020 / 300s ≈ 20% |
| 杀因 | excessive cpu（站点脚本 reflow 风暴） | excessive cpu（**阅读模式自身的心跳**） |
| 用户看到 | 空屏 + 卡「正在打开」 | 空屏 + 自动恢复（重进阅读） |

**结论：迁移有效——把「16 分钟被杀」推迟到「71 分钟被杀」，并修好了恢复链路。但没根除
CPU 来源，只是把锅从「站点脚本」挪到了「阅读模式自己的常驻心跳」上。**

---

## 二、为什么干净页仍然烧 CPU：阅读模式自己的三套常驻心跳

干净页外壳（`buildReaderShell`）只注入阅读 JS，无站点脚本。但阅读态期间，以下三套机制
**全程不停**（应用前台 + 屏亮 71 分钟）：

### 1. JS `pump` 心跳：每 800ms 强制一次 layout（主因）

`KeyphoneReadingMode.js:1359` `pump()` 由 `setInterval(pump, 800)`（:1560）驱动，阅读态下
每 800ms 跑一次，内部：

- `docHeight()`（:921）读 `scrollHeight` → **强制同步 layout**；
- `scrollTop()` / `viewportPx()` → 同步布局读；
- `trimChapters()`（:947）再读 `docHeight()`，超 `MAX_DOM_PX=40000` 时还读
  `getBoundingClientRect()`（更多 layout）；
- `prefetchNext()`（:1120）必要时触发 `fetchChapter`（页内 JS fetch）。

关键：`trimChapters` 的下限是 `pages.length > MIN_CHAPTERS(2)`。日志里全程 `dom=2`，但
`docHeight` 仍达 **93051 px**（`14:32:22` 现场）——两章拼起来 9.3 万 px 高，每 800ms
被强制 reflow 一次。**reflow 成本随文档高度增长**，9.3 万 px 的文档每秒被 layout 1.25 次，
就是持续 CPU 的主来源。

> 71 分钟 × 1.25 次/秒 ≈ 5300 次强制 layout，全部砸在一个 9.3 万 px 高的文档上。

### 2. 帧心跳 `probeReaderFrame`：每 3s 逼渲染进程合成一帧

`KeyphoneBrowserActivity.kt:3961` `probeReaderFrame()` → `webView.postVisualStateCallback(...)`
每 3 秒（`READER_WATCHDOG_INTERVAL_MS=3000`）请求一次新帧。页面静止时本不需要新帧，这个
回调逼渲染进程走一次合成管线（tile/composite），对 9.3 万 px 的文档不是免费的。71 分钟
≈ 1420 次强制合成。

### 3. 现场采样 `sampleReadingState`：每 2s 读 WebView 状态（含跨进程 IPC）

`KeyphoneBrowserActivity.kt:3681` 每 2 秒（`READING_WATCH_INTERVAL_MS=2000`）采样，读
`wv.progress / url / scrollY / contentHeight / canGoBack`——这些大多是到渲染进程的
binder IPC，每次都唤醒渲染进程的主线程。

### 翻页时叠加的瞬时工作

本会话共 **1008 次** page-down（`阅读模式翻页 keyCode=15`），平均 ~14 次/分钟。每次翻页
（`KeyphoneBrowserActivity.kt:2015`）触发 `evaluateJavascript(READING_JS_APPEND)` + 当
`repeatCount==0` 时 `pullReadingProgress`（又一次 `evaluateJavascript(progress diag)`）；
接近页尾时还触发 JS `fetchChapter`（页内 fetch 同源章节 + DOMParser 解析 + append + trim）。

### 合成效应

三套心跳（800ms + 3s + 2s）在阅读态**永不熄火**，叠加翻页的瞬时工作，渲染进程永远拿不到
真正空闲。最后 5 分钟用户正在连续翻页（14:32:22、23、25 各一次），CPU 抬到 ~20% 持续，
达到本机 `excessive cpu` 阈值 → 被杀。

> 设备的杀进程阈值很低：~20% 持续 5 分钟就触发。阅读模式必须把**常驻** CPU 压到远低于此。

---

## 三、对原方案的证伪与补充

### 3.1 原方案的核心假设只对了一半

`reading-mode-render-isolation-plan.md` 的根因判断是「**站点后台脚本在越拼越大的 DOM 上
循环 reflow**」。本次复现证伪了其中两点：

1. **「越拼越大的 DOM」**——迁移后 `trimChapters` 已把 DOM 限制在 2 章（`dom=2` 全程），
   DOM 不再无限增长。但**单档 2 章仍有 9.3 万 px 高**，照样贵。
2. **「站点后台脚本」**——干净页外壳确实没有站点脚本了，CPU 风暴消失（16→71 分钟可证）。
   但**阅读 JS 自己的 `pump` 心跳**成了新的 reflow 来源，原方案没识别到。

原方案在「第四节·迁移步骤 5」把 `KEEP_PAGES` 当成「搭配项（可降为后续优化）」，第一轮评审
（P1-A）也只说「调小数值」。**实测证明这是 P0**：DOM 体量（高度）直接决定 `pump` 每次
强制 layout 的成本，是 71 分钟被杀的主因。

### 3.2 实际实现与方案的偏差（记录，非问题）

- 方案推荐「方案 A：OkHttp + jsoup native 取章」。**实际未实现**：取章仍在阅读页 JS 内
  （`fetchChapter` 走 `fetch()`），靠 `loadDataWithBaseURL(章节URL, ...)` 让干净页继承
  章节 origin 实现**同源 fetch**，绕过 CORS。这是个聪明的简化，但与方案描述不符——
  记一笔，别让后续人按方案文字去找 native 取章服务。
- `chapterCache`（`{prev,current,next}`）+ `prefetchNext` 已在 JS 里实现，日志 `cached=2`
  即此。取章预读本身没问题，但 `prefetchNext` 挂在 `pump` 心跳里，等于每 800ms 都有机会
  触发一次 fetch/parse，放大了心跳的副作用。

### 3.3 恢复链路（原方案 P0-2）已生效

`14:32:28` 看门狗命中「阅读态但无 WebView」→ 导航到 `lastUrl`（最后读的章节真实 URL）→
`14:32:30.521` 自动重进阅读模式。**本次没卡「正在打开」**，原方案第二轮评审 P0-2 要求的
「native 持有正文缓存 + 阅读态死亡恢复语义」由 `ReadingSession.lastUrl` + 自动重进链路
承担了。用户感知到的「退出阅读模式重新加载原网页」就是这次恢复的中间帧，属于预期行为，
不是 bug。

> 唯一的毛刺：`14:32:39.846 重载后未出帧（画面可能是空白） tab=1 .../P1mRFTbQrl...
> attached=false`——原 tab（tab=1）的尸体没出帧，但选中的已是 tab=2，不影响恢复。可忽略。

---

## 四、根因定位（一句话）

> **阅读模式自身的常驻心跳（800ms JS `pump` 强制 layout + 3s 帧合成 + 2s 状态采样）在
> 9.3 万 px 高的拼接文档上持续跑 71 分钟，渲染进程永远不空闲，累计 CPU 触发系统
> `excessive cpu` 杀进程。干净页迁移消除了站点脚本这个 CPU 源，但没消除阅读模式自身的
> CPU 足迹。**

---

## 五、待评审的修复方向（不写代码，仅列选项）

按「治本程度 / 改动量」排序，供讨论：

1. **熄掉 `pump` 的空转**（治本，改动小）：阅读态静止时（无翻页、无 append 在途），
   `pump` 不应每 800ms 强制 layout。可改为：翻页 / scroll 事件驱动 + 退避（例如静止 2 秒
   后停泵，有 scroll/keydown 再唤醒）。`trimChapters` 的 `docHeight()` 读取也应节流（用
   脏标记，只在 append/trim 后重测一次）。**这一条预期能把常驻 CPU 从 ~20% 压到个位数。**
2. **降低单档 DOM 高度**（治本，配合 1）：`MIN_CHAPTERS=2` + `MAX_DOM_PX=40000` 的组合
   在大字号 / 小屏下会让两章拼到 9.3 万 px。把 `MAX_DOM_PX` 按视口倍数动态算（如
   `viewport × 30`），让 trim 更激进；或把 `MIN_CHAPTERS` 降为 1。文档越矮，每次强制
   layout 越便宜。
3. **放宽帧心跳**（治标，改动小）：`probeReaderFrame` 的 3s 周期对「合成卡死」型空屏有用
   （第一次复现的场景），但对本次「进程被杀」型空屏无预防作用——进程被杀前合成是正常的。
   可拉长到 10s+，或仅在「翻页后 N 秒未出新帧」时才探。`postVisualStateCallback` 本身逼
   合成，是 CPU 贡献者之一。
4. **现场采样降频 / 去掉 IPC**（治标，改动小）：`sampleReadingState` 的 2s 周期 + 读
   `canGoBack/contentHeight` 等 IPC，可降到 5s，并避免在静止期读跨进程字段。
5. **`prefetchNext` 移出 `pump`**（治本，改动中）：把预读触发从「每 800ms 检查」改成
   「翻页到页尾 1.5 屏时一次性触发 + 取完后停」，避免心跳里夹带 fetch/parse。
6. **真正的渲染隔离**（治本，改动大）：方案 A 的 native OkHttp+jsoup 取章 + 干净页只排版
   不 fetch。把 fetch/DOMParser/extract 全挪出渲染进程，渲染进程只持有当前可见的 1~2 章
   纯文本。这是原方案的终态，但本次证明**不做这一步也能靠 1+2+5 解决 CPU 问题**，可降级为
   长期优化。

### 优先级建议

- **立即做 1 + 2**：直接砍常驻 reflow，预期就能让 71 分钟被杀消失（或推迟到远超人类单次
  阅读时长）。这两条改动都在 `KeyphoneReadingMode.js` 内，风险可控。
- **3 + 4 跟随做**：降低心跳密度，属于无副作用的减负。
- **5 跟随做**：让预读不再寄生在心跳上。
- **6 暂缓**：1+2+5 生效后评估是否还需要。

---

## 六、给后续排查的锚点

- 关键日志锚点（`logs/repro-20261022/20261022.log`）：
  - `14:32:22.372` 阅读 JS 现场：`chapters=15 dom=2 cached=2 docHeight=93051`——DOM 受控
    但高度仍大。
  - `14:32:27.777` 渲染进程死亡 `didCrash=false`——系统杀，非 crash。
  - `14:32:28.698` 触发自动恢复 reason=阅读态但无 WebView——恢复链路入口。
  - `14:32:30.521` 自动重进阅读模式——恢复完成。
- 系统日志锚点（`logcat-system.txt:6436`）：`excessive cpu 6020 during 300048 dur=4271072 limit=2`。
- 代码锚点：
  - `KeyphoneReadingMode.js:1359` `pump` / `:1560` `setInterval(pump, 800)`。
  - `KeyphoneReadingMode.js:947` `trimChapters`（`docHeight()` + `getBoundingClientRect`）。
  - `KeyphoneReadingMode.js:61` `MAX_DOM_PX=40000` / `:62` `MIN_CHAPTERS=2`。
  - `KeyphoneBrowserActivity.kt:3681` `sampleReadingState` / `:3961` `probeReaderFrame`。
  - `KeyphoneBrowserActivity.kt:1280` `openReaderPage`（`loadDataWithBaseURL` 干净页） /
    `:1322` `buildReaderShell`（外壳无站点脚本）。
