# 阅读模式「看着看着文字消失 / 一直正在打开」排查记录

> 现象（用户口述）：全屏阅读模式看着看着，文字全部消失、只剩背景色；退出全屏后发现一直卡在
> 「正在打开」。两次复现：10-19 08:15（设备 `192.168.1.8`）与 10-19 21:13（同一台机器，序列号
> 变成 `192.168.1.5`）。导出的日志在 `logs/reading-blackscreen-20261019/`。

## 一、日志层面已经确认的事实

### 1. 浏览器进程自己的日志，在这台机器上一条都没留下来

三个 logcat 缓冲区加起来只有 **256 KiB**（`main` 256K / `system` 256K / `crash` 256K），
而系统里 `UnisocWatchdog` 每 30 秒打一轮、`BroadcastQueue` / `PowerManagerService` /
`AlarmManager` 每秒打一串，**十几分钟就把应用自己的日志顶掉了**。

实测：10-19 20:56 启动的浏览器进程 `19587`，在缓冲区里只留下系统侧的 WM / AM 记录，
`KeyphoneBrowser` / `TabWebViewClient` / `TabAdapter` 三个 tag **一条都没有**；
崩溃前的阅读现场完全不可见。

### 2. 系统侧唯一和「页面变空白」对得上的事件

```
10-19 21:13:26.976 I/ActivityManager: Killing 19618:com.android.webview:sandboxed_process0 ... (adj 900):
                excessive cpu 7890 during 300048 dur=1026561 limit=2
10-19 21:13:27.039 I/am_proc_died: [0,19618,...]
10-19 21:13:27.041 W/ActivityManager: Scheduling restart of crashed service
                acr.browser.lightning/org.chromium.content.app.SandboxedProcessService0:0
10-19 21:13:27.081 I/am_pss: [19587,10147,acr.browser.lightning,219791360,...,0,2,29]
10-19 21:13:38.6   D/WindowManager: Toast（来自 acr.browser.lightning）
10-19 21:14:27       电源键 → 回桌面（用户自己操作）
10-19 21:14:31       forceStop acr.browser.lightning（shell）
```

- 渲染进程 `19618` 从 20:56:18 起跑了 **~17 分钟**，期间 CPU 时间 `7890ms / 300048ms`（≈2.6%），
  系统按 `excessive cpu … limit=2` 把它杀了。这是 Unisoc 比 AOSP 更激进的保底策略。
- `adj 900` 说明当时浏览器**大概率已不在前台/不在焦点**（进程优先级被降级）。
- 渲染进程一死，WebView 图层就没了 → 页面区域变空白（只剩 WebView 背景）。
  这与「文字全部消失、只剩背景色」一致；`Schedule restart` 之后 WebView 重建，
  如果宿主仍在阅读态而没有重新注入正文，就会停留在空白。
- 21:13:38 宿主弹了一个 Toast（约 3 秒），说明**应用主进程活着并被执行到**，
  不是 ANR、不是崩溃。用户看到的「一直正在打开」很可能就是主进程里那个**全屏不透明遮罩**
  （`keyphone_page_loading`，背景 `#121D2C`，文案「正在打开…」）挂住了。

**注意：本次日志无法证明「遮罩挂住」发生在哪一步**（应用日志已被冲掉），
只能确定渲染进程被杀 + 主进程存活。

### 3. 另一个已确认的壳层缺陷（与遮罩卡死直接相关）

`pendingOpenUrl` 只在 `observeState` 看到「WebView 已 attach 且当前标签不再是主页」时才清除。
如果那次状态发射恰好发生在 WebView attach **之前**（弱机上很常见），pending 再也没人清，
遮罩会永久盖住内容区，且 `sendPendingOpenUrl` 会永久跳过重新导航 —— 表现就是「一直正在打开」。
已在 `armPendingOpenWatchdog()` 加 6 秒兜底（只在该页其实已有 URL 时才清，避免吞掉真实导航）。

## 二、这次补的日志

全部只读、无副作用。定时探针只在 debug 包启用（与既有主线程看门狗同策略），
帧心跳只在真的停滞时才打日志。

