# 浏览器卡死问题：原因分析与解决方案

> **实施状态：独立 Surface 外壳方案已实现（本期）。** 同步调用守卫（P1 部分）仍保留为辅助。
> 核心改动：新增 `KeyphoneShellOverlay.kt`（独立 Surface + 专用渲染线程自绘光标/菜单），
> 删除 `KeyphoneCursorView.kt`，`KeyphoneBrowserActivity.kt` / `KeyphoneMouseController.kt` / `keyphone_browser.xml` / `keyphone_strings.xml` 相应改造。

> 问题：打开章节特别多、JS 很重的页面（如 https://m.xxsy.net/chapter/22304467000039402/95797029348849723 ），点击页面内"目录"按钮后，整个浏览器卡死——菜单打不开、光标动不了、标签关不掉，甚至闪退。
>
> 要求（用户铁律）：**网页可以卡，浏览器壳永远不许卡。** 光标、菜单、标签管理任何时候都必须可响应；页面本身必须保持可见、继续渲染（不许冻结、不许隐藏）。

---

## 一、卡死的真正原因（三轮实测后定论）

早期假设"主线程被同步跨进程 WebView 调用堵住"经过三轮探针**证伪**：冻结窗口内 `dispatchTouchEvent`、`evaluateJavascript`、`view.draw` 全部静默且调用本身 0ms 返回，主线程消息循环照常运行。

真实机制（日志实证）：

1. 巨型章节 DOM 把 **WebView 渲染进程**烧爆（持续 60%+ CPU）；
2. 渲染进程出帧太慢 → 应用主窗口的帧同步被拖垮：`Choreographer Skipped 300+ frames`、`Davey! duration=5635ms`、延迟集中在 `IntendedVsync→Vsync`（5.4s）和 `SwapBuffers`（2.7s）；
3. 光标、快捷菜单、软键栏这些 View **和 WebView 画在同一个窗口、同一条显示管线**上 → 主窗口一帧都出不来 → 所有 View 视觉上全卡死（帧率 ~1fps 甚至 5 秒无帧）；
4. 按键功能其实在执行（有按键日志），但**视觉反馈**（光标移动、菜单弹出）要等帧 → 用户感知"光标卡死、菜单卡死"。

**结论：任何"守卫同步调用""降渲染进程优先级"都救不了视觉卡死，因为病根不在调用链，而在共享的显示管线。** 必须让浏览器外壳**不走主窗口的渲染管线**。

## 二、方案：独立 Surface 自绘外壳（本期待实施）

### 核心机制

Android 的 `SurfaceView` 拥有**独立于窗口的 Surface**（独立 buffer queue，直接交给 SurfaceFlinger 合成）。用 `SurfaceHolder.lockCanvas()`（软件 Canvas）在**自建工作线程**（`HandlerThread("keyphone-shell-overlay")`）上绘制，**完全不经过主线程 Choreographer、不经过 RenderThread、不参与主窗口帧同步**。WebView 把主窗口帧同步拖到 5 秒一帧时，独立 Surface 依然能即时出帧。这正是"页面烧爆、系统 UI 依然流畅"的同款机制。

### 具体实现

**新增 `KeyphoneShellOverlay.kt`**（`SurfaceView`）：

- 透明 surface，`setZOrderOnTop(true)` 浮于主窗口一切内容（含 WebView）之上；不抢焦点、`dispatchTouchEvent` 恒返 false 触摸穿透给 WebView。
- `surfaceCreated` 时起渲染线程，`surfaceDestroyed` / `onDetachedFromWindow` 时退出。
- **状态模型 + 按需渲染**：主线程调 `updateCursor(x, y, edgeDir)`、`setCursorVisible(bool)`、`showMenu(rows, focusIndex, gravityRight)`、`updateMenuFocus(index)`、`hideMenu()` 写状态并 post 一帧；渲染线程 `lockCanvas` → 清屏 → 画光标（原 `KeyphoneCursorView.onDraw` 绘制代码原样迁入）→ 画快捷菜单（卡片背景 + 逐行文字 + 焦点高亮，样式对齐原 `KeyphoneViews.quickMenuItem`）→ `unlockCanvasAndPost`。高频连发按键合并为一帧（`renderQueued` 去重）。
- 渲染线程自绘不抛异常出进程：任何绘制异常吞掉，下一帧照常。

