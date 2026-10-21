# 阅读模式日志落盘：使用生态标准 KeydroidxLog（不再依赖 logcat）

> 结论先说：**本项目此前从未调用 `KeydroidxLog.init()`，所以 keydroidx-common 的文件日志器一直是关闭的。**
> 所有 `KeydroidxLog` 调用只写 logcat，而本机型 logcat 三个缓冲区各只有 256 KiB，
> 系统自身日志十几分钟就把应用日志冲掉 —— 这正是「复现了却完全抓不到现场」的根因。

## 一、为什么不自己写一个日志文件

`keydroidx-common` 的 `io.github.cctyl.nokia.common.log.KeydroidxLog` 已经提供完整能力，
生态规范（`NOKIA_DEVELOPMENT_RULES.md` §15）也明确要求统一使用它、禁止自建：

| 能力 | 说明 |
|---|---|
| 落盘位置 | `Android/data/<包名>/files/log/yyyyMMdd.log`（外部目录不可用时回退内部 `files/log`） |
| 轮转 | 按天，保留 7 天 |
| 详细日志开关 | `isDetailedLogEnabled` / `setDetailedLogEnabled`，**debug 包默认开启**（落 DEBUG 及以上），release 默认只落 ERROR 与崩溃 |
| 崩溃 | `KeydroidxLog.e` 与未捕获异常同步落「待上传」标记；配合 `KeydroidxCrashReporter` 可在下次启动自动上传 |

自建一个 `KeyphoneLogFile` 属于重复造轮子，且会绕开崩溃上报与 7 天保留策略，已移除。

## 二、本项目的接入方式（`BrowserApp.onCreate`）

```kotlin
if (!isIncognito) {
    KeydroidxLog.setTag("KeyphoneBrowser")
    KeydroidxLog.init(this)
}
```

必须满足的约束：

1. **必须早于全局异常处理器安装**，否则崩溃栈写不进当天文件；
2. **不要再调 `KeydroidxLog.installCrashHandler(this)`**：它会覆盖本项目已有的
   `Thread.setDefaultUncaughtExceptionHandler`（其中包含 debug 包 `FileUtils.writeCrashToStorage`）。
   当前做法是：在现有处理器里先 `KeydroidxLog.e(...)` 记录，再交给原处理器继续抛；
3. `:incognito` 进程不初始化（隐私模式不留日志）。

## 三、改了什么代码

- `BrowserApp.kt`：补上 `KeydroidxLog.setTag` + `init`；崩溃处理器里记录异常。
- `TabWebViewClient.kt`：原来导航日志只走注入的 `Logger`，而 **release 下它是 `NoOpLogger`（完全不输出）**。
  现在 `onPageStarted` / `onPageFinished` / `onReceivedError` / `onReceivedHttpError` 同时走 `KeydroidxLog`，
  debug 包全量落盘，release 至少 ERROR 级落盘。
- `KeyphoneBrowserActivity.kt`：阅读模式相关日志（进出、遮罩显隐、翻页、JS 现场、视觉现场、帧停滞）全部走
  `KeydroidxLog`，因此会随 debug 包的详细日志一并落盘。

## 四、取日志（复现之后随时都可以，不用抢时间）

```powershell
# 日志按天落盘，直接拉文件；不需要复现后立刻操作设备
adb -s 192.168.1.5:5555 pull /sdcard/Android/data/acr.browser.lightning/files/log/20261020.log .
```

如果外部目录不可用（回退到了内部目录），用 run-as：

```powershell
adb -s 192.168.1.5:5555 shell run-as acr.browser.lightning cat files/log/20261020.log > 20261020.log
```

文件格式：`[MM-dd HH:mm:ss.SSS][级别][TAG] 内容`，可直接 grep/Select-String，例如：

```powershell
Select-String -Path 20261020.log -Pattern "阅读现场|加载遮罩|阅读视觉现场|onPage|渲染进程死亡"
```

> 注：debug 包才有 DEBUG 级日志；如果想在 **release 包**上也拿到完整现场，
> 需要在应用内开启「详细日志」开关（`KeydroidxLog.setDetailedLogEnabled(context, true)`，
> 生态 `KeydroidxAboutFragment` 里有现成开关，本项目尚未接入 About 页）。

## 五、已验证

- `assembleDebug` / `lintDebug` 均通过；
- 装机后启动即生成 `/sdcard/Android/data/acr.browser.lightning/files/log/20261020.log`，
  内容含 `加载遮罩 隐藏` / `state:` / `updateTabMode` 等阅读排查关键行；
- 该文件可直接 `adb pull` 导出（已实测，样本见 `logs/device-log-verify/`）。

## 六、下一次复现要看的日志

| 关键字 | 判断 |
|---|---|
| `【阅读现场】帧回调已等待 … 未返回` | 页面不再出帧（渲染进程 / 合成层问题） |
| `阅读视觉现场:` 里 `theme=4` / `cssBg=#000000` | 主题切到「夜间黑」，字与底色都深，表现为字消失 |
| `加载遮罩 显示` 之后没有对应 `隐藏` | 壳层遮罩卡死（见 `pendingOpenUrl` 6 秒兜底） |
| `渲染进程死亡` / `onPageStarted` 后无 `onPageFinished` | 渲染进程被杀或导航卡死 |
