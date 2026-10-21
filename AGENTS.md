# AGENTS.md

本项目是从 Lightning Browser 上游 fork 出来的，但**工作重心只有一个：把浏览器适配到诺基亚功能机（240×320 / 320×480）的按键操作上**。上游触屏 / Compose 相关的实现只在必要处提及，不作为开发目标。

---

## 1. 项目定位与现实边界

- 包名 `acr.browser.lightning`，单模块（`app/`），单变体，minSdk 24 / compileSdk 37 / JDK 21。
- **真正生效的 UI 全部在 `acr.browser.lightning.keyphone` 包**：原生 View + keydroidx SDK，无 Compose。
- 上游 MVP 内核**被复用**：`BrowserPresenter`（事件入口 `onEvent(BrowserUiEvent)` + `state: StateFlow<BrowserViewState>`）、`BrowserContract`、`browser/tab/` 标签页内核、`database/`、`preference/`。
- 上游 Compose 渲染层**基本被绕过**，但有 **2 个漏口仍会打开触屏 Compose 界面**，属于已知未适配项：

| 漏口 | 触发方式 | 现状 |
|---|---|---|
| **隐身模式** | `KeyphoneOptionsActivity.kt:216` → `IncognitoBrowserActivity.launch()`，而该类继承 `BrowserActivity`（Compose 宿主） | 打开后是触屏 Compose 浏览器，方向键基本不可用 |
| **外部链接** | 其他 App 发 `VIEW` / `WEB_SEARCH` → Manifest 里 `DefaultBrowserActivity`（同样是 `BrowserActivity` 子类） | 同上 |

> 动这两个入口之前先想清楚：它们是「补适配」的候选，而不是可以忽略的死代码。

**改 `keyphone/` 相关代码前必读**（生态硬规范，位于同级仓库）：

- `../keydroidx-core/docs/NOKIA_DEVELOPMENT_RULES.md`（按键处理、catch 必打日志、图标规范、排版硬规则）
- `../keydroidx-core/docs/spec/typography-and-font-spec.md`、`../keydroidx-core/docs/spec/responsive-layout-spec.md`（字号 / 分辨率唯一事实源）
- 代码注释常以「红线 #8/#9/#13」「规范 §6.3」引用这些文档。

---

## 2. 常用命令

在含 `gradlew.bat` 的项目根执行（Windows PowerShell）。

```powershell
.\gradlew.bat :app:assembleDebug          # Debug APK -> app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat :app:lintDebug              # 只跑 Lint，改完涉及 API 调用的代码后必跑
.\gradlew.bat :app:lintRelease :app:assembleRelease   # Release（先 Lint 再 R8）
.\gradlew.bat :app:testDebugUnitTest      # 全部单测
.\gradlew.bat build                       # 全量构建 + 单测 + Lint
```

**Lint 是硬要求，不可绕过**：

- **禁止 `-x lint`**。minSdk=24 而 compileSdk=37，越级 API 编译期静默、只在旧真机上 `NoSuchMethodError` 闪退，`NewApi` 是唯一静态防线。
- `app/build.gradle.kts` 里 `lint { checkReleaseBuilds = false }`，所以 `assembleRelease` **不会**自动跑 `lintVitalRelease`，必须显式先跑 `:app:lintRelease`。
- 项目**没有 CI**（`.github` 已删除），不会有任何自动化兜底。

一键脚本（均为 `pause` 收尾，脚本调用需喂 stdin）：

