# GEMINI.md

本文件为 Antigravity / Gemini CLI 在此代码仓库（`keydroidx-lightning-browser`）中工作的核心指导上下文。

---

## 1. 项目定位与架构概述

### 1.1 项目定位
- **项目名称**：Lightning Browser（KeydroidX 适配版）
- **包名**：`acr.browser.lightning`（Plus 变体） / `acr.browser.barebones`（Lite 变体）
- **生态背景**：本仓库是经典开源轻量浏览器 Lightning Browser 的上游 Fork，隶属于 **KeydroidX 生态**（`d:/project/keydroidx_ecosystem/`）。
- **目标设备**：诺基亚智能按键手机 / 物理键盘低分辨率 Android 设备（典型分辨率 240×320、320×480，minSdk 24，targetSdk 37）。

### 1.2 核心技术栈与按键机 UI 架构
- **语言与平台**：Kotlin 2.4.20、Java 21 Toolchain、Android Gradle Plugin (AGP) 9.4.1、minSdk 24、compileSdk/targetSdk 37。
- **UI 架构**：
  - **上游触屏 UI**：MVP + Jetpack Compose（`BrowserActivity`、`BrowserScreen`、`BrowserPresenter`、Material3 `BrowserTheme`）。
  - **按键机专用 UI**（`acr.browser.lightning.keyphone`）：
    - **主界面与多窗口宿主**：`KeyphoneBrowserActivity`（内聚 UC 风格顶部动态标签栏、原生九宫格书签与搜索主页 `homeScroll`、WebView 网页容器 `browserFrameHost`、底部三段式软键栏）。
    - **原生主页本质（核心红线）**：按键机的主页是**纯原生 Android View**（搜索栏 + 9 宫格书签 + 历史记录列表），**绝不是 WebView 加载的 HTML 页面**！新建主页窗口或切换到主页时，底层的 WebView 必须保持空白/NoOp 状态，绝对严禁让 WebView 去 `loadUrl("about:home")` 等伪协议（否则会被 Chromium 引擎重定向为 `chrome://home` 并抛出“网页无法打开”崩溃/错误页）。
    - **多窗口（Tab）管理心智**：
      - 顶部标签栏显示所有已打开的窗口（可以同时打开多个 `[ 主页 ]` 或各个 `[ 网页 ]`）。
      - 处于主页状态的窗口，顶栏显示 `主页`，内容区仅展示原生书签与搜索主页；
      - 处于浏览状态的窗口，顶栏显示网页标题，内容区展示 WebView 与地址栏。
      - **新建窗口（1 键菜单 -> 2.新建窗口）**：在标签列表中新建一个主页窗口，并切换到该窗口展示原生主页。
      - **关闭窗口（1 键菜单 -> 1.关闭窗口）**：只要还有其他窗口（无论主页或网页），直接关闭当前窗口并切换到相邻窗口，绝对不退出浏览器；仅在关闭全浏览器最后一个唯一窗口时才弹出退出确认。
      - **按键防穿透**：菜单弹出期间在 `dispatchKeyEvent` 中全模态消费所有按键，并通过 `lastConsumedDownKeyCode` 严格拦截后续的 `ACTION_UP` 抬起事件，彻底杜绝穿透到底层书签。
    - **辅助页面**：`KeyphoneOptionsActivity`（全屏三段式设置与数据选项页）、`KeyphoneTabsFragment`（卡片式标签管理器）、`BrowserHomeActivity`（启动入口兼容代理转发）。
- **依赖注入**：Dagger 2.60.1（`AppComponent` 全局单例、`BrowserComponent` 每 Activity 独立 Scope、`SettingsComponent`）。
- **数据存储**：
  - 手写 `SQLiteOpenHelper` + `DatabaseDelegate`（书签 Bookmark、历史 History、下载 Downloads、广告拦截 Hosts、白名单 AllowList），非 Room。
  - Jetpack DataStore Preferences（`UserPreferencesDataStore`、`PreferenceStore<T>` 类型安全封装）。
- **网络与资源**：OkHttp 5.5、Okio 3.18、Coil 3.6.3（图标/图片加载）、jsoup 1.23.2、Mezzanine 2.5.0（编译期将 `src/main/html/*.html` 和 `src/main/js/*.js` 转为 Kotlin Reader）。
- **测试框架**：JUnit 4、Robolectric 4.17、AssertJ、Turbine（Flow 测试）、Mockingbird（`com.anthonycr.mockingbird` 编译期 Fake 生成，非 Mockito）。

