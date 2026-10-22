# 阅读模式 ANR 修复方案

> 根因诊断见 [`reading-mode-excessive-cpu-20261022.md`](./reading-mode-excessive-cpu-20261022.md)
> 与本仓库 `logs/crash-20261022-172812/logcat-all.txt`。
> **P0 / P1 已落地，实施记录与对草案的调整见 §八**（P2 按 §七.4 拆出）。

---

## 一、故障定性

原生 TextView 化（第一步）上线后，阅读模式出现**新闪退**：

- **不是** 旧的 `excessive cpu`（渲染进程被系统杀）——那条已被显示层换原生 TextView 根除。
- **是** `ANR`：主线程被分页测量堵死 5 秒以上，系统判定无响应，强杀进程。

```
17:26:20.409  按键 key=11（数字 4 = 上一页，readerPageUp）
17:26:21.636  主线程持续非空闲 1003ms
              android.text.StaticLayout.<init> → StaticLayout$Builder.build
              → KeyphoneReaderView$StaticLayoutMeasurer.heightOf(:305)
              → pageEnd(:271) → KeyphoneReaderPager.pageStarts(:299)
              → previousPageStart(:316) → prev(:239)
              → readerPageUp → handleReadingModeKeys
17:26:22.646  主线程持续非空闲 2012ms，栈顶 LineBreaker.nComputeLineBreaks
17:26:25.748  主线程持续非空闲 5115ms，栈变成 HardwareRenderer.nSyncAndDrawFrame
17:26:26.255  主线程连续忙 5622ms 后恢复
17:26:26.284  ANR: Input dispatching timed out (Waited 5001ms for KeyEvent)
17:26:26.285  Killing 29145:acr.browser.lightning ... bg anr
```

**触发门槛极低**：进入阅读 → 看一两页 → 按一下"上一页"，就崩。复现时间从旧的 71 分钟降到**秒级**。

---

## 二、根因（三层）

### 第 1 层（元凶）：`prev()` 用 `pageStarts()` 把整章从头量一遍

`KeyphoneReaderPager.prev()`（`:239`）回翻时调用 `previousPageStart()`（`:316`），后者依赖
`pageStarts()`（`:299`）：

```kotlin
private fun pageStarts(chapterIndex: Int): IntArray {
    ...
    var pos = 0
    while (pos < length) {              // ① 整章循环：近万字 ≈ 几十页
        starts.add(pos)
        val end = pageEnd(chapter, pos) // ② 每次 pageEnd 内部又二分 ≈ 12 次 StaticLayout
        if (end <= pos) break
        pos = end
    }
    ...
}
```

**算一笔账**（崩溃现场第 177 章 `plainLen=9908`，240×320 屏 + 16px 字号，一页约 300 字）：

| 量 | 值 |
|---|---|
| 章长 | 9908 字 |
| 一页字数 | ~300 字 |
| 一章页数 | ~33 页 |
| `pageStarts` 循环次数 | ~33 |
| 每次 `pageEnd` 二分 | ~12 次 `StaticLayout` |
| **总 StaticLayout 次数** | **~400 次** |
| 每次 StaticLayout 处理文本 | 最多近万字（二分 `hi = length`，整章） |
| 主线程耗时（功能机 CPU） | **5.6 秒** → ANR |

核心问题：`pageStarts` 的复杂度是 **O(章长 × log 章长)**，而回翻只需要**一页**的边界，
本应是 **O(log 章长)**。把单页测量做成了整章遍历，差了几十倍。

### 第 2 层（放大器）：`pageEnd` 的二分窗口是整章

`StaticLayoutMeasurer.pageEnd`（`KeyphoneReaderView.kt:266`）：

```kotlin
var lo = start + 1
var hi = length                        // ← 整章长度
while (lo < hi) {
    val mid = (lo + hi + 1) / 2
    if (heightOf(text, start, mid, spec) <= budget) lo = mid else hi = mid - 1
}
```