| 日志关键字 | 位置 | 能看到什么 |
|---|---|---|
| `阅读模式切换` / `进入阅读模式(结果)` / `退出阅读模式` / `退出阅读模式导航` | `KeyphoneBrowserActivity` | 进出阅读态的时刻与现场（主页 / 遮罩 / 菜单 / 查找 / 全屏 / 页面栈） |
| `阅读态被导航清掉` | 同上 | 阅读中页面自己发起了导航（正文被替换、阅读态失效） |
| `阅读翻页 wv:` | 同上 | 每次翻页的 WebView 数值（scrollY / content / viewport / remain / URL） |
| `阅读 JS 现场:` | 同上 | 页面内 `__keyphone_rm__.diag()`：loading/ended/paused/fails/fetches/chapters/domPages/next/root/scroll/docHeight/nearBottom + 最近 12 步拼页 trace |
| `阅读视觉现场:` / `阅读视觉预检（进入前）:` | 同上 | **新增**：主题序号、样式表背景色、body 实际背景色、横向/纵向滚动、视口尺寸、阅读器根节点 top/height、正文文本长度 —— 用来区分「没内容」「滚出视口」「主题切成深底」 |
| `【阅读现场】` | 同上 | 每 2 秒采样（阅读态或加载中）：遮罩是否 VISIBLE 及挂了多久、wvProgress、wvVis、scrollY、url、loading |
| `【阅读现场】加载已持续 … 未结束` + 线程栈 | 同上 | 加载连续超过 8 秒时抓 WebView 相关线程栈 |
| `【阅读现场】帧回调已等待 … 未返回` | 同上 | **新增**：阅读态每 3 秒让 WebView 出一帧，帧回调 3 秒不回就报 ERROR —— 空白屏但日志无异常时唯一的证据 |
| `加载遮罩 显示/隐藏` | 同上 | 全屏不透明遮罩的每次显隐，带挂了多久 |
| `待打开地址超时未确认` | 同上 | `pendingOpenUrl` 6 秒兜底 |
| `state:` | 同上 | 每次 progress / URL / 标签变化后的完整状态 |
| `onPageStarted` / `onPageFinished elapsed=` | `TabWebViewClient` | 导航起止与耗时、距上次完成多久 |
| `onReceivedError` / `onReceivedHttpError` | 同上 | 主帧错误码 / HTTP 状态码 / 失败 URL（原来完全没有） |
| `渲染进程死亡` | `TabAdapter` | 渲染进程被系统杀掉的时刻与对应 tab |

## 三、复现与取日志（日志已按天落盘，见 [reading-mode-log-persistence.md](./reading-mode-log-persistence.md)）

> **2026-10-20 更新**：本项目已接入 KeydroidxLog 文件日志（`KeydroidxLog.init`），
> 日志按天写入 `Android/data/acr.browser.lightning/files/log/yyyyMMdd.log`，
> 复现后直接 `adb pull` 该文件即可，**不再需要放大 logcat 缓冲区、也不用抢时间**。
> 下面的 logcat 方式仅作为补充。

### （补充）放大 logcat 缓冲区

日志被系统噪声冲掉是这次拿不到现场的直接原因，复现前先做：

```powershell
# 1) 放大三个缓冲区（重启后失效，所以每次复现前都要执行）
adb -s 192.168.1.5:5555 shell "setprop persist.logd.size 4194304"
adb -s 192.168.1.5:5555 shell "setprop persist.logd.size.system 4194304"
adb -s 192.168.1.5:5555 shell "setprop persist.logd.size.crash 4194304"
adb -s 192.168.1.5:5555 shell "logcat -G 4M"

# 2) 清空，然后正常使用并复现
adb -s 192.168.1.5:5555 logcat -c

# 3) 复现后立刻导出（趁缓冲区没被冲掉，先导出再操作设备）
adb -s 192.168.1.5:5555 shell "logcat -b all -d -v time" > logs\repro.log
```

黑屏当下如果还能操作，先截图（PowerShell 下必须 screencap + pull）：

```powershell
adb -s 192.168.1.5:5555 shell screencap -p /sdcard/black.png
adb -s 192.168.1.5:5555 pull /sdcard/black.png .
adb -s 192.168.1.5:5555 shell rm /sdcard/black.png
```

## 四、下一步（待复现日志确认后）

- 若 `【阅读现场】帧回调已等待` 出现 → 确认是「页面不再出帧」，方向是渲染进程/合成（对齐 21:13
  的 `excessive cpu` 杀进程）；
- 若 `阅读视觉现场:` 里 `cssBg=#000000` 且 `theme=4` → 是主题切到「夜间黑」导致字看不见，属渲染逻辑问题；
- 若 `加载遮罩 显示` 后没有对应的 `隐藏` → 是壳层遮罩卡死，按 `pendingOpenUrl` / `progress` 日志定位；
- 若出现 `渲染进程死亡` + `Schedule restart` → 按渲染进程被杀处理：阅读模式下重新注入正文
  （WebView 重建后自动恢复阅读态），并降低连续拼接对渲染进程的压力。
