# 按键机（Keyphone）适配 — 进度交接

> 最后更新：2026-09-27 · 工作区：`d:\project\keydroidx_ecosystem\keydroidx-lightning-browser`
> 本文件是**进度/交接说明**；项目本身的架构与构建约定见根目录 `CODEBUDDY.md`（注意：其开头「尚未接入生态适配层」已过期，见 §7）。

---

## 1. 任务目标与当前阶段

把上游 Lightning Browser（Compose + 触屏交互）改造为**按键机浏览器**：接入 `keydroidx-core` 生态 SDK，界面按 `../keydroidx-core/docs/reference/FEATURE_PHONE_UI_SPEC.md` 的 240×320 四区规范重做，用方向键 / 左右软键 / 中键操作。

当前阶段：**首页（进入浏览器后的第一屏）+ 全屏选项页 + 全屏网址编辑页 + 退出确认** 已落地并在真机验证通过。**「打开网址后的按键机浏览界面」尚未开始**（用户明确要求先不做），打开链接仍交给上游 Compose 浏览器渲染。

---

## 2. 环境、构建、验证

```powershell
# 在项目根目录（Windows PowerShell）
.\gradlew.bat :app:assembleLightningPlusDebug            # 打包（变体名必须带前缀）
.\gradlew.bat :app:lintLightningPlusDebug                # 改了涉及 API 的代码后必跑
.\gradlew.bat :app:compileLightningPlusDebugKotlin       # 只编译，迭代最快
```

- 输出 APK：`app\build\outputs\apk\lightningPlus\debug\app-lightningPlus-debug.apk`
- 一键脚本：`build_install_debug.bat`（构建 + 安装 + 启动）
- **验证机**：`192.168.1.4:5555`（Q968，API 33 / 320×480）—— 当前唯一能装包的设备
- **目标机装不上**：`4a24ecf`（MIWANG M2s）、`5990a29a`（UNNO F3）都是 **API 19 / Android 4.4.4**，本项目 `minSdk = 24` → `INSTALL_FAILED_OLDER_SDK`。**这是待用户决策的头号问题**，见 §7.1
- 启动首页（activity-alias 是应用唯一入口）：`adb shell am start -n acr.browser.lightning/acr.browser.lightning.DefaultBrowserActivityOrange`
  - `BrowserHomeActivity` / `KeyphoneOptionsActivity` 都是 `android:exported="false"`，**不能**用 `am start -n <包名>/<类名>` 直接调起（会 Permission Denial），App 内互跳正常
- 模拟按键：`adb shell input keyevent 20/19/21/22`（下/上/左/右）、`66`（中键）、`82`（左软键/MENU）、`4`（右软键/BACK）

---

## 3. 已完成的改动清单（全部未提交，`git status` 为准）

### 3.1 接入 keydroidx-core（Gradle / DI / Manifest）

| 文件 | 改动 |
| --- | --- |
| `settings.gradle.kts` | 仓库加 `mavenLocal()`（core 本地发布产物）+ `https://jitpack.io`（`keydroidx-common` 的传递依赖 `com.github.getActivity:XXPermissions`） |
| `app/build.gradle.kts` | 依赖加 `io.github.cctyl.nokia:keydroidx-key-core:1.0.0`、`androidx.lifecycle:lifecycle-runtime-ktx`；删掉 licensee 插件与 `licensee {}` 块 |
| `app/gradle.lockfile` | 同步新依赖（依赖锁定开着，加依赖必须 `--write-locks`） |
| `di/AppComponent.kt` | 加 `fun inject(activity: BrowserHomeActivity)`，供 keyphone 页取仓库 |
| `app/src/main/AndroidManifest.xml` | 注册 `BrowserHomeActivity`（singleTask / Theme.DarkTheme）、`KeyphoneOptionsActivity` |
| `app/src/LightningPlus/AndroidManifest.xml`、`app/src/LightningLite/AndroidManifest.xml` | 两个启动 `activity-alias` 的 `targetActivity` 从 `DefaultBrowserActivity` 改为 **`keyphone.BrowserHomeActivity`**（应用唯一入口） |