`heightOf(text, start, mid, spec)` 构造的 `StaticLayout` 文本范围是 `[start, mid]`，
`mid` 可达 `length`（整章近万字）。也就是**单次 `pageEnd` 的 12 次测量，每一次都在对
最多近万字的文本做 native `LineBreaker.nComputeLineBreaks` 断行**。

这一层让"单页测量"本身就重：哪怕只翻一页（`next()` 走 `pageEnd`），也是 12 次"近万字
StaticLayout"。日志佐证：`17:25:50.554`、`17:25:53.563`、`17:25:58.575` 多次
`主线程连续忙 1002ms`——那是 `next()` 和字号变化时的单页测量卡顿（轻症，未到 ANR）。

### 第 3 层（触发面）：缓存失效的入口太多

`pageStarts` 缓存命中条件是 `cacheVersion == version && cacheChapter == chapterIndex`。
而 `version` 在以下情况 `+1`（缓存全失效）：

- `setSpec`（字号变化 / 视口变化）—— `changeFont`（按键 1/3）、`onSizeChanged`（旋屏等）
  都走这里；
- `append`（取到新章）；
- `reset` / `clear`。

也就是说：**用户每按一次字号键，缓存就清空，下一次 `render()` 重新 `pageEnd`**。
连按字号 5 次 = 5 次单页重测 = 5 次 ~1 秒卡顿（日志 `17:25:50–51` 实证）。
而 `prev` 在缓存失效后的第一次回翻，直接退化成整章遍历 → ANR。

---

## 三、修复方案

按"收益 / 风险 / 工作量"排序，分三档。**P0 是必须做的**（直接堵住 ANR），P1/P2 是把
分页测量整体做对（彻底消除卡顿）。

### P0：`prev()` 不再整章遍历（堵住 ANR，必做）

把 `previousPageStart` 从"先 `pageStarts` 算全表再反查"改成"**从当前 offset 往前二分
一次**"找上一页起点。单页测量，O(log 章长)，绝不整章。

**算法**（二分上一个页起点）：

```
已知：当前 offset 是某个页边界（页尾），要找"以它为页尾的那一页的页首"。
页首 S 满足：pageEnd(S) == offset（这一页从 S 开始、在 offset 处结束）。

二分 S ∈ [0, offset]：
  pageEnd(S) 对 S 单调不减（S 越靠后，页尾越靠后或相等）。
  找最大的 S 使 pageEnd(S) <= offset；由于页边界连续，该 S 满足 pageEnd(S) == offset。
  单页 pageEnd ≈ 12 次 StaticLayout，整个二分 ≈ 12 × 12 = ~144 次……仍可能偏重，见 P1。
```

**实现要点**：
- 新增 `measurePrevPageStart(start): Int`，纯二分，不碰 `pageStarts`。
- `prev()` 改调它；`pageStarts()` / `lastPageStart()` / `previousPageStart()`（旧整章版）
  全部删除。
- 二分窗口上界取 `offset`，不是 `length`——避免第 2 层的"对整章做 StaticLayout"。
- 仍保留 `pageStarts` 缓存用于**已算过的章**快速反查（可选优化，见 P1）。

**风险**：二分依赖 `pageEnd(S)` 的单调性。因 StaticLayout 断行存在边界噪声（一个字加
减可能让行数不变 → `pageEnd` 返回相同值），需在二分里处理"平段"：找到任一满足
`pageEnd(S) == offset` 的 S 即可，不要求唯一。单测必须覆盖：任意文本上 `prev`/`next`
往返不丢字、不跳字（页边界连续）。

### P1：`pageEnd` 的二分窗口收敛到"一页量级"（消除单页卡顿）

P0 堵住了 ANR，但单页 `pageEnd` 仍是 12 次"对近万字做 StaticLayout"，单次翻页仍可能
~1 秒（日志的 1002ms 卡顿就是它）。要做轻：

**思路 A（推荐）：限制二分的文本窗口**
一页字数有上限（视口高 × 行宽 / 字宽，可估出"最多 N 字"）。二分时 `hi = min(length,
start + MAX_PAGE_CHARS)`，`MAX_PAGE_CHARS` 取保守上界（如视口可容字数的 3 倍）。
于是每次 `heightOf` 的 StaticLayout 文本最多几百字，12 次 ≈ 几毫秒。
若该窗口内 `heightOf(start, hi) <= budget`，说明一页放得下这整段，直接 `hi`；否则在窗口内二分。