- `build_debug.bat` — 出 Debug 包
- `build_install_debug.bat [serial]` — 构建 + 装到所有已连设备（并行）+ 启动
- `build_release.bat` — lintRelease + assembleRelease，成功后打开输出目录
- `install_debug.bat [serial]` — 只装不构建（`install_debug.py` 挑 `apk\debug\` 下最新 APK）

**运行与调试**：

```powershell
# 必须显式指定 activity-alias，这是唯一可靠的启动方式
adb -s <serial> shell am start -n acr.browser.lightning/acr.browser.lightning.DefaultBrowserActivityOrange
```

- **不要用 `monkey -p ... -c LAUNCHER 1`**：debug 包的 LeakCanary 也注册了 LAUNCHER 入口，`monkey` 会随机命中它弹出泄漏详情页。
- `keyphone.BrowserHomeActivity` / `KeyphoneBrowserActivity` 均 **not exported**，直接 `am start` 会 `SecurityException`。
- 蓝图标 alias（`.DefaultBrowserActivityBlue`）默认 `enabled="false"`，需先在应用内「更换应用图标」启用。
- 截图：**不要用 `adb exec-out screencap -p > x.png`**（PowerShell 按文本处理二进制会损坏 PNG），改用 `shell screencap -p /sdcard/x.png` + `pull`，用完 `rm`。
- 日志：`adb logcat -c` 先清空；`adb logcat -d -v time -s KeyphoneBrowser` 读按键与标签页日志（`updateTabMode` 等日志量大易被冲掉）。

**测试约定**：JUnit4 + Robolectric（`@RunWith(RobolectricTestRunner::class)` + `@Config(application = TestApplication::class, sdk = [SDK_VERSION])`，且**无 androidTest**）；断言 AssertJ，Flow 用 Turbine，协程用 `FakeCoroutineDispatchers`；**mock 用 Mockingbird（编译期生成，不是 Mockito）**；手写替身命名 `Fake*`。

---

## 3. 按键机适配层全景（`keyphone/`，20 个文件）

### 3.1 入口链

```
activity-alias (Orange 默认 / Blue 备用)
  → KeyphoneBrowserActivity? 否，先经 BrowserHomeActivity（立即转发并 finish）
  → KeyphoneBrowserActivity（singleTop，实际浏览主页）
```

`BrowserHomeActivity` 继承 `KeydroidxBaseActivity`、`getContentLayoutRes() = 0`，只做 Intent 转发（带 action/data/extras + `FLAG_ACTIVITY_FORWARD_RESULT`）。

`KeyphoneBrowserActivity` **不继承** `BrowserActivity`，而是继承 SDK 的 `KeydroidxBaseActivity`，同时实现 `BrowserContract.View`、注入同一个 `BrowserPresenter`——即「绕开 Compose 渲染，保留 MVP 事件通道」。**必须先完成 Dagger 注入再 `super.onCreate()`**。

### 3.2 文件职责

| 分类 | 文件 | 作用 |
|---|---|---|
| Activity | **`KeyphoneBrowserActivity.kt`（约 5.2k 行）** | 核心巨类：UC 标签栏 + 原生主页 + WebView 宿主 + 光标 + 菜单浮层 + 阅读模式 + 按键分发 |
| Activity | `BrowserHomeActivity.kt` | 启动代理，转发后 `finish()` |
| Activity | `KeyphoneOptionsActivity.kt` | 全屏选项页（标签/页面/收藏与记录/系统四组）；`EXTRA_PAGE_MODE=true` 时只回传 `EXTRA_ACTION` |
| Activity | `KeyphoneHistoryActivity.kt` | 历史页（上下移动、确认打开、左键删、LSK 清空） |
| Activity | `KeyphoneGuardListActivity.kt` | 拦截规则管理（协议跳转 / 站内跳转 / 下载站点 / 中转规则） |
| Activity | `KeyphoneHelpActivity.kt` / `KeyphoneHelpDetailActivity.kt` | 帮助列表 / 正文（方向键整屏翻页，步长 0.85 屏高） |
| Fragment | `KeyphoneTabsFragment.kt` | 标签列表，继承 `KeydroidxListPageFragment`，push 在浏览页内 |
| View | `KeyphoneCursorView.kt` | 40dp 虚拟光标，`translationX/Y` 位移（0 重绘），贴边变箭头 |
| View | `KeyphoneShellOverlay.kt` | WebView 上层全透明覆盖层，自绘快捷菜单卡片 |
| 弹窗 | `KeyphoneOptionSheetDialog.kt` | 通用底部选项卡（纯代码 UI），1~9 数字直选 |
| 控制 | `KeyphoneMouseController.kt` | 鼠标模式引擎：方向键移光标（短边×0.015，140ms 后连发，800ms 加速 6.5x）、贴边滚动（滚**光标下元素**而非文档根）、1~9 热区跳转 |
| 桥接 | `KeyphoneJsDialogs.kt` | 接管 `alert/confirm/prompt/beforeunload`，禁止系统模态框钉死页面 |
| 桥接 | `KeyphoneUrlGuard.kt` | 拦截判定引擎（`PROCEED/BLOCK/ASK`）：外部协议、站内跳转、下载审批 |
| 桥接 | `KeyphoneReadingBridge.kt` | 阅读模式 JS 桥，持 `WeakReference` 防池化泄漏 |
| 存储 | `KeyphoneGuardStore.kt` | 拦截规则（SP），域名归一化为 eTLD+1 |
| 存储 | `KeyphoneSiteKeyStore.kt` | 1~9 键自定义光标落点，存**比例坐标 0~1**（适配分辨率与旋屏） |
| 存储 | `KeyphoneReadingPrefs.kt` | 阅读模式外观（背景 5 档 / 字号 14~40px），落盘 `keyphone_reading` |
| 工具 | `KeyphoneUrls.kt` | 地址归一化：`about:` 内页不回填、空串→主页、其余走 `smartUrlFilter` |
| 工具 | `KeyphoneViews.kt` | **所有列表行 / 宫格卡 View 工厂**（`iconRow/searchRow/addressBar/tabRow/bookmarkGrid/quickMenuItem/ucTabCell`），强制走 SDK 字号 Token + 字体图标 + 主题色 |

### 3.3 SDK 与资源

- 依赖 `io.github.cctyl.nokia:keydroidx-key-core:1.0.0`，来自 `../keydroidx-core` 的 `publishToMavenLocal`（**必须同时发布 `:keydroidx-common` 和 `:keydroidx-key-core`**；core 的 AGP 版本不同，不能用 composite build）。`settings.gradle.kts` 仓库首位是 `mavenLocal()`。
- 常用 SDK 类：`KeydroidxBaseActivity`（7 个 Activity 基类）、`KeydroidxListPageFragment`、`KeydroidxListFocusHelper`（列表循环导航）、`KeydroidxConfirmDialog`、`KeydroidxTextInputFragment`、`KeydroidxKeyAction`（UP/DOWN/LEFT/RIGHT/SELECT）、`KeydroidxIcons`（字体图标）、`KeydroidxTheme`、`KeydroidxFontManager`、`KeydroidxDimens`、`KeydroidxLog`。
- 纯逻辑、不依赖 SDK：`KeyphoneCursorView`、`KeyphoneShellOverlay`、`KeyphoneMouseController`、`KeyphoneSiteKeyStore`、`KeyphoneUrls`、`KeyphoneReadingBridge`。
- **只有 2 个布局**：`keyphone_browser.xml`（仅主界面）、`keyphone_list.xml`（其余 6 个列表页共用，行由 `KeyphoneViews` 动态构建）。
- 文案全部在 `res/values/keyphone_strings.xml`。

### 3.4 主页红线

主页是**纯原生 View**（搜索栏 + 九宫格书签 + 历史），严禁 `loadUrl("about:home")` 之类伪协议（会被 Chromium 重定向成 `chrome://` 报错）；主页状态下底层 WebView 保持空白。