**Activity / 控制器改造**：

- `KeyphoneBrowserActivity.onCreate`：overlay 挂到 `android.R.id.content` 根容器（覆盖全内容区），传入 `KeyphoneMouseController`。
- `KeyphoneMouseController`：光标参数类型改为 `KeyphoneShellOverlay`，`cursorView.update(...)` → `updateCursor(...)`。
- 快捷菜单：菜单模型与按键逻辑全部留在 Activity，只把**绘制**搬到 overlay。`showMenu` → `shellOverlay.showMenu(rows…)`；焦点移动（上/下键、SDK `onAction` 两条路径）→ `shellOverlay.updateMenuFocus(index)`；`hideQuickMenu` → `shellOverlay.hideMenu()`。
- 删除主窗口里的 `keyphone_quick_menu_overlay` 浮层（XML + 字段）。

**删除噪音**：`setPageBusy` 不再显示任何 UI（删 Toast、删软键栏中键"页面繁忙"文案）——用户明确否掉该提示。busy 心跳检测保留，仅用于 `isRendererLikelyBusy()` 守卫日志与滚动/输入拦截。

### 保留的辅助防护（P1，已实现、非本次改动核心）

- `WebViewFactory`：`RENDERER_PRIORITY_WAIVED` + `offscreenPreRaster=false`。
- `TabWebViewClient.onRenderProcessGone`：渲染进程崩溃时 destroy + 清引用 + 返回 true（防闪退）。
- `TabsRepository.deleteTab/deleteAllTabs`：繁忙时先从列表移除再延迟 destroy（关标签立即生效）。
- `KeyphoneGuardStore` 内存缓存、`KeyphoneMouseController` 贴边滚动与 `evaluateJavascript` 注入的 busy 守卫。

## 三、不改的部分（明确边界）

- **WebView 页面本身照常渲染**：不冻结、不隐藏、不停 JS、不降帧。
- **标签栏 / 地址栏 / 软键栏**仍在主窗口：它们是静态元素，状态变化时才重绘，烧爆期间文字晚几秒更新可接受。若实测软键栏中键反馈也必须实时，可把软键文字搬进 overlay（架构已预留）。

## 四、验收标准

基准场景：`m.xxsy.net` 章节页点"目录"，页面烧爆期间：

| 场景 | 通过标准 |
|---|---|
| 页面烧爆中动光标 | 光标**即时**跟手（不再等 5 秒） |
| 页面烧爆中按 1 弹菜单 | 菜单即时弹出、上/下键高亮即时移动 |
| 页面烧爆中关标签 / 切标签 | 立即生效且有即时反馈 |
| 页面本身 | 保持可见、继续渲染，无冻结无隐藏 |
| 全程 | 无任何"页面繁忙"Toast / 提示 |

## 五、遗留风险与兜底

- `setZOrderOnTop(true)` 会盖住主窗口弹窗；本浏览器弹窗只有快捷菜单（已迁入 overlay），若发现其他浮层被盖，改 `setZOrderMediaOverlay(true)`。
- 触摸穿透：实测若 overlay 拦截触摸，加 `setOnTouchListener { false }` 兜底。
- 个别设备合成透明 top surface 兼容问题：回退为 `WindowManager.addView` 独立窗口，同样绕开主窗口帧同步。
- 若独立 surface 后仍有可感知残留卡顿（渲染进程持续烧导致帧率普遍降低）：再考虑 busy 时注入暂停 rAF/setInterval 的 JS（保留最后画面、不隐藏页面）降低渲染负载——暂不做，用户反感"冻结/隐藏"语义。
