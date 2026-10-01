# 浏览器卡死问题：原因分析与解决方案

> **实施状态：第一期、第二期、第三期已实现**（commit `ac4b4f37`）。第四期（多进程）为备案，未做。
> 改动文件：`KeyphoneBrowserActivity.kt`、`TabsRepository.kt`、`TabAdapter.kt`、`TabWebViewClient.kt`、`KeyphoneGuardStore.kt`、`AppModule.kt`、新增 `di/RendererBusyProvider.kt`。

> 问题：打开章节特别多、JS 很重的页面（如 https://m.xxsy.net/chapter/22304467000039402/95797029348849723 ），点击页面内"目录"按钮后，整个浏览器卡死一段时间——菜单打不开、光标动不了、标签关不掉。
>
> 要求：**网页可以卡，浏览器壳永远不许卡。** 菜单、光标、标签管理任何时候都必须可响应。

---

## 一、卡死的真正原因

不是简单的"章节太多加载慢"，机制更具体：

**Android WebView 的网页渲染跑在系统的独立渲染进程里。当页面 JS 一次性渲染上千个章节 DOM 时，渲染进程被占满。此时主线程对 WebView 做的某些调用（`dispatchTouchEvent`、`evaluateJavascript`）是跨进程同步等待的——主线程会原地冻结，直到渲染进程腾出手。**

光标点击的最终落地路径（光标是原生层画的"假鼠标"，网页看不见它，按下确认键时伪造一次触摸喂给 WebView）：

```
确认键 → dispatchKeyEvent (KeyphoneBrowserActivity.kt:2899 起的按键分发)
       → 鼠标模式分支
       → dispatchCursorTouch (KeyphoneBrowserActivity.kt:3653)
       → 在光标当前坐标构造 MotionEvent（DOWN 和 UP）
       → webView.dispatchTouchEvent(DOWN) / (UP)   ← :3677 / :3691，同步跨进程调用
```

平时渲染进程毫秒级响应，没事；但"目录"触发页面 JS 一口气渲染几千个章节 DOM，渲染进程瞬间被占满——主线程在 `dispatchTouchEvent` 里原地等它，等多久卡多久。**主线程一冻，菜单、光标、按键全部失灵**（它们本身不碰 WebView，是纯粹被主线程堵死连累的）。

代码中已有注释实测过该机理：`KeyphoneMouseController.kt:67-72` 明确写到"每帧都发跨进程调用会把 UI 线程堵在等渲染进程上……实测会导致 input ANR 被杀进程"。

## 二、已有防护及其漏洞

项目已有一套 `pageBusy` 看门狗（`KeyphoneBrowserActivity.kt:2531-2604`）：每 1 秒向渲染进程 ping 一次（`evaluateJavascript("1")`），超过 1.5 秒没回应就标记"页面繁忙"，之后触摸派发、JS 注入都会被跳过。思路正确，但有三个洞：

1. **生效延迟窗口约 2.5 秒**（ping 间隔 1s + 阈值 1.5s）。页面刚开始卡的那 2.5 秒内 `pageBusy` 未置位，而点"目录"的那次点击恰好就发生在这个窗口里——**第一次点击永远不受保护**。
2. **关标签/切标签路径完全没有守卫**。`TabAdapter.freeze()/destroy()`（`TabAdapter.kt:467-490`）里的 `stopLoading/onPause/destroy/removeAllViews` 全是主线程直调 WebView，页面繁忙时关标签照样卡。
3. **事后补救，不是事前预防**。看门狗只能"发现卡了之后不再碰"，不能"让点击本身不卡"。

次要放大因素：

- `TabAdapter.kt:524-551`：`onPageFinished` 后主线程 `view.draw(canvas)` 给整个 WebView 截图做标签预览，DOM 巨大时是一次昂贵绘制。
- `KeyphoneGuardStore.kt:304-314`：`isRedirectorBlocked()` 每次遍历 SharedPreferences 全表，而它在 `shouldInterceptRequest` 里每个子资源都调一次，拖慢加载期、延长渲染进程繁忙期。
- 全项目没有 `onRenderProcessGone` / `WebViewRenderProcessClient` 处理：渲染进程崩溃时默认行为是**系统连 App 进程一起杀**（网页崩 = 浏览器闪退）。