---

## 4. 按键映射与交互红线（改 keyphone 必看）

### 4.1 主界面按键（默认态）

| 按键 | 网页态（有页面） | 主页态（书签宫格） |
|---|---|---|
| 方向键 | 移动虚拟光标（`KeyphoneMouseController.onDirectionDown/Up`，短按微移、长按连发加速、贴边滚动） | `KeydroidxListFocusHelper` 移动搜索框 / 书签焦点 |
| 确认键（中键 / Enter / Numpad5 / Button_A） | 点击光标所在位置（DOWN/UP 转原生触摸给 WebView，由 WebView 判定单击/长按） | 短按打开书签或搜索；**长按书签进入编辑模式** |
| `1`~`9` | 光标跳到对应九宫格热区（**只移动不点击**，落点由确认键触发；站点可用专属键位覆盖） | 无 |
| `0` | 循环切换下一个标签页 `cycleNextTab()` | 同左 |
| `*` | 弹出**页面菜单** | 同左 |
| `#` | 弹出**全局菜单** | 同左 |
| 左软键 LSK | 打开选项页 `KeyphoneOptionsActivity` | 同左 |
| 右软键 RSK | 返回上一页 `handleBack()` | 退出应用 `confirmExit()` |
| 返回键 | 返回上一页，无历史时退出 | 退出确认 |

书签编辑模式：确认键 = 删除当前书签；中软键显示「删除」，右软键「返回」。

### 4.2 两个快捷菜单

**`*` → 页面菜单**（`buildPageMenuItems()`，面向当前页面 / 窗口）

| 键 | 菜单文案 | 实现 |
|---|---|---|
| 1 | 刷新/停止 | `refreshCurrentPage()` |
| 2 | 加入书签 | `addCurrentPageToHome()` |
| 3 | 复制链接 | `copyCurrentPageLink()` |
| 4 | 分享页面 | `shareCurrentPage()` |
| 5 | 页内查找 | `findInCurrentPage()` |
| 6 | 阅读模式（进 / 退） | `toggleReadingMode()` |
| 7 | 清空标签页 | `confirmClearAllTabs()` |
| 0 | 新建窗口 | `createNewWindow()` |
| `*` | **关闭窗口** | `closeCurrentTabOrExit()` |