---

## 2. 常用构建、测试与运行命令

所有命令均在项目根目录下通过 Windows PowerShell 或 CMD 执行。

### 2.1 构建命令
- **Debug 构建（推荐 Plus 变体）**：
  ```powershell
  .\gradlew.bat assembleLightningPlusDebug
  ```
- **Debug 构建（Lite 变体）**：
  ```powershell
  .\gradlew.bat assembleLightningLiteDebug
  ```
- **Release 打包（必须走全量 Lint，严禁加 `-x lint`）**：
  ```powershell
  .\gradlew.bat :app:lintLightningPlusRelease :app:assembleLightningPlusRelease
  ```
- **全量构建（包含测试与 Lint）**：
  ```powershell
  .\gradlew.bat build
  ```

### 2.2 质量检查与依赖工具
- **单跑 Lint 检查**：
  ```powershell
  .\gradlew.bat :app:lintLightningPlusDebug
  ```
- **许可证白名单检查（Licensee）**：
  ```powershell
  .\gradlew.bat :app:licenseeAndroidLightningPlusDebug
  ```
- **依赖健康检查（Dependency Analysis）**：
  ```powershell
  .\gradlew.bat buildHealth
  ```
- **依赖块字母序自动排版**：
  ```powershell
  .\gradlew.bat sortDependencies
  ```
- **更新依赖锁文件**（新增/升级依赖后必跑）：
  ```powershell
  .\gradlew.bat --write-locks
  ```

### 2.3 单元测试（Robolectric）
- **运行全部单测**：
  ```powershell
  .\gradlew.bat :app:testLightningPlusDebugUnitTest
  ```
- **单跑指定测试类**：
  ```powershell
  .\gradlew.bat :app:testLightningPlusDebugUnitTest --tests "acr.browser.lightning.adblock.HostsFileParserTest"
  ```
- **单跑指定测试方法**：
  ```powershell
  .\gradlew.bat :app:testLightningPlusDebugUnitTest --tests "acr.browser.lightning.search.suggestions.GoogleSuggestionsModelTest.fetchSuggestions_returnsExpectedList"
  ```