**为什么走 mavenLocal**：core 的 AGP 8.5.1 与本项目 AGP 9.4.1 冲突，**不能**用 `includeBuild` composite build。改完 core 必须先在 core 目录执行：
`gradlew.bat :keydroidx-common:publishToMavenLocal :keydroidx-key-core:publishToMavenLocal`

### 3.2 licensee 全量移除（用户明确要求）

`app.cash.licensee` 插件、`licensee {}` 块（会向 APK 塞许可报告 asset，按键机不需要）、`settings/licenses/` 整个目录 7 个文件、`SettingsNavigation.LICENSES`、`SettingsActivity` 的 LICENSES 路由与 `LicensesScreen` 分支、`AppBindsModule` 的 `DependenciesRepository` 绑定、`SettingsScreenStateProvider` 的 LICENSES 分支、关于页的入口项。

### 3.3 新增：按键机适配层 `app/src/main/java/acr/browser/lightning/keyphone/`

| 文件 | 职责 |
| --- | --- |
| `BrowserHomeActivity.kt` | 首页（三段式，见 §4）。继承 `KeydroidxBaseActivity`，`@Inject` 书签/历史仓库 |
| `KeyphoneOptionsActivity.kt` | 全屏选项页（LSK 进入），等价上游 `browser/compose/BrowserOverflowMenu.kt` 全集的展开列表 |
| `KeyphoneViews.kt` | 列表行视图工厂：`iconRow` / `searchRow` / `sectionLabel` / `divider` / **`bookmarkGrid`（宫格）** |
| `res/layout/keyphone_list.xml` | 通用内容布局：ScrollView(禁焦) + LinearLayout(`keyphone_rows`)，两页共用 |
| `res/values/keyphone_strings.xml` | 按键机专用中文文案（`tools:ignore="MissingTranslation"`，刻意不翻译） |

### 3.4 设计原型（当前已落后于实现）

`prototype/feature-phone-browser.html` — 单文件、零依赖的原型陈列页（v3）。**注意**：原型里书签仍是列表、选项页条目也不全，实现已经超出原型（宫格书签 + 14 项选项页）。要不要回同步原型由用户定。

---

## 4. 按键机 UI 设计契约（改界面前必读）

- 四区布局（core 基类 `KeydroidxBaseActivity` 提供骨架）：状态栏 0–24 / 标题栏 24–56 / 内容区 56–288 / 软键栏 288–320。**内容区高度约 230dp，装不下就得滚动**。
- 视觉体系直接来自 core `KeydroidxTheme`，**不要自己写颜色/圆角**：焦点高亮 = `KeydroidxTheme.createSelectionDrawable()`（半透明蓝圆角矩形）。
- **字号只能用 `@dimen/keydroidx_font_*` 6 档 Token**；动态建字必须走 `KeydroidxFontManager.setTextSizeResource()`，**禁止 `tv.setTextSize(n)`**。图标只能用 `KeydroidxIcons` 字体图标（无 PNG/XML）。
- **含 TextView 的动态行高必须 `WRAP_CONTENT` + `setMinimumHeight()`**，禁止写死行高（大字号会裁字）；纯 View（分隔线/空格）不受此约束，但**必须显式给高度**——否则在 `AT_MOST` 下会被 `getDefaultSize()` 撑满父容器剩余空间、把后续行挤成 0 高（本项目踩过）。
- 焦点统一走 `KeydroidxListFocusHelper`（首尾循环 = 红线 #6）；行根 View 必须 `focusable + focusableInTouchMode`（防首键被吞，红线 #8），ScrollView 必须 `focusable=false`。
- **禁止写 keyCode**，一律用 `KeydroidxKeyAction.UP/DOWN/LEFT/RIGHT/SELECT/SOFT_LEFT/SOFT_RIGHT`（红线 #4）。
- 弹窗/确认只能用 core 的 `KeydroidxConfirmDialog` / `KeydroidxOptionsDialog`（**禁止原生 AlertDialog**，红线 #9）；确认框是**底部弹出卡片、左软键=肯定、右软键=否定**。
- 异常日志一律 `KeydroidxLog`（`catch` 必须记一笔，红线见 core `AGENTS.md` §4）。