**思路 B（备选）：粗筛 + 精修**
先用 `Paint.measureText` / `getTextRunAdvances`（纯测宽、不排版）估算每页字数（按行宽
折算行数），得到一个粗略页尾；再用 StaticLayout 在粗略点附近 ±几字做精修。更快但要调参。

**实现要点**：
- `MAX_PAGE_CHARS` 按 `spec`（视口宽高 / 字号）动态算，不是写死。
- 窗口取小后 `pageEnd` 若返回 `start + 1`（窗口太小放不下哪怕一页），扩大窗口重试一次。

### P2：分页全部移到后台协程（治本，彻底无感）

P0+P1 之后，翻页/字号变化仍会在主线程做一次单页测量（几毫秒~几十毫秒，可接受）。
要彻底无感，把所有分页测量移到后台协程：

- 进入阅读 / 字号变化 / 取到新章 → 后台协程把当前章整章分页（用 P1 的轻量 `pageEnd`），
  结果写回 `pageStarts` 缓存；
- 主线程翻页只读缓存，命中即 0 测量；
- 缓存未算好时降级：`next` 用同步单页测量（P1 后很轻），`prev` 先用 P0 的单页二分兜底，
  后台算好后下次即走缓存。

**字号变化的特殊处理**：`setSpec` 不立即 invalidate + 重测。先沿用旧页边界画一帧（用户
立刻看到字变大、内容不跳），后台算好新边界再静默替换。这一步把"连按字号 5 次 = 5 次卡顿"
也消掉。

**实现要点**：
- 用项目既有的 `CoroutineDispatchers`（IO 调度器）跑后台分页。
- 后台写缓存要加内存屏障（`@Volatile` / 单线程调度器），主线程读缓存要容错（读到半算
  的表就降级到同步单页测量）。

---

## 四、文件改动清单

### P0（必做）

| 文件 | 改动 |
|---|---|
| `KeyphoneReaderPager.kt` | 删 `pageStarts`/`lastPageStart`/`previousPageStart`（整章版）；新增 `measurePrevPageStart`（单页二分）；`prev()` 改调它；`isLastPageOfChapter` 改用单页 `pageEnd` |
| `KeyphoneReaderPagerTest.kt` | 旧测试里依赖 `pageStarts` 整章遍历的断言要改；新增 `prev returns to the previous page start without scanning the whole chapter`、`prev and next round-trip never loses or duplicates text`、`prev from offset 0 goes to title page` |

### P1

| 文件 | 改动 |
|---|---|
| `KeyphoneReaderView.kt` | `StaticLayoutMeasurer.pageEnd` 二分窗口 `hi = min(length, start + maxPageChars(spec))`；新增 `maxPageChars` 估算 |
| `KeyphoneReaderPager.kt` | `PageMeasurer` 接口不变；`pageEnd` 行为契约补充"返回值 ≤ start + maxPageChars" |

### P2

| 文件 | 改动 |
|---|---|
| `KeyphoneReaderView.kt` | 引入后台分页协程；`changeFont`/`onSizeChanged`/`appendChapter` 触发后台重算；`setSpec` 改成"沿用旧边界画一帧 + 后台算新边界" |
| `KeyphoneReaderPager.kt` | 缓存读写加并发保护；主线程读缓存容错降级 |

### 不动

- `KeyphoneReaderThemes.kt`（外观常量，与性能无关）
- `KeyphoneReadingMode.js` / `KeyphoneReadingBridge.kt`（取章层，本次故障与它无关）
- `KeyphoneReadingPrefs.kt` / `KeyphoneReadingSessionStore.kt`
- `KeyphoneBrowserActivity.kt` 的取章 / 按键路由 / 退出 / 恢复链（P0/P1 不碰）
- AGENTS.md / strings（P0/P1 不改按键映射，无需同步规范）

