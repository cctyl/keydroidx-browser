# 浏览器卡死根治方案：WebView 请出壳窗口（同进程三窗口架构）

> 状态：方案（待实施）。本文基于 2026-10-01 复现日志的实测证据，不依赖任何设备特定行为，对通用设备同样适用。
> 前置阅读：`docs/page-freeze-solution.md`（根因诊断、独立 Surface 方案及为何不足）。

## 一、为什么独立 Surface 还不够（本次复现铁证）

本次复现日志（2026-10-01 19:46:30 ANR）：

- 渲染进程 `com.android.webview:sandboxed_process0` **83% CPU 连续燃烧约 30 秒**，心跳 pong 滞后 15~21 秒；
- 主窗口帧管线被掐断：`Davey! duration=5591ms`，**`IntendedVsync→Vsync` 差 5.56 秒**——主窗口整整 5.6 秒没收到 vsync、没产出任何一帧；
- 卡死期间主线程**没有堵在任何调用里**（LOOP 逐消息探针静默、主线程看门狗判定"空闲"）——是**整个窗口的帧供给被掐断**，不是某个调用阻塞；
- 系统判**输入 ANR**（`Waited 5000ms for KeyEvent`），看门狗强制注入 HOME 把浏览器切走 → 用户感知"卡死 + 闪退"；
- 31.5 秒渲染进程被系统杀掉（`isolated not needed`），页面才恢复响应。

**关键：独立 Surface 救了光标/菜单这一层，但 WebView 本身还挂在主窗口里。** WebView 的图层被卡住的渲染进程拖住 → 主窗口这一整个窗口的合成出不来帧 → 同窗口里的 tab 栏、地址栏、软键栏、主窗口输入全部一起死。**壳和页面还在同一条船**——这是独立 Surface 没救住的根本原因。

## 二、方案核心：把 WebView 请出壳窗口

一句话：**页面窗口可以卡，壳窗口永远有帧。**

Android 每个窗口有独立的帧管线（独立 vsync 调度、独立 buffer queue、独立合成路径）。把 WebView 放进一个**独立窗口**，渲染进程烧爆时只有那个窗口的帧被掐断——页面定格在最后一帧（不冻结、不隐藏、不消失），壳窗口照常出帧、照常消费按键、照常响应。这就是"系统 UI 在 App 卡死时依然流畅"的同款机制，套到浏览器外壳上。

### 三窗口划分（全部同进程）

| 窗口 | 承载 | 焦点 | 烧爆时表现 |
|---|---|---|---|
| **A 壳窗口**（现有 Activity 窗口） | tab 栏、地址栏、查找栏、进度线、主页 ScrollView、软键栏（SDK bottomPanel） | **是**（收全部按键） | 照常出帧、按键即时响应 |
| **B 页面窗口**（WindowManager 新增） | browserFrameHost → browserFrame → 所有 WebView | 否（`FLAG_NOT_FOCUSABLE`） | 帧被掐断、页面定格在最后一帧；渲染进程缓过来后继续出帧 |
| C 壳覆盖层窗口（WindowManager 新增） | `KeyphoneShellOverlay`（光标 + 快捷菜单） | 否（`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`） | 光标/菜单照常绘制，完全不受页面影响 |

### 几何与坐标系

- B、C 两个窗口的尺寸/位置**严格等于内容区矩形**（`keyphone_content_frame` 的屏幕矩形：地址栏下沿 ~ 软键栏上沿）。
- 这样三套坐标系天然重合：**host 坐标 = B 坐标 = C 坐标 = 内容区坐标**，零换算（不会再犯光标错位的错）。
- 几何同步：`keyphone_content_frame.addOnLayoutChangeListener` → 重算矩形 → `windowManager.updateViewLayout(B/C, lp)`。地址栏显隐、查找栏弹出、全屏模式等所有引起内容区尺寸变化的状态，都会自动触发 content frame 布局变化 → 同步跟随，无需逐处埋点。
- B 窗口物理上只占内容区，**不可能盖住 tab 栏/地址栏/软键栏**（它们在内容区之外）。

### 输入模型