### 2.4 一键脚本（移植自 KeydroidX 生态）
- `build_debug.bat`：构建 Plus Debug APK，输出至 `app\build\outputs\apk\lightningPlus\debug\`。
- `build_install_debug.bat [serial]`：构建并通过 ADB 安装运行至已连接设备（支持多设备并行）。
- `build_release.bat`：全量 Lint + Release 混淆打包并打开输出文件夹。
- `install_debug.bat [serial]`：只安装已有 Debug APK，不重新编译。

### 2.5 启动与调试（ADB）
- **启动应用必须显式指定 activity-alias**（唯一可靠方式，`<serial>` 为设备序列号）：
  ```powershell
  adb -s <serial> shell am start -n acr.browser.lightning/acr.browser.lightning.DefaultBrowserActivityOrange
  ```
  - **严禁使用 `monkey -p acr.browser.lightning -c android.intent.category.LAUNCHER 1`**：Debug 包的 LeakCanary 在同一包内也注册了 `LAUNCHER` 入口（`com.squareup.leakcanary.internal.LauncherActivity`），`monkey` 会随机命中并弹出 LeakCanary 泄漏详情页（重装 APK 后它常自行拉起，`0 Distinct Leaks` 也照弹），该页面既不是浏览器也不是设置页。
  - 只能通过 alias 启动：alias 标记为 `exported="true"`；而启动链上的 `keyphone.BrowserHomeActivity` 与 `keyphone.KeyphoneBrowserActivity` 均为 **not exported**，直接 `am start` 会抛 `SecurityException: Permission Denial: ... not exported from uid ...`。
  - Lite 变体启动组件：`acr.browser.barebones/acr.browser.barebones.DefaultBrowserActivityBlue`（Blue alias 默认 `enabled="false"`，需先在应用内「更换应用图标」启用）。
- 安装 Debug APK：
  ```powershell
  adb -s <serial> install -r app\build\outputs\apk\lightningPlus\debug\app-lightningPlus-debug.apk
  ```
  重装后若停留在 LeakCanary 页，先执行 `adb -s <serial> shell input keyevent KEYCODE_BACK` 退回。
- 截屏：Windows PowerShell 下 `adb exec-out screencap -p > x.png` 会把二进制流按文本重定向而损坏 PNG，必须用 `adb -s <serial> shell screencap -p /sdcard/x.png` 后 `adb -s <serial> pull /sdcard/x.png .`。
- 日志：复现前先 `adb -s <serial> logcat -c` 清空缓冲区，再用 `adb -s <serial> logcat -d -v time -s KeyphoneBrowser` 抓取按键、标签页与主页切换日志。

---

## 3. 按键机（KeydroidX）开发规范与红线

在 `acr.browser.lightning.keyphone` 模块及任何按键机适配代码中，必须严格遵守以下生态规则：

1. **基类继承**：
   - 所有按键机 Activity 必须继承 `io.github.cctyl.nokia.keycore.ui.KeydroidxBaseActivity`。
   - 所有列表 Fragment 必须继承 `io.github.cctyl.nokia.common.ui.page.KeydroidxListPageFragment` 或 `KeydroidxPageFragment`。
2. **按键处理规范（严禁硬编码 KeyCode）**：
   - 必须通过 `KeydroidxKeyAction` 枚举匹配按键（如 `KeydroidxKeyAction.CONFIRM`、`NAV_LEFT`、`SOFT_LEFT` 等），绝对禁止手写 `KeyEvent.KEYCODE_*`。
   - 如果消费了 KeyDown，必须保证同按键的 KeyUp / Repeat 事件得到一致处理。
   - 对于非长按的执行类动作，必须过滤 key repeat 事件（防止按键连击）。
3. **焦点与滚动**：
   - 列表焦点导航必须使用 `KeydroidxListFocusHelper` 管理首尾循环与高亮。
   - 动态构建包含 `TextView` 的列表项时，高度必须使用 `WRAP_CONTENT` 配合 `setMinimumHeight()`，禁止写死固定 `dp`（防大字号裁字）。
4. **弹窗规范**：
   - 确认/取消类对话框统一走 `KeydroidxConfirmDialog`，避免原生触屏 Dialog 导致的物理按键失焦。
5. **设计 Token 与资源**：
   - 字体/字号统一走 `KeydroidxTheme` 和 `keydroidx_font_*` 语义 Token。
   - 图标统一使用 `KeydroidxIcons`（Material 矢量图标）。
6. **异常日志**：
   - 所有的 `catch` 分支必须显式调用 `KeydroidxLog.e(...)` 记录日志，严禁空 catch。

---

## 4. Gradle 与环境避坑指南

1. **`.bat` 脚本纯 ASCII 原则**：
   - Windows cmd.exe 解析 `.bat` 默认走 OEM 代码页（GBK/936），UTF-8 中文字符会导致行尾截断或语法解析崩溃。所有 `.bat` 文件严禁包含非 ASCII 字符，中文说明应写在文档中。
2. **依赖仓库顺序**：
   - 仓库配置在 `settings.gradle.kts` 中。必须保持 **官方源在前（google, mavenCentral）、国内镜像在后兜底**，防止部分镜像元数据残缺（只有 POM 无 jar）导致的依赖解析死锁。
3. **项目隔离与路径访问**：
   - 开启了 `org.gradle.unsafe.isolated-projects=true`，构建脚本中禁止跨 Project 调用（如 `rootProject.file(...)`），应使用 `File(layout.projectDirectory.asFile.parentFile, "...")` 相对路径。
4. **HTML / JS 静态资源修改**：
   - `src/main/html/` 和 `src/main/js/` 由 Mezzanine KSP 插件编译处理，修改后必须执行 clean 或重新 Gradle 编译生成新的 Reader 类。
5. **WebView 创建约束**：
   - 所有 `WebView` 实例必须通过 `WebViewFactory` 和 `pool/` 对象池获取，禁止直接 `WebView(context)` 实例化。
6. **签名凭证安全**：
   - Release 凭证只读取环境变量或本地未提交的 `local.properties`，严禁提交任何 keystore 口令到版本库。

---

## 5. 协作与 Git 规范

- **自动部署要求**：每次完成功能开发或 Bug 修复并通过编译构建后，**必须自动执行安装脚本**（如 `python install_debug.py`）将最新 Debug APK 部署至已连接的手机设备上。
- **交互语言**：始终以 **中文** 回答用户。
- **Git Commit 规范**：Commit Message 使用 **英文**，遵循 Conventional Commits（如 `feat: ...`, `fix: ...`, `refactor: ...`）。
- **提交权限**：除非用户明确指令（如 "Commit the change"），切勿擅自执行 `git commit` 或 `git push`。