---

## 五、验证标准

### P0 验收

1. **ANR 不再复现**：进入近万字章节（如第 177 章 9908 字），看一两页后连按"上一页"，
   主线程不卡、不崩。
2. **页边界连续**：任意章节上反复 `next`/`prev` 往返，文本不丢字、不重复、不跳页
   （单测 `prev and next round-trip never loses or duplicates text` 必过）。
3. **`prev` 不整章遍历**：单测断言 `prev` 调用次数 ≤ 阈值（用假测量器计数 `pageEnd`
   调用次数，断言 `prev` 触发的测量次数与 `next` 同量级，而非整章量级）。

### P1 验收

4. **单页翻页 ≤ 50ms**：近万字章节上 `next`/`prev` 单次主线程耗时 ≤ 50ms（功能机），
   日志不再出现 `主线程连续忙 1002ms`。
5. **连按字号不卡**：连按 5 次字号键，每次主线程耗时 ≤ 50ms。

### P2 验收

6. **字号变化瞬时响应**：按字号键后 1 帧内（≤16ms）文字变大显示，无重测卡顿；
   后台重算完成后静默修正页尾，用户无感。

### 回归

7. 旧的 excessive cpu 不复发（显示层仍是原生 TextView，三个心跳仍删除）。
8. 空屏（渲染进程被杀导致）不复发（显示层与 WebView 死活无关）。
9. 长读（连续翻几十页）CPU 归零、无卡顿、无闪退。

---

## 六、实施顺序

1. **P0**：先改 `prev` 的整章遍历 → 单页二分。**这一步就能堵住 ANR**，风险最低、收益最大。
   配套单测：往返连续性、`prev` 测量次数。
2. **P1**：收敛 `pageEnd` 二分窗口。消除单页翻页的 1 秒级卡顿。
3. **P2**：分页移后台 + 字号瞬时响应。彻底无感，但涉及并发，最后做、需细测。

P0 上线后即可解锁"进阅读就崩"的阻塞；P1/P2 在后续迭代补齐。

---

## 七、待审核确认

1. **P0 是否立即做**：这是堵住 ANR 的最小改动，建议立即做。✅（建议）
2. **P1 的窗口估算法**：用"视口可容字数 × 3"做保守上界，还是用 `Paint.measureText`
   粗筛？建议先用前者（简单、够用），不够再上粗筛。
3. **P2 的并发模型**：用 `CoroutineDispatchers` 的 IO 调度器 + `@Volatile` 缓存，还是
   单线程分页调度器？建议先 IO + 容错降级，与项目既有风格一致。
4. **字号瞬时响应（P2 的字号分支）是否纳入本轮**：可单独拆出，P0/P1 先上。建议拆。

---

## 八、实施记录（P0 + P1 已落地）

> 现场核对：`logs/crash-20261022-172812/logcat-all.txt` 第 14983 行「取到第 2 章……字数=10327」，
> 第 15028–15034 行栈 = `prev → previousPageStart → pageStarts → pageEnd → heightOf`，
> 与本文 §一 完全一致（万字章、进阅读后按两下上一页就 ANR）。

### 8.1 P0 的实际做法：**页边界表只增不改**，而不是「pageEnd 上二分」

本文 §三 的 P0 草案写的是「在 `pageEnd(S)` 上二分找上一页页首」。落地时**放弃了这条**，原因：

`pageEnd(S)` 对 S **不是**可用的单调/对齐函数。页序列是 `0 = P0 < P1 < … < Pn = length`，
回翻要的是 `P(k-1)`。而：
- 最大的满足 `pageEnd(S) <= offset` 的 S 其实是 `offset - 1`（单字页），不是 `P(k-1)`；
- 最小的满足 `pageEnd(S) >= offset` 的 S 可能是 `P(k-2)`（从上一页中间起排，可能排到 offset 之后）。

也就是说，**光靠对 `pageEnd` 二分无法判断「哪条是页边界」**，草案里担心的"平段"其实是更严重的
对齐问题。硬套会翻错页（跳页 / 停在页面中间），比 ANR 更难查。