- **按键**：焦点留在 A（壳窗口），`dispatchKeyEvent` 完全不变。B 不抢焦点，系统不会把按键派给它。
- **光标点击**：`dispatchCursorTouch` 直接 `webView.dispatchTouchEvent(...)`——这是对视图对象的 Java 调用，与视图在哪个窗口无关，不变。
- **真实触摸**（有触屏的通用设备）：B 不设 `FLAG_NOT_TOUCHABLE`，真触摸直达 WebView；C 设 `FLAG_NOT_TOUCHABLE`，触摸穿透到 B。
- **ANR 面**：按键 ANR 只发生在焦点窗口（A），A 的帧管线和按键消费都不依赖 B → 烧爆时 A 不会按键 ANR。触摸事件会到达 B，但 B 与 A **同进程同主线程**，B 的触摸消息仍由主线程消费——只要主线程活着（本次日志证明主线程没有被堵死），B 也不会因触摸 ANR。

### 主页 / 网页切换

- 网页态：B 窗口内容可见（browserFrameHost VISIBLE）。
- 主页态：B 窗口内容隐藏（browserFrameHost GONE）。WebView 仍 attach（避免反复 surface 创建/销毁），仅不可见。homeScroll 在 A 中显示。
- C 始终可见（透明，不画任何东西时不占视觉）。
- 现 `updateTabMode()` 里 `browserFrameHost?.visibility = GONE/VISIBLE`（526/535 行）改为驱动 B 窗口内容的可见性。

## 三、具体改动（文件级）

### 新增：`KeyphoneAuxWindows.kt`

封装 B、C 两个窗口的创建、布局、同步、销毁：

```kotlin
class KeyphoneAuxWindows(activity: Activity) {
    private val wm = activity.windowManager
    private val webRoot: FrameLayout        // 内含 browserFrameHost（由 Activity 注入）
    private val overlayHost: FrameLayout    // 内含 KeyphoneShellOverlay（由 Activity 注入）
    private var attached = false

    fun attach(browserFrameHost: FrameLayout, shellOverlay: KeyphoneShellOverlay) {
        // webRoot addView(browserFrameHost, MATCH)
        // overlayHost addView(shellOverlay, MATCH)
        // wm.addView(webRoot, lpB)  —— TYPE_APPLICATION_PANEL, token=activity.window.attributes.token
        // wm.addView(overlayHost, lpC) —— 同上，addView 顺序后于 B → z-order 在 B 之上
        attached = true
    }

    fun sync(contentFrame: View) {
        // getLocationOnScreen → 算矩形 → updateViewLayout(B)、updateViewLayout(C)
        // 矩形为 0（home 态隐藏后）时把 B 内容 GONE，窗口本身保留
    }

    fun setWebVisible(visible: Boolean) { webRoot.visibility = ... }
    fun detach() { wm.removeView(webRoot); wm.removeView(overlayHost) }
}
```

### 修改：`keyphone_browser.xml`

- `keyphone_browser_frame_host` 从 XML 删除（改为代码创建，挂进 B 窗口）。
- `keyphone_content_frame` 保留（主页容器 + 几何基准）。
- 原快捷菜单浮层已删除（不变）。

### 修改：`KeyphoneBrowserActivity.kt`

- `onCreate`：代码创建 `browserFrameHost`（FrameLayout）→ 注入 B 窗口；`shellOverlay` 注入 C 窗口；`auxWindows.attach(...)`；给 `keyphone_content_frame` 加 `OnLayoutChangeListener` → `auxWindows.sync(contentFrame)`。
- `browserFrame`（SDK 提供的 WebView 容器）`addView` 进 `browserFrameHost`（不变，只是 host 现在在 B 窗口里）。
- `KeyphoneMouseController` 的 `host = browserFrameHost`（不变，引用还是它）。
- `updateTabMode()`：`browserFrameHost?.visibility = ...` → `auxWindows.setWebVisible(...)`。
- `onDestroy`：`auxWindows.detach()`。
- `KeyphoneShellOverlay` 不再挂 `keyphone_content_frame`（迁到 C 窗口）。
- 各 `host = browserFrameHost`（zone 跳转、几何换算 3999/4015/4104/4160/4180）不变——host 还是那个 FrameLayout，只是它的 parent 链换了窗口。