### 首页信息架构（`BrowserHomeActivity`）

```
标题栏   ⌕ 浏览器                     信号/电量
────────────────────────────────────────
  [ ⌕ 搜索或输入网址 ]        ← 焦点项 0，CENTER 进全屏编辑页
  书签                        ← 分组标题（3 列宫格，最多 6 个）
  [C]  [C]  [W]
  Change Contact Wiki
  [T]  [G]  [D]
  ...
  历史记录                    ← 分组标题（单列列表，最多 3 条，图标+标题+URL）
  ...
────────────────────────────────────────
左软键 选项 |  中键 空  |  右软键 返回
```

- 焦点列表是**扁平一维**（搜索框 → 宫格格子（行优先展开）→ 历史行）；宫格区间由 `gridStart` / `gridCount` 记录，`moveFocus()` 在宫格内做**二维导航**（左右换列、上下 ±`GRID_COLUMNS`），出宫格边界后交回 `KeydroidxListFocusHelper` 的线性循环。
- 常量：`GRID_COLUMNS = 3`、`MAX_BOOKMARK_CELLS = 6`、`MAX_HISTORY_ROWS = 3`、`MAX_INPUT_CHARS = 100`（都在 companion object）。
- 数据：`BookmarkRepository.getAllBookmarksSorted()` / `HistoryRepository.lastHundredVisitedHistoryEntries()`，`onResume` 里 `loadData()` 刷新并**保持原焦点索引**。
- 宫格图标：沿用上游 `graphics/LetterImagePainter.colorForCharacter()` 的四色算法（白字 + 蓝/绿/红/橙圆角方块），与原浏览器默认书签图标观感一致。
- CENTER 进编辑页 = core `KeydroidxTextInputFragment`（全屏，非小弹窗），回调用 `setOnConfirmListener`。
- `normalizeInput()`：含 `://` 原样；否则「无空格且含点」当域名补 `https://`，其余拼 **硬编码 Google 搜索 URL**（⚠ 未走 `SearchEngineProvider`，见 §7.3）。

### 选项页条目（`KeyphoneOptionsActivity`，14 项 / 4 组）

标签：新建标签页✅ · 新建无痕标签页✅
页面：刷新页面 · 停止加载 · 在页面中查找 · 添加书签 · 分享 · 复制链接 · 添加到主屏
收藏与记录：书签✅ · 历史记录 · 下载
系统：设置✅ · 退出浏览器✅

- 上游 `BrowserOverflowMenu.kt` 的 11 项**全部在位**；另加 3 项按键机刚需（刷新/停止/退出，因为没有工具栏按钮）。
- ✅ = 已接线。**未接线的两类**：
  1. 「页面」组 7 项 —— 上游都是对 `currentTab` 操作（`BrowserPresenter.onMenuClick`），而选项页目前从**首页**进入、没有当前网页 → 统一 Toast「请先打开网页后再使用」（`keyphone_need_page`）。
  2. 历史记录 / 下载 —— 上游是在浏览器里开 POP_UP tab 加载本地生成的 HTML（`HistoryPageFactory` / `DownloadPageFactory`，靠 `buildPage()` 落临时文件，**没有可外部调起的 scheme**）。建议后续做成按键机原生列表页，而不是复用 WebView 页。
- 待按键机浏览页落地后，正确接法是：浏览页携带当前 URL 调起本页，本页把动作回调给浏览页执行（类注释已写明）。

---

## 5. 验证记录（已通过）