**`#` → 全局菜单**（`buildGlobalMenuItems()`，面向全局工具）

| 键 | 菜单文案 | 实现 |
|---|---|---|
| 1 | 输入网址 | `openSearchEditor()` |
| 2 | 快捷搜索 | `openSearchEditor()` |
| 3 | 历史记录 | `showHistoryPage()` |
| 4 | 跳转拦截 | `showJumpGuardMenu()` |
| 5 | 页首/页尾 | `toggleScrollTopBottom()` |
| 6 | 自由复制 | `startFreeCopyMode()` |
| 7 | 专属按键适配 | `showSiteKeyUrlMenu()` |
| `*` | **无图切换** | `toggleBlockImages()` |
| `#` | **切换全屏** | `toggleFullScreen()` |

**菜单内通用操作**：上 / 下方向键移焦点；`1`~`9`、`*`、`#` 直达执行对应项；左软键或确认键执行当前焦点项；右软键或右方向键关闭菜单。

**两个必须知道的易错点**：

1. **`*` 菜单里没有 `#` 项**。在 `*` 菜单按 `#` 是**切换到全局菜单**（不是执行功能）；反向在 `#` 菜单按 `1` 可切回页面菜单。这是 `dispatchKeyEvent` 里的兜底分支，改菜单时别破坏。
2. **菜单打开后同一个键会变成菜单项**（`#` = 切换全屏、`*` = 无图切换 / 关闭窗口）。所以设备连击会造成「按一次 `#` 直接退出全屏」。已用 `shouldSwallowDuplicatedActionKey()` + `ACTION_KEY_DEBOUNCE_MS = 400ms` 做防重入，新增执行类单键动作时必须一并接入。

### 4.3 其它模式按键

| 模式 | 按键行为 |
|---|---|
| **阅读模式** | `↑` / `2` 上翻一屏；`↓` / `8` 下翻一屏（长按连翻）；`1` 减小字号；`3` 增大字号；`7` 轮换背景（米白 → 护眼黄 → 护眼绿 → 夜间灰 → 夜间纯黑）；左右方向键、确认键、其余数字直接消费（光标层隐藏，热区无意义）；`*`、`#`、软键、返回仍放行 |
| **页内查找** | `↑` / `←` / 左软键 = 上一个；`↓` / `→` / 确认键 = 下一个；右软键 = 退出查找 |
| **自由复制** | 方向键移动复制光标；确认键 = 开始选择 / 执行复制 |
| **地址栏聚焦** | 数字 / 字母正常输入；确认键 = 提交；右软键 = 取消焦点；左软键 = 选项页 |
| **站点键位标定** | `1`~`9` 选要标定的键；方向键微调光标；确认键保存落点；左软键重置该站点点位 |

### 4.4 编码红线

- `catch` 分支**必须落日志**（`KeydroidxLog.e`），严禁空 catch。
- 菜单 / 弹窗按键必须用 `KeydroidxKeyAction` 枚举匹配，**禁止硬编码 keyCode**。
- 消费了 DOWN 就必须消费同键 UP/REPEAT；执行类动作要过滤 key repeat（`repeatCount == 0`）。
- 执行类单键动作（`0` / `*` / `#`）必须过 `shouldSwallowDuplicatedActionKey()`，防硬件连击。
- 字号只能用 `keydroidx_font_*` 语义 Token，禁止裸 sp。
- 含 TextView 的动态列表行用 `WRAP_CONTENT` + `setMinimumHeight()`，**不要固定行高**（大字号会裁字）。
- 确认 / 取消弹窗统一 `KeydroidxConfirmDialog`；列表焦点导航统一 `KeydroidxListFocusHelper`。
- 快捷菜单弹出期间 `dispatchKeyEvent` 全模态消费按键并拦截 UP，防止穿透到 WebView。
- WebView **必须**经 `WebViewFactory` + `pool/` 获取，不要直接 `new WebView()`；`ImeBlockableWebView` 是鼠标模式专用（可彻底屏蔽系统输入法）。

---

## 5. 目录地图（全量，每个目录一句话作用）

路径简写：`…/lightning/` = `app/src/main/java/acr/browser/lightning/`。

### 5.1 事件总入口与上游浏览内核 `browser/`（76 文件）

