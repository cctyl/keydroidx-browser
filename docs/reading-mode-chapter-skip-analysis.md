# 阅读模式跳章问题根因分析与修复

> 状态：根因已定位，**方案 A + B 已实现**（`app/src/main/js/KeyphoneReadingMode.js`），已通过 Lint / assemble / jsdom 真实页面回归。
>
> 关联文档：`reading-mode-render-isolation-plan.md`（M1.5 曾把同类现象误判为「站点自身跳号」，本文档推翻该结论）。
>
> 实现说明与验证结果见文末第 6、7 节。

## 1. 用户报告的现象（起点 m.qidian.com，《趋吉避凶，从天师府开始》bookId=1037806577）

1. 从第 1 章进入阅读模式，内容不是从第 1 章开始的，约跳过一整章。
2. 从第 3 章看起，读到第 4 章后，拼接的下一章直接是第 6 章——第 5 章被跳过。
3. 从第 5 章直接进入阅读模式，首屏直接跳到第 6 章。
4. 从第 8 章进入，跳过第 9 章直接到第 10 章。
5. 退出阅读模式后确认被跳过的章节在站点上正常存在。

共同点：**恰好跳过一章**，且入口章（extract 发生在活体页面上）与拼接章都可能受影响。

## 2. 关键证据

### 2.1 站点 SSR HTML 本身完全正常（排除「站点跳号」）

用移动端 UA curl 了该书多章 SSR HTML，每页正文章节容器为 `<main id="c-{chapterId}">`，**全页有且仅有一个**短文本锚点「下一章」，且 href 严格指向下一章 chapterId：

```
1(763585474)→2(763621766)   3(763630592)→4(763630643)
5(763691857)→6(763691946)   7(763771909)→8(763771959)
8(763771959)→9(763880303)   9→10(763880334)  10→11(763947747)  11→12(763947809)
```

将 `findNextUrl()` 的逻辑原样在 jsdom 里对这些页面模拟，返回的 next 全部正确。即：**只要输入是 SSR HTML，现有算法不会跳章**。

### 2.2 设备日志证明：出错的 next 来自「活体页面」，且该 URL 在 SSR 里根本不存在

`logs/repro-20261020-0929/app-20261020.log`：

```
09:04  enter 763630643(第4章)  next=763691946(第6章)   ← 跳第5章
09:11  enter 763691857(第5章)  next=763691946(第6章)   ← 正确
09:18  enter 763691857(第5章)  next=763771909(第7章)   ← 跳第6章
```

三个决定性事实：

- **同一章（第 5 章）两次进入，检测出的 next 不同**（09:11 正确、09:18 错）。SSR HTML 是静态的，不可能产生这种差异 → 差异只能来自 WebView 里被 JS 运行过的活体 DOM。
- 09:18 检测出的 next=`763771909`（第 7 章），但这个 chapterId **在第 5 章的 SSR HTML 里完全不出现**（全文检索 0 命中）。它是第 6 章页面里的「下一章」目标。
- 一旦进入拼接阶段（`fetchChapter` 走 `fetch()` 拿 SSR + DOMParser），后续 next 全部恢复正确（09:18 日志：append 第 7 章后 next=763771959 第 8 章，正确）。

### 2.3 唯一能把第 7 章的链接放进第 5 章页面的机制：起点的「滑屏自动加载下一章」

m.qidian.com 章节页是 Vue 应用，用户滚动到本章底部时会**把下一章正文自动追加进当前 DOM**（页面 URL 是否同步更新取决于其 history 处理），并伴随 nav 区变化。这解释了全部现象：

| 进入阅读模式前用户在活体页上的状态 | extract 的结果 | 用户看到的现象 |
| --- | --- | --- |
| 章首，未触发自动追加 | 容器=本章，next=下一章（正确） | 正常（对应 09:11 日志） |
| 已滚动到本章底部，下一章被追加进 DOM | `pickContainer` 可能选中**下一章**的容器（本章容器可能被站点折叠/降权）；本章的「下一章」链接被移除或顺位变化，`findNextUrl` 命中的是**追加章**的「下一章」→ 指向 N+2 | 现象 1/3：首屏内容直接是下一章（「从第5章进入直接跳到第6章」「从第1章进入少了一章」） |
| 本章底部已追加下一章，但 `pickContainer` 仍选中本章 | 内容=本章正确，但 next=N+2 | 现象 2/4：读着读着跳一章（第4章→第6章、第8章→第10章） |