- `:app:assembleLightningPlusDebug`、`:app:compileLightningPlusDebugKotlin`、`:app:lintLightningPlusDebug` 全绿。
- 真机（Q968）实测：首页主题跟随桌面（core 侧同步）、顶部搜索框可聚焦、书签 2×3 宫格 + 历史列表、`↓→↓` 焦点从搜索框 → Change Log → Contact Me → **Google**（`+GRID_COLUMNS`）二维导航正确、LSK 进选项页、四组 14 项齐全、连按 15 次方向键按红线 #6 首尾循环回顶、CENTER 进编辑页、退出确认框（`KeydroidxConfirmDialog`）正常。
- `keyphone_strings.xml` 曾让 Lint 报 25 个 `MissingTranslation` error，已用 `tools:ignore="MissingTranslation"` 收口（该文件是意定中文、不参与上游 19 语言翻译）。

---

## 6. 迭代中踩过的坑（别重复踩）

1. **KSP 偶发失败**：`java.lang.IllegalArgumentException: this and base files have different roots` —— 不是代码问题，**原样重跑一次即可**。
2. 动态建行时给「纯 View」（如分隔线）用 `WRAP_CONTENT` 会被撑满容器剩余高度，导致后续行全 0 高 → 必须显式 `LayoutParams(MATCH_PARENT, dp(6f))`。
3. `⏻`（U+23FB）等生僻字形在真机字体缺字渲染成豆腐块 → 图标一律走 `KeydroidxIcons`。
4. `.bat` 脚本必须纯 ASCII（中文注释会被 GBK 误解码当命令执行）。
5. `gradle.properties` 里的 Clash 代理（7897）没开时必须注释掉；`org.gradle.java.home` 指向 JDK 21。
6. 未获用户明确许可**不要 `git commit`**。

---

## 7. 未完成 / 下一步（按优先级）

### 7.1 【待用户决策·阻塞】minSdk 24 vs 目标机 API 19
两台 240×320 目标机是 Android 4.4.4 / API 19，装不上包。降到 19 需要逐个排查高于 19 的 API 调用与依赖（core 侧本身 `minSdk 19`），并同步 `app/gradle.lockfile`。**动之前先问用户**（`CODEBUDDY.md` 写着「为兼容旧机型下调过，不要再抬高」，但当前值是 24）。

### 7.2 【下一大块】按键机浏览界面
用户明确说「先不考虑打开网址后界面怎么浏览」。现状：打开链接走 `DefaultBrowserActivity`（上游 Compose 界面，纯触屏，方向键无效）。需要按四区规范做按键机浏览页（顶栏显示标题/URL + 进度、内容区 WebView、软键栏），并解决：
- WebView 必须经 `WebViewFactory` + `pool/` 池化获取；
- 浏览页的 LSK 应能调起 `KeyphoneOptionsActivity` 并把 §4 那 7 个「页面」组条目接成实功能（刷新/停止/查找/分享/复制链接/添加书签/添加到主屏，以及上游的 ⋮ 菜单语义 `MenuSelection`）；
- WebView 内的方向键滚动 vs 焦点导航的切换（浏览模式无焦点）。
- 上游 Compose 代码是否保留/替换需要一并决策（`BrowserScreen` / `BrowserOverflowMenu` / `BrowserPresenter` 等）。

### 7.3 【小】其他
- `normalizeInput()` 的搜索 URL 硬编码 Google → 改走 `SearchEngineProvider`（并把「搜索词/网址」判断交给上游 `SearchBoxModel` 之类的既有逻辑）。
- 首页书签宫格与历史列表目前最多 6/3 条，无「查看全部」入口；「书签」选项可进 `about:bookmarks`（WebView 页，非按键机原生）。
- 历史记录 / 下载 建议做成按键机原生列表页（见 §4）。
- **更新 `CODEBUDDY.md`**：开头「当前工作区是上游代码的干净检出…尚未接入生态适配层」「licensee 会在构建期校验」「无 CI」等段落已与现状不符（licensee 已移除、已接入 core、已加 keyphone 包）。新增依赖后 `licensee`/`gradle.lockfile` 相关说明也要改。
- 原型 `prototype/feature-phone-browser.html` 与实现已不同步（见 §3.4）。