| 文件 / 子目录 | 作用 |
|---|---|
| `…/lightning/BrowserUiEvent.kt` | **全站事件总入口**（sealed interface），keyphone 大量直接派发 `TabClick` / `SearchConfirmed` / `BackClick` / `MenuClick` 等 |
| `BrowserPresenter.kt`（60 KB） | MVP 中枢：唯一事件入口 `onEvent(BrowserUiEvent)` + `state: StateFlow<BrowserViewState>` |
| `BrowserContract.kt` | `View` / `Model` / `Navigator` 契约 + `Action` + 各事件枚举 |
| `BrowserNavigator.kt` | 开设置 / 分享 / 下载 / 关浏览器；**含 keyphone 分支**（keyphone 退出时跳过 `ExitCleanup`） |
| `BrowserActivity.kt` | 上游 Compose 宿主（abstract），**keyphone 不继承它**；仅隐身与外部链接会走到 |
| `BrowserScreen.kt` / `BrowserViewState.kt` | Compose 根组合与状态快照，keyphone 不用 |
| `tab/`（13 + `bundle/` + `settings/`） | **标签页内核，全部复用**：`TabModel` / `TabAdapter` / `TabFactory` / `TabsRepository`（即 `BrowserContract.Model`）/ `TabPager`（WebView 挂载协调）/ `WebViewFactory` / `TabInitializer`（`Url`/`HomePage`/`BookmarkPage`/`HistoryPage`/`DownloadPage`/`Bundle`/`Freezable`/`NoOp`/`Permission`）/ `UrlHandler` / `TabWebViewClient` / `TabWebChromeClient` / `RecentTabModel`（撤销关闭栈）/ `ImeBlockableWebView`（可屏蔽系统输入法，鼠标模式专用）/ `TabViewState` |
| `tab/settings/` | `TabSettings`（19 项偏好快照）、`RenderingMode`、`TextSize` |
| `tab/bundle/`（+`storage/`） | 标签持久化：`DefaultBundleStore` 按 URL 还原 Initializer、`IncognitoBundleStore` 全丢弃、`BundleWriter` 原子落盘 |
| `view/`（6） | WebView 交互：`CompositeTouchListener`、长按解析、滚动协调、`CustomViewCoordinator`（全屏视频） |
| `search/`（3） | `IntentExtractor`（Intent→`Action`，keyphone 直接注入）、`SearchBoxModel`、`SearchBoxDisplayChoice` |
| `history/`（3） | `HistoryRecord` + 默认实现 + 隐身 NoOp（由 DI 切换） |
| `notification/`（4） | 标签数通知 `TabCountNotifier` + 空实现 + 隐身常驻通知 |
| `webrtc/`（2） | 网页权限请求的 Model / View，`TabWebChromeClient` 直接依赖 |
| `menu/`（1） | `MenuSelection` 枚举，主菜单全部可选项（keyphone 复用） |
| `cleanup/`（4） | 退出清理命令，**keyphone 显式跳过** |
| `keys/`（2） | `KeyCombo` + `KeyEventAdapter`，只服务 `BrowserActivity` |
| `ui/`（1） | `TabConfiguration`（DESKTOP / DRAWER_SIDE / DRAWER_BOTTOM），按键机只剩偏好值 |
| `compose/`（15）+ `compose/sheets/`（8） | 上游触屏 UI，keyphone 零引用 → 见 5.5 |
| `color/`（1） | `ColorAnimator` —— 死代码 |

### 5.2 数据层与 DI