## 3. 代码定位（均未改动）

- `app/src/main/js/KeyphoneReadingMode.js`
  - `findNextUrl()`（L537）：先查 `link/a[rel~=next]`，再按**文档顺序**扫描全部 `a[href]`，取第一个文案命中 `NEXT_TEXT_RE` 的锚点。对「活体 DOM 里存在多个章节、各自带下一章链接」的情况没有任何防御——**扫描范围是整个 document，与正文容器无关联**。
  - `pickContainer()`（L373）：按 `scoreCandidate` 打分取最高分容器。活体页面上存在两个章节容器时，选错即「首屏就是下一章」。起点容器带 `<main id="c-{chapterId}">`，**本可用 URL 里的 chapterId 校验**，目前没有。
  - `extractHtml()`（L523）/ `extract()`：在**活体页面**上执行，是整个链条里唯一吃 JS 污染 DOM 的环节。
  - `advanceTo()`（L911）/ `fetchChapter()`（L933）：拼接阶段 fetch SSR + DOMParser，日志证明这段链路是干净的，next 严格来自上一章 SSR，无跳章。
- `app/src/main/java/acr/browser/lightning/keyphone/KeyphoneBrowserActivity.kt`
  - `enterReadingMode()`（L1068）→ `ReadingModeJs.extractScript()`（L5285）：在源页面只装 API 不渲染（`__keyphone_rm_noenter__`），随后 `r.extract()` 直接读活体 DOM —— 污染入口。
  - `pullReadingProgress` / trail / `openReaderPage`（L1149）只负责记账与壳页加载，不参与选章，可排除。

## 4. 修复方案候选（按推荐度排序）

### 方案 A：入口 next 不信活体 DOM，改用 SSR 自检（推荐）

`extract()` 时不再从活体 document 取 next；改为在 `enterWithContent`/拼接启动前，由 reader 对**当前章 URL 自己发一次 `fetch()`**，在 SSR 文档上跑 `findNextUrl()`。与拼接阶段同源同逻辑，天然一致，从根上免疫一切站内 JS 污染（不止起点）。

- 代价：进入阅读模式多一次同域请求（拼接本来也要 fetch 下一章，可复用该次响应里解析出的 next）。
- 兜底：fetch 失败再回落到现有的活体 DOM 检测。

### 方案 B：用 chapterId 锚定正文容器（对起点类站点见效快）

`pickContainer` 打分后，若页面容器带可解析的章节 id（如 `<main id="c-763691857">`）且 URL 里含同一 id，则**强制选中 id 匹配 URL 的容器**，解决「首屏就是下一章」。但这是站点特化启发式，通用性不如 A。

### 方案 C：next 合理性校验

`advanceTo` 记录上一章 next 与本次 fetch 返回 URL 的对应关系，若 entry.next 与「上一章 SSR 的 next」不一致（即活体与 SSR 矛盾），以 SSR 为准并打日志。可作为 A 的辅助防线，同时为以后排查留下可观测性。

## 5. 验证计划（修复后）

1. 起点该书复现路径全走一遍：第 1 章进入、第 3→4→5 连读、第 5 章直接进入、第 8 章进入，确认无跳章。
2. 进入前先把章节页滚到底（触发自动追加）再进阅读模式——这是原 bug 的触发姿势，必须覆盖。
3. 回归 `docs/reading-mode-render-isolation-plan.md` M1.5 的用例，并修正其中「14→16 是站点自身跳号」的误判记录。
4. `.\gradlew.bat :app:lintLightningPlusDebug` + `:app:testLightningPlusDebugUnitTest`。

## 6. 已实现的改动（`app/src/main/js/KeyphoneReadingMode.js`）

### 6.1 方案 A：入口 next 改由「当前章 SSR 自检」决定（核心）

- 新增 `selfCheckEntryNext(url, fallback)` / `resolveEntryNext(ssrNext, fallback)`（L1126 附近）。
- `enterWithContent()` 不再直接 `nextUrl = entry.next`（活体 DOM 检测值），而是：
  - 启动瞬间 `entryNextCheckPending = true`、`nextUrl = null`、`ended = false`；
  - 对**当前章 URL 自己**发一次 `fetch(url, {credentials:'include', cache:'force-cache'})`，用 `DOMParser` 解析后在该 **SSR 文档**上跑 `findNextUrl()`；
  - 以 SSR 结果为准写入 `nextUrl` 并启动 `prefetchNext`；活体值 `entry.next` 仅在 SSR 自检失败（网络/超时/解析失败）时兜底。