## 三、方案选型

结合"长期做高度自定义浏览器"的目标：**选第一期（事前预判）+ 第二期（逃生通道）+ 第三期（渲染进程死亡处理 + 优化项），暂缓多进程（第四期备案）。**

理由：

1. **多进程代价双倍贵。** Android 系统限制一个 App 只有一个进程能使用 WebView（数据目录、Cookie、缓存绑定进程）。多进程意味着所有 WebView 进 `:web` 进程，菜单/光标/标签/软键栏留在主进程，两者之间每一次交互都要跨进程。对"高度自定义"的浏览器，等于给未来每个功能（脚本注入、规则引擎、手势、插件）加一道 IPC 税。
2. **多进程要防的事，`onRenderProcessGone` 已能防住。** 多进程真正的收益是"渲染进程崩溃时外壳不死"，而 `WebViewRenderProcessClient`（API 26+）在单进程下同样能收到渲染进程死亡回调。剩下的唯一风险"主线程被同步跨进程调用堵住"，恰好是第一、二期解决的。
3. **高度自定义浏览器的核心竞争力在外壳的响应性和可扩展性**，第一、二期做完就达到"外壳永远听使唤"的底线，这才是自定义功能的地基。

---

## 四、具体实现方案

### 第一期：事前预判——让那次点击永远堵不住主线程

目标：用户点击落在繁忙页面上时，点击被安全跳过，主线程零阻塞。

**1. 看门狗加密 + 记录"最后存活时间"**（`KeyphoneBrowserActivity.kt:2531-2604`）

- ping 间隔：1000ms → **400ms**。ping 用的 `evaluateJavascript("1")` 是异步发射不管的，不堵主线程，加密无风险。
- 新增字段 `lastPongAt: Long`，每次 ping 回调回来时更新。
- 新增判定函数 `isRendererLikelyBusy()`：`SystemClock.uptimeMillis() - lastPongAt > 800ms`（连续两次 ping 未回）即视为繁忙。保护窗口从 2.5s 缩到 **0.8s**，且从"事后发现"变为"随时可查的预判"。
- 原 `pageBusy` 标志保留，继续驱动软键栏"页面繁忙"显示。

**2. `dispatchCursorTouch` 加前置守卫（核心改动，`KeyphoneBrowserActivity.kt:3653-3695`）**

```
dispatchCursorTouch(action):
    if isRendererLikelyBusy():
        置 pageBusy = true（立即，不等看门狗下一轮）
        软键栏刷新为"页面繁忙"
        toast("页面繁忙，请点击已忽略，稍后再试")
        return true   // 消费按键，事件不到 WebView
    // 渲染进程活着才往下走
    构造 MotionEvent → webView.dispatchTouchEvent(...)
```

**3. 同窗口内其他跨进程调用一并收口**

- `:3676`（点击后安装输入劫持）和 `:3833`（探测脚本）的 `evaluateJavascript`：加 `isRendererLikelyBusy()` 前置判断，忙则跳过，页面恢复后由现有"恢复重注入"逻辑补上。
- `KeyphoneMouseController` 贴边滚动（`:288`、`:371` 已有 pageBusy 守卫）改为同时查 `isRendererLikelyBusy()`，新判定比 pageBusy 提前约 1.7s 生效。

第一期效果：点"目录"→ 页面开始卡 → 最迟 0.8s 后软键栏显示"页面繁忙"→ 后续点击被安全忽略并提示 → 菜单、光标、按键全程正常。第一次点击本身发生在页面未卡时（页面是收到点击后才开始渲染的），DOWN/UP 派发时渲染进程未满，大概率正常通过。

### 第二期：逃生通道——卡死时关标签、切标签、停加载永远可用

**1. 关标签/切标签降级路径**（`TabAdapter.kt:467-490` 的 `freeze()` / `destroy()`）