| 目录 | 作用 |
|---|---|
| `database/`（23，含 5 子包） | 手写 `SQLiteOpenHelper`（**非 Room**），「Repository + Database」成对：`bookmark/`（+`BookmarkExporter`）、`history/`、`downloads/`、`allowlist/`、`adblock/`（+`InMemoryHostsRepository`）；领域模型根 `WebPage.kt`（派生 `HistoryEntry` / `Bookmark.Entry` / `Bookmark.Folder` / `SearchSuggestion`）；`DatabaseDelegate.kt` 缓存并按需重开 DB |
| `preference/`（7） | DataStore 设置中心：`UserPreferencesDataStore.kt`（43+ 个 `PreferenceStore`，**所有偏好项唯一声明处**）、`datastore/PreferenceStore.kt`（NonNull / Nullable / Enum 三种实现）、`IntEnum.kt`、`datastore/Migrations.kt`；另有 `DeveloperPreferenceStore`、`CleanupPreferencesDataStore` |
| `di/`（13） | Dagger 图：`AppComponent`（`@Singleton`，额外注入 `BrowserHomeActivity` / `KeyphoneHistoryActivity`）+ `AppModule`（445 行：OkHttp ×3、目录限定符、Mezzanine Reader、`AdBlocker`、`Cleanup.Action` 列表）+ `AppBindsModule`；子组件 `BrowserComponent`（**同时注入触屏与 keyphone 两个 Activity**）、`SettingsComponent`；`RendererBusyProvider`（渲染进程繁忙预判，切/关标签时降级防主线程冻住）；`DiExtensions.kt`（`Context.injector`） |
| `search/`（27：`engine/`11 + `suggestions/`9） | `SearchEngineProvider`（11 种引擎）、`SuggestionsModel`（书签 : 历史 : 建议 = 2:2:1，上限 5）、`engine/BaseSearchEngine` + 11 实现、`suggestions/BaseSuggestionsModel` + 5 实现 + `NoOpSuggestionsRepository` |
| `download/`（5） | `DelegatingFileDownloader` 按前缀分派：`data:` → `DataImageFileDownloader`，其余 → `DefaultFileDownloader`（HEAD 补元信息 → `DownloadManager` → 落库） |
| `bookmark/`（3） | 书签**导入**（导出在 `database/bookmark/`）：Netscape 格式（jsoup）、本浏览器按行 JSON |

### 5.3 支撑包（按需查阅，一般不直接改）

| 目录 | 作用 |
|---|---|
| `adblock/`（26） | `BloomFilterAdBlocker` / `NoOpAdBlocker`；`parser/HostsFileParser`；`source/`（Assets / File / Url 三种 hosts 源 + Provider）；`allowlist/`（会话级白名单）；`util/`（BloomFilter、`hash/MurmurHash.java`、`object/JvmObjectStore` 落盘） |
| `pool/`（2） | `ObjectPool` + `LimitedObjectPool`，WebView 池化复用（**必经**） |
| `concurrency/`（3） | `CoroutineDispatchers`（DB 调度器 `limitedParallelism(1)` 保证 SQLite 串行）+ `App/Browser/Tab` 三个语义作用域 + `combineMultiple` |
| `connectivity/`（1） | `ConnectivityProvider.hasInternetAccess: MutableStateFlow<Boolean>` |
| `favicon/`（3） | `FaviconModel`（磁盘缓存 + `LetterImagePainter` 首字母占位）、`FaviconCleanup`、`UriExtensions` |
| `preview/`（2） | 标签缩略图缓存 `PreviewModel` + Coil3 `TopCropTransformation` |
| `graphics/`（1） | `LetterImagePainter`：带首字母的圆角方块位图 |
| `utils/`（9） | `UrlUtils.smartUrlFilter`、`IntentUtils.startActivityForUrl`（交外部 App）、`WebUtils`（清缓存）、`ThreadSafeFileProvider`（异步建目录）、`FileUtils`、`DeviceUtils` + 3 个遗留 Java（`Utils` / `ThemeUtils` / `DrawableUtils`） |
| `extensions/`（10） | 按接收者归类：`ContextExtensions`、`CursorExtensions`（游标→列表）、`CloseableExtensions`（`safeUse`）、`StringBuilderExtensions`、`PermissionRequestExtensions` 等 |
| `ssl/`（4） | `SslState` + `SslWarningPreferences`（**仅内存，重启即忘**）+ `SslCertificateInfo` |
| `useragent/`（2） | `DefaultUserAgentProvider`（DEFAULT / DESKTOP / MOBILE / CUSTOM） |
| `icon/`（2） | `BrowserIcon`（ORANGE / BLUE）+ `BrowserIconSelector`（启停 alias 换桌面图标） |
| `shortcuts/`（3） | `DefaultShortcutGenerator`（API 26+ `requestPinShortcut`）与 `LegacyShortcutGenerator` |
| `migration/`（1） | `Cleanup.kt`：按 `fixedInVersionCode` 与 `lastInstalledVersion` 只跑新增的一次性 Action |
| `device/`（2） | `BuildInfo`（包名 / BuildType / versionCode / isPlus）+ `ScreenSize.isTablet()` |
| `log/`（3） | `Logger` + `AndroidLogger` / `NoOpLogger`（按 BuildType 选） |
| `constant/`（1） | `Constants.kt`：硬编码 UA、URL scheme、`about:home` / `about:bookmarks` / `about:history` 等本地页 scheme |
| `ids/`（1） | `ViewIdGenerator`（动态 View ID，`takenIds` 供预览裁剪） |
| `resources/`（2） | `ResourceProvider` / `NumberFormatter` |
| `theme/`（2） | `DefaultThemeProvider`（隐身恒 DARK，否则取 `useTheme`） |
| `dialog/`（2） | 传统 View 对话框辅助：`BrowserDialog`、`DialogItem` |