### 附带改动（一起做，非调试）

1. **LOOP 逐消息插桩 + 主线程看门狗改为默认关闭**（保留开关，仅排查时开）。卡死期间每秒数百条日志往 logd 灌，本进程 24% CPU 一半是它烧的——在通用设备上是纯负担。
2. **`KeyphoneSiteKeyStore` 主线程磁盘读**（日志实证 `StrictMode DiskReadViolation`）→ 启动时一次读入内存缓存，按键路径零 IO。

## 四、实施顺序（分两步，第二步前先验证核心假设）

### 第一步：spike——只挪 WebView，先验证假设

- 只做 B 窗口（WebView 挪出去）；overlay 暂留壳窗口不动。
- 复现烧爆场景，验证**两个核心假设**：
  1. 烧爆时壳窗口（A）还能出帧（tab 栏/地址栏/软键栏可动）；
  2. 烧爆时 A 还能消费按键（不再 ANR）。
- **判不通过的明确标准**：B 挪出去后，烧爆期间 A 窗口仍然不出帧、或者仍然输入 ANR。一旦出现，说明 stall 不是窗口级的（是进程级：共享 RenderThread/Choreographer 或共享主线程的某个不可见阻塞），窗口拆分在这台设备上无效，立即止损换方向（见风险）。

### 一处必须诚实标注的不确定性

本次复现里有一个日志无法完全钉死的环节：旧架构下主线程在 25.641 秒处理完最后一条消息后，**看似空闲却 5.6 秒没消费任何按键**。LOOP 探针（每条消息都有日志）和主线程看门狗都没抓到阻塞点。候选解释：
- 按键消费的某个环节依赖窗口帧就绪（vsync 停 → 按键派发被连带掐断）；
- 主线程卡在不产生消息日志的路径（idle handler / nativePollOnce 里的某个 native 阻塞，如 GC 等待——当时系统可用内存仅 51MB，内存压力极大）；
- 该 ROM 的定制行为。

**无论哪个成立，触发源都是渲染进程烧爆拖垮帧管线**——窗口拆分去掉的正是这个触发源。spike 验证的是端到端行为（帧 + 按键），不是机制，所以机制没钉死不影响方案成立，但必须在 spike 里把"按键消费"列为硬指标。

### 第二步：完整三窗口

- 上 C 窗口（overlay 迁独立覆盖层窗口）。
- 主页/网页切换适配、几何同步、附带改动。
- 老页面复现验证全套验收标准。

## 五、风险与回退

- **ROM 怪癖**：spike 先行就是为这个——先花最小成本验证"窗口隔离真能保住壳帧"。不通过不投入完整重构。
- **WebView 在独立窗口的生命周期**：surface 创建/销毁由窗口管理；标签切换的 addView/removeView 路径不变；onPause/onResume 不变。
- **z-order**：B 物理上只占内容区，不覆盖 chrome；C 在 B 之上（addView 顺序保证）。
- **回退**：`KeyphoneAuxWindows.attach` 失败时（极少见），保留旧单窗口路径作为兜底开关。

## 六、验收标准

基准场景：`m.xxsy.net` 章节页点"目录"烧爆期间：

| 场景 | 通过标准 |
|---|---|
| 光标移动 | 即时跟手 |
| 快捷菜单弹出/焦点移动 | 即时 |
| tab 栏 / 地址栏 / 软键栏 | 全程可点可响应 |
| 关标签 / 切标签 | 即时生效 |
| **输入 ANR / 闪退** | **不再发生**（核心指标） |
| 页面本身 | 定格在最后一帧，可见、不冻结不隐藏；渲染进程缓过来后继续出帧 |

## 七、为什么不选其他方向

- **多进程（第四期）**：渲染进程本就是独立进程（83% CPU 那个就是），再拆 App 多进程不解决帧管线问题，纯属自找 IPC 复杂度。
- **JS 节流（busy 时停 rAF/timer）**：能缩短烧爆时长，但治标——只要 WebView 图层还在壳窗口里，烧爆的那几秒壳照样陪死。可作为三窗口后的补充增强，不作主方案。
- **冻结/隐藏页面**：用户明确否决。