- 等待窗口内 `appendNext()` / `prefetchNext()` 返回 `busy` / 直接跳过，保证「不可能用脏 next 拼接」；`pump()` 每 800ms 重试，自检一落地立即续拼，不会死等。
- `teardown()` 重置 `entryNextCheckPending`；`resolveEntryNext()` 在 `!active` 时直接返回，避免退出后旧响应复活状态。

拼接阶段（`fetchChapter`）本来就是 fetch SSR + `findNextUrl`，与自检同源同逻辑，因此**只有入口**需要修。

### 6.2 方案 B：正文容器按 URL 里的 chapterId 锚定

- 新增 `chapterIdFromUrl(url)`（取 URL 路径里最后一个纯数字段，起点是 `/chapter/{bookId}/{chapterId}/`）与 `findContainerByChapterId(doc, chapterId)`（匹配 `id="c-{chapterId}"` / `chapter-{id}` / `content-{id}` 等）。
- `extractHtml()` 改为 `pickSource(doc, baseUrl)`：**优先** id 锚定容器，找不到才回落到原来的打分 `pickContainer()`。
- 安全阀：锚定容器得分不到最高分容器的 50% 时视为「恰好 id 尾号相同的挂件」，仍走打分结果，避免误伤别的站点。
- 该逻辑对入口提取与拼接提取（fetched SSR）同时生效：解决「从第 5 章进入首屏却是第 6 章」这类**内容**跳章。

### 6.3 方案 C：可观测性

- `resolveEntryNext()` 在活体值与 SSR 值不一致时打 `entry next corrected by SSR: live=… ssr=…`；
- `diag()` 增加 `entryCheck=1/0`，宿主日志「阅读 JS 现场」可直接看出自检是否仍在进行。

## 7. 已验证内容

jsdom 加载**真实** `KeyphoneReadingMode.js`，用 curl 下来的**真实起点页面**做回归：

| 用例 | 结果 |
| --- | --- |
| 真实 ch5 SSR 上 `extract()` 的 next | `…/763691946/`（= 第 6 章，正确）|
| 真实 ch8 SSR 上 `extract()` 的 next | `…/763880303/`（= 第 9 章，正确）|
| 活体页内联追加了第 7 章容器时提取 | 正文只有 ch5（`雷俊看着王归元…`），未混入追加章 |
| 模拟起点污染（删掉本章「下一章」、追加章的「下一章」指 ch8）后进入阅读模式 | live next = ch8（跳两章）→ SSR 自检纠正为 **ch6** → 首个拼接请求是 ch6 |
| SSR 自检失败（网络错误）| 回落活体值，阅读不中断 |
| SSR 自检期间按键翻页 | 返回 `busy`，`pump` 每 800ms 重试，自检完成后自动续拼 |

构建门禁：`:app:lintLightningPlusDebug` + `:app:assembleLightningPlusDebug` **BUILD SUCCESSFUL**（Mezzanine 已把新脚本编进 `_MezzanineReader_828439026` 的 class/dex）。

单测 `:app:testLightningPlusDebugUnitTest` 为 58 tests / 12 failed，**在未改动的干净树上同样是 58/12**，失败全在 `preference/datastore`（Windows 下 DataStore `Unable to rename *.tmp` 的既有环境性 flake），与本次改动无关。

## 8. 残留风险

1. **站点完全不带 chapterId 或用非数字 id**：方案 B 不生效，只能靠打分；此时内容错章风险仍在（方案 A 仍能保证不跳章）。
2. **SSR 自检超时（12s）**：超时后回落活体值，极慢网络下仍可能跳章（旧行为）。
3. **入口正文不做 SSR 复核**：首屏仍用活体提取的正文（为了不闪、不重置滚动）；只对 next 做 SSR 复核。若某站点去掉容器 id 又内联追加章节，内容错章仍可能发生。
4. 起点若改为 `history.replaceState` 把 URL 同步成追加章，则 URL 与正文一致，属「与站点一致」的正常表现，不算跳章。