### 5.4 资源与测试

| 目录 | 内容 |
|---|---|
| `res/layout/`（**仅 4 个**） | `keyphone_browser.xml`（主界面）、`keyphone_list.xml`（6 个列表页共用）、`dialog_auth_request.xml`、`dialog_ssl_warning.xml` |
| `res/values/`（8） | `strings.xml`、**`keyphone_strings.xml`（按键机文案）**、`arrays` / `attr` / `colors` / `dimens` / `integers` / `styles` |
| `res/values-*` | 21 种语言 + `values-v21/`（styles）+ `values-xlarge/`（dimens） |
| `res/drawable/`（38）、`mipmap-anydpi-v26/` | 矢量图标与形状（`ic_action_*`、`ic_block`、`card_bg`、`tab_background*`）；`ic_launcher_orange/blue` 自适应图标 |
| `res/xml/`、`raw/`、`anim/`、`color/` | `filepaths.xml`（FileProvider）；`default_bookmarks.dat`（首启书签）；5 个转场动画；图标着色 selector |
| `assets/`（10） | `hosts.txt`（1.69 MB 内置广告域名库）+ 9 个搜索引擎 logo |
| `src/test/`（28） | 包名镜像 `main`，**无 `androidTest`**；`Constants.kt`（`SDK_VERSION = 36`）、`TestApplication`、`TestUtils.unimplemented()`；覆盖 adblock / 搜索建议 / PreferenceStore / TabViewState / WebRTC 等 |

### 5.5 明确不用看 / 不要往这里加东西

| 目录 | 说明 |
|---|---|
| `browser/compose/`（15）+ `compose/sheets/`（8） | 上游触屏 UI（三套 tab 外壳、搜索框、溢出菜单、查找）。keyphone 内零引用 |
| `browser/color/ColorAnimator.kt` | 死代码 |
| `browser/keys/KeyEventAdapter.kt` | 只服务 `BrowserActivity`，keyphone 自己处理按键 |
| `browser/cleanup/` | keyphone 退出时被显式跳过 |
| `browser/ui/TabConfiguration` | 只剩偏好值，按键机不走三套外壳分支 |
| `compose/`（4） | Material3 主题基建，仅供设置页使用 |
| `settings/`（24） | **仍在用**（见 5.4），但它是 Compose，不是适配目标 |
| 其余小包（`ssl/`、`useragent/`、`icon/`、`shortcuts/`、`migration/`、`constants/`、`extensions/`、`dialog/`、`log/`、`ids/`、`resources/`、`theme/`、`device/`、`bookmark/`、`download/`） | 上游支撑设施，按键机开发一般不直接改；需要时按包名定位即可 |

### 5.6 html / js（Mezzanine，改完必须重新构建）

`interface` + `@FileStream("src/main/xxx")` + `provideJs()/provideHtml()`，KSP 在**编译期**把 `src/main/js/*.js`、`src/main/html/*.html` 内联为字符串常量；清单在 `app/build.gradle.kts` 的 `mezzanine { files = ... }`。

按键机专用脚本：

- `js/KeyphoneScroll.js`（10.7 KB）— 把贴边滚动对象从文档根改成**光标下元素**
- `js/KeyphoneReadingMode.js`（66 KB）— 阅读模式：正文提纯、下一页识别、自动拼页、页数裁剪；**必须保持纯 ASCII**（中文用 `\uXXXX`），事件经 `window.__keyphoneReadingBridge__` 回传

### 5.7 设置页（唯一残留的 Compose 界面）

- 入口：首页模式 `KeyphoneOptionsActivity.kt:165` → `startActivity(SettingsActivity)`；页面模式 `relay(ACTION_SETTINGS)` → `KeyphoneBrowserActivity.kt:5201` → `menuClick(MenuSelection.SETTINGS)` → `BrowserNavigator.openSettings()`。
- 结构：`settings/activity/SettingsActivity`（宿主）+ `settings/framework/`（声明式设置项：`ToggleState` / `ClickableState` + `ClickableOnClick` 11 种动作）+ `settings/screens/`（9 个屏，每屏只 `createSettingsFrameworkState()`）+ `settings/navigation/`（自研路由，非 Jetpack Navigation）。
- 加一个设置项 = `settings/screens/*Screen.kt` 加声明 + `preference/UserPreferencesDataStore.kt` 加字段。
- 它是**触屏控件**（开关、底部单选栏、字号滑杆）。若要彻底按键机化，这是最后一块待改造界面。