落地做法（`KeyphoneReaderPager`）：

| 机制 | 说明 |
|---|---|
| `boundaries: HashMap<章下标, ArrayList<Int>>` | 每章**已量出来**的页边界，升序，首项恒 0 |
| `next()` 时 `recordBoundary()` | 向前翻出来的页尾就是下一页页首，顺手记账 → **回翻 0 次测量** |
| `previousBoundary()` → `boundariesUpTo(offset)` | 只补量到 `offset` 为止（不碰章末），再取表里小于 offset 的最大边界 |
| `lastPageStart()` | 跨章回翻落在「上一章最后页」：表里齐了就直接查，没齐才补量该章一次 |
| `append()` 只有**丢章**才 `invalidate()` | 预取成功不改变已有章节排版，缓存必须留着（否则每次取章都把回翻打回补量） |

复杂度：正常向前读 → 回翻 O(1)；换字号后的第一次回翻 → O(当前页之前的页数)，与章长无关。

### 8.2 P1：测量窗口收敛到一页量级（`KeyphoneReaderView`）

- 删掉 `pageEnd` 开头那句 `heightOf(text, start, length)` 的**整章试排**（它是每次页测量的最大开销）；
- 新增 `maxPageChars(spec)`：`(视口高/行高) × (视口宽/0.5em) × 2`，下限 64、上限 4096；
- 二分窗口 `hi = min(length, start + window)`；若结果正好顶到窗口右端且未到章末，
  窗口翻倍再量（最多到 4096），避免"估小了导致页尾填不满"；
- `lineSpacingMultiplier` 改为按 `spec.textSizePx` 现算（原来用的是 `fontIndex` 对应的字号，
  换字号那一瞬间两者可能不一致）；
- 新增耗时埋点：`next` / `prev` / `font±` 各打一条 `阅读分页耗时 xxx=NNms`，
  ≥50ms 升为 WARN（对应 §五 验收口径 4/5，真机 `adb logcat -s KeyphoneReaderView` 核对）。

### 8.3 单测（`KeyphoneReaderPagerTest`，18 个用例全绿）

新增 5 个，全部围绕本次故障：

| 用例 | 钉住什么 |
|---|---|
| `prev page never scans the whole chapter` | 万字章回翻 **0 次测量**（旧实现 = 1000 次） |
| `prev after a spec change only measures up to the current page` | 换字号后回翻只补量到当前页（≤3 次），不是整章 |
| `appending a chapter keeps the recorded boundaries` | 预取成功不清边界表 |
| `prev and next round-trip never loses or duplicates text` | 去程/回程页区间逐页一一对应，不丢字不重字 |
| `prev from the first page goes back to the title page` | 边界退化路径 |

### 8.4 P2 未做（有意）

P0+P1 之后主线程只剩「一次单页测量」（窗口几百字、十几次排版），已满足 ≤50ms 的验收口径；
P2 要引入后台协程 + 缓存并发保护，收益是"再省几十毫秒"，风险是并发读到的半张表。
按 §七.4 的建议**单独拆出**，等真机实测确认仍需要「字号瞬时响应」时再做。

### 8.5 后续修正：测量口径与 TextView 对齐（2026-10-22）

真机二次反馈「页尾那行只露半截、下一页开头接不上」。日志定位到 `StaticLayout.Builder` 与
TextView 的两个默认值不一致（`fallbackLineSpacing` / `breakStrategy`），量出来行高比实际小
约 30%（22px 字号：38 vs 49px/行），每页多塞一行、渲染时被裁 —— 详见
`reading-mode-native-textview-plan.md` §12.7.1。

对齐之后测量与实际逐像素相同（326 == 326、369 == 369），因此**取消了 §12.4 的「预算少排一行」保底**
（`usableHeight` 直接用整屏高，反而每页多显示一行）；避头尾拉标点的判定也随之回到
"不超过整屏"，不再有裁字风险。`阅读页` / `测量` / `排版核对` 三条 debug 日志保留做哨兵。