```
freeze(tab):
    if tab 对应的渲染进程繁忙（isRendererLikelyBusy / pageBusy）:
        browserFrame.removeView(webView)   // 本地操作，不等渲染进程，立即执行
        // 用户视角：标签已关闭，立即切走
        Handler.postDelayed(5s 或等 ping 恢复) { webView.destroy() }  // 延迟销毁
    else:
        走现有完整 freeze 流程
```

要点：**从视图树摘除是本地操作**，用户点"关闭标签"永远立即生效；WebView 对象销毁稍后补，即使最终泄漏一份内存，代价远小于整壳卡死。

**2. 软键栏"页面繁忙"升级为可操作菜单**（`KeyphoneBrowserActivity` 软键栏渲染处）

`pageBusy` 时提供两个按键项：

- 【停止加载】→ `webView.stopLoading()`。该调用也是跨进程的，用 Handler post 出去调，**调用前不等、调用后不检查**，发完不管；渲染进程对"取消加载"通常优先响应。
- 【强制关闭本页】→ 直接走上面第 1 条的降级关标签路径。

**3. 按键入口优先级审计**（`dispatchKeyEvent`，`KeyphoneBrowserActivity.kt:2899` 起）

确认菜单键、返回键的处理分支排在一切可能触碰 WebView 的分支之前。菜单本身不碰 WebView，第一期做完后这步是防御性确认，不需大改。

### 第三期：渲染进程死亡处理 + 优化项

**1. `WebViewRenderProcessClient`**（`WebViewFactory.kt` 的 `createWebView()`，API 26+）

单进程下防"页面把浏览器拖死"的最后一道闸：

```
webView.webViewRenderProcessClient = object : WebViewRenderProcessClient() {
    onRenderProcessGone(view, detail):
        标记该 tab 为"已崩溃"
        立即 removeView + destroy 该 WebView（渲染进程已死，不会再堵）
        显示覆盖层："页面已崩溃 [重新加载] [关闭标签]"
        return true   // 关键！返回 false 系统会连 App 进程一起杀
}
```

**2. 标签预览截图守卫**（`TabAdapter.kt:524-551`）

- `pageBusy` / `isRendererLikelyBusy` 时跳过本次截图，保留旧预览。
- 后续可换 `PixelCopy`（API 26+，异步不堵主线程）。

**3. 拦截规则表内存缓存**（`KeyphoneGuardStore.kt:304-314`）

- `isRedirectorBlocked()` 改为：启动时/规则变更时读入内存 `HashSet`，查询 O(1)。资源加载更快 → 渲染进程繁忙期更短。

**4. WebView 池预热**

- 切标签现场 `new WebView`（`WebViewFactory.kt:68`）有几十到几百 ms 卡顿。启动后和每次池取出后，用主线程 idle Handler 补一个预热实例（WebView 构造必须在主线程）。

### 第四期（备案，暂不做）：WebView 独立进程

若未来做插件沙箱、脚本引擎需要硬隔离再启动。路径：所有 WebView 集中到 `:web` 进程的 Activity，主进程通过 Binder/AIDL 发指令（按键、光标坐标、标签操作），页面信息回调回传。前三期的守卫逻辑原样保留，只换通信层。现在不做，是为了不给"高度自定义"的迭代速度上枷锁。

---

## 五、验收标准

基准场景：`m.xxsy.net` 章节页点"目录"。

| 场景 | 通过标准 |
|---|---|
| 页面繁忙中按菜单键 | 菜单 1 秒内弹出 |
| 页面繁忙中动光标 | 光标流畅跟随按键 |
| 页面繁忙中关标签 | 标签 1 秒内关闭并切换 |
| 页面繁忙中点页面 | 点击被忽略，有"页面繁忙"提示，无冻结 |
| 渲染进程崩溃（chrome://crash 类页面模拟） | 显示崩溃覆盖层，App 不闪退 |
| 页面从繁忙恢复 | pageBusy 解除，输入劫持等脚本自动补注入，功能恢复 |

## 六、实施顺序

一 → 二 → 三期，每期独立可交付、独立见效。第一期改动最小（看门狗加密 + `dispatchCursorTouch` 守卫），收益最大，先做。