---

## 6. 构建与工具链坑

- `.bat` 必须**纯 ASCII**：cmd.exe 按 OEM 代码页（中文系统 936/GBK）逐字节解析，UTF-8 中文注释会被误解码、行尾当命令执行。中文说明写进本文件。
- **依赖锁定**：`activateDependencyLocking()` + `app/gradle.lockfile`。改依赖后必须 `--write-locks`；**若需要清掉已废弃的配置名，`--write-locks` 只会追加不会删除，必须先删 lockfile 再生成**。
- `gradle.properties` 开了 configuration-cache / isolated-projects / parallel / KSP2 / incremental。**`--no-configuration-cache` 与 isolated-projects 冲突，不能一起用**。
- `isolated-projects=true`：构建脚本**禁止跨工程访问 Project API**（`rootProject.file(...)`、`project(":x")`），要用 `java.io.File` + `layout.projectDirectory` 自行拼路径。
- `gradle.properties` / `local.properties` 里的 Windows 绝对路径会触发 Lint `PropertyEscape`，该规则对 Windows 路径**不可满足**；已用 `app/lint.xml` 做按文件豁免，不要放开其它检查或在 `build.gradle.kts` 里全局 disable。
- Lint 结果会从构建缓存恢复：改了 `gradle.properties` 仍报旧错时，先删 `app\build\intermediates\lint*`、`app\build\reports\lint*` 再跑。
- **本工程没有 licensee 插件**，不存在 `licensee*` 任务（历史文档里出现过，是误记）。
- 单测 `jvmArgs` 里大量 `--add-opens` 是 Robolectric + JDK 21 必需，不要删。
- 未经用户明确许可，**不要执行 `git commit`**。

---

## 7. 「要改 X 该去哪」速查

| 需求 | 位置 |
|---|---|
| 按键机主页 / 标签栏 / 菜单 / 按键分发 | `keyphone/KeyphoneBrowserActivity.kt` |
| 列表行、宫格卡外观 | `keyphone/KeyphoneViews.kt` |
| 光标 / 鼠标模式 / 热区跳转 | `KeyphoneMouseController.kt`、`KeyphoneCursorView.kt`、`js/KeyphoneScroll.js` |
| 阅读模式 | `js/KeyphoneReadingMode.js` + `KeyphoneReadingBridge.kt` + `KeyphoneReadingPrefs.kt` + `docs/reading-mode-*.md` |
| 网页弹窗 / 跳转拦截 / 下载审批 | `KeyphoneJsDialogs.kt`、`KeyphoneUrlGuard.kt`、`KeyphoneGuardStore.kt`（另需同步看 `browser/tab/TabWebViewClient`、`TabWebChromeClient`、`TabAdapter` 三处调用点） |
| 1~9 键光标落点 | `KeyphoneSiteKeyStore.kt` |
| 标签页行为 / WebView 创建 | `browser/tab/`（**必须经 `WebViewFactory` + `pool/`**） |
| 地址栏归一化 / 搜索引擎识别 | `KeyphoneUrls.kt` + `utils/UrlUtils.kt` + `search/` |
| 新增偏好项 | `preference/UserPreferencesDataStore.kt`（唯一声明处） |
| 新增设置项（Compose 设置页） | `settings/screens/*Screen.kt` |
| 新增依赖 | `app/build.gradle.kts` + `app/gradle.lockfile`（`--write-locks`） |
| 补适配缺口 | 隐身模式 `IncognitoBrowserActivity`、外部链接 `DefaultBrowserActivity`（都还是 Compose） |

---

## 8. 参考资料

- `docs/`（8 份中文文档）：九宫格热区鼠标跳转、网页输入劫持、阅读模式（黑屏诊断 / 分章跳读 / 日志持久化 / 渲染隔离）、页面冻结多窗口方案。**动阅读模式 / 光标 / 滚动前先翻这里**。
- `prototype/`：2 个 HTML 原型（按键机 UI、跳转拦截），不参与编译。
- 工作区相对上游已删除 `LICENSE`、`CHANGELOG.md`、`CONTRIBUTING.md`、`.github/`、`launcher_icon*.png`，因此**无 CI**，`README.md` 徽章链接已失效。
