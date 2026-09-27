# CODEBUDDY.md This file provides guidance to CodeBuddy when working with code in this repository.

## 项目定位

Lightning Browser（`acr.browser.lightning`）的上游 fork，位于 keydroidx 生态（`d:/project/keydroidx_ecosystem/`）内，目标平台是诺基亚功能机 / 低分辨率 Android（240×320、320×480）。

当前工作区是**上游代码的干净检出**（`origin` = github.com/anthonycr/Lightning-Browser，基线 commit `a30ebe09`）：全仓搜索 `keydroidx` 无任何命中，即尚未接入生态适配层。一旦开始做诺基亚适配（Material Icons 矢量图标、物理软键映射、统一日志、字号 Token 等），必须遵守：

- `../keydroidx-core/docs/NOKIA_DEVELOPMENT_RULES.md`（按键处理、catch 必打日志、图标规范、排版硬规则）
- `../keydroidx-core/docs/spec/typography-and-font-spec.md`、`../keydroidx-core/docs/spec/responsive-layout-spec.md`（字号 / 分辨率唯一事实源）

工作区状态注意：相对上游删除了 `LICENSE`、`CHANGELOG.md`、`CONTRIBUTING.md`、`.github/workflows/action.yml`、`launcher_icon*.png`（git status 标为 deleted，磁盘上已不存在），因此 `.github` 目录不存在（**无 CI 工作流**），`README.md` 中的徽章/图标链接已失效。没有 `androidTest`，只有单元测试。

## 常用命令

Windows PowerShell，在包含 `gradlew.bat` 的项目根目录执行。

构建（变体名 = 两个 flavor × 两个 buildType，任务名必须带变体前缀）：

- `.\gradlew.bat assembleLightningPlusDebug` — Debug APK，Plus 变体（`acr.browser.lightning`）。Lite 为 `assembleLightningLiteDebug`（`acr.browser.barebones`）。`assembleDebug` 不会触发 Lint。
- `.\gradlew.bat :app:lintLightningPlusDebug` — 只跑 Lint。改完涉及 API 调用的代码后必跑。
- `.\gradlew.bat :app:lintRelease assembleRelease` — Release 打包（先全量 Lint）。**禁止加 `-x lint`**：生态内 minSdk 低（本项目 minSdk=24），越级 API 在旧机上是运行时闪退，Lint 的 `NewApi` 是唯一静态防线。
- `.\gradlew.bat build` — 全量构建 + 单测 + Lint（首次构建 / 提交前建议跑）。
- `.\gradlew.bat buildHealth` — dependency-analysis 插件的依赖健康检查（冗余/未声明依赖）。
- `.\gradlew.bat sortDependencies` — square sort-dependencies 插件，按字母序重排依赖块。
- `.\gradlew.bat :app:licensee` — licensee 校验依赖许可证白名单（Apache-2.0 / BSD-3-Clause / jsoup），产物 `app/licensee/artifacts.json`。

打包与签名（已按 `../android-gradle-build/SKILL.md` 配好）：

- `app/test.jks` 是生态统一测试密钥（来源 `../android-gradle-build/test.jks`），已被 `.gitignore` 的 `*.jks` 规则排除，**不要提交**。
- 签名凭据只从**环境变量**（优先）或**根目录 `local.properties`**（同样被 `.gitignore` 忽略）读取，键名 `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`，由 `app/build.gradle.kts` 顶部的 `signingProperty()` 读取。**严禁把口令写进任何会被 git 跟踪的文件**。凭据缺失时只有 release 打包会失败，debug 不受影响。
- `gradle.properties` 固定了代理（Clash 默认 7897，**代理没开时必须注释掉这四行**）和 `org.gradle.java.home`（JDK 21，配合 `jvmToolchain(21)` + AGP 9.4.1，避免 Gradle 再去下载 toolchain）。
- Gradle 发行版本体走腾讯云镜像（见 `gradle/wrapper/gradle-wrapper.properties`）。
- 仓库顺序是**官方源在前、腾讯/阿里镜像在后兜底**，不要为了提速把镜像移到前面：镜像存在「有 POM 但没有 jar」的残缺条目（实测 `dev.zacsweers.moshix:moshi-sealed-runtime:0.37.0` 在腾讯云镜像上只有 POM），而 Gradle 一旦在某个仓库拿到模块元数据，就只用该仓库取 jar、**不会回落到后面的仓库**，镜像前置会直接导致构建失败。
- 该 SKILL 还是 Gradle 卡住 / 依赖下不动时的排查清单（镜像、代理、wrapper 镜像、JDK 选 OpenJDK/Temurin/JBR 而非 GraalVM）。

测试（JUnit4 + Robolectric，无 androidTest）：

- `.\gradlew.bat :app:testLightningPlusDebugUnitTest` — 跑某变体的全部单测（共 4 个变体，任务名带后缀）。
- 单个测试类：`.\gradlew.bat :app:testLightningPlusDebugUnitTest --tests "acr.browser.lightning.adblock.HostsFileParserTest"`
- 单个测试方法：`... --tests "acr.browser.lightning.search.suggestions.GoogleSuggestionsModelTest"`

测试约定：Robolectric 用 `@RunWith(RobolectricTestRunner::class)` + `@Config(application = TestApplication::class, sdk = [SDK_VERSION])`（`SDK_VERSION` 见 `app/src/test/.../Constants.kt`）；断言用 AssertJ，Flow 断言用 Turbine，协程用 `kotlinx-coroutines-test` + `FakeCoroutineDispatchers`；**mock 用 Mockingbird（`com.anthonycr.mockingbird.core.fake` / `verify`，由 Gradle 插件在编译期生成，不是 Mockito）**。手写替身命名 `Fake*`。`app/src/test/.../TestUtils.kt` 的 `unimplemented()` 用于占位未实现方法。

## 架构

### 整体：MVP + Compose 混合（迁移已基本完成）

`browser/BrowserActivity.kt` 是抽象基类，实现 `BrowserContract.View`。`onCreate` 里先 new 出两个 `FrameLayout`（browserFrame / customFrame），用 `injector.browserComponentBuilder()` 构建**每个 Activity 独立的 Dagger 子组件**并 `inject(this)`，然后 `setContent {}` 全量 Compose 渲染。

状态流：`BrowserPresenter.state: StateFlow<BrowserViewState>` → Activity 内的 `collectAsMutableState` 扩展把每个字段转成独立 `mutableStateOf`（`BrowserComposeState`，`@Stable`，逐字段更新以减少重组）→ `BrowserScreen(...)`。`BrowserScreen` 按 `TabConfiguration` 分派到 `DesktopTabs` / `DrawerTabs` / `BottomTabs`；WebView 属于 View 体系，通过 `AndroidView` 宿主那两个 `FrameLayout` 嵌入 Compose。

反向事件：UI 产出密封接口 `BrowserUiEvent`（`acr/browser/lightning/BrowserUiEvent.kt`）→ `presenter.onEvent(...)`。导航由 `BrowserNavigator`（实现 `BrowserContract.Navigator`）负责。`BrowserContract.kt` 里还集中定义了 `View`/`Model`/`Navigator` 接口、各种枚举与 `Action`。入口 Activity：`DefaultBrowserActivity`、`IncognitoBrowserActivity`（后者跑在独立 `:incognito` 进程）。

### DI（Dagger）

- `di/AppComponent.kt`：`@Singleton`，Builder 绑定 `Application` / `BuildInfo` / `@FullIncognitoMode`；含 `AppModule`（提供 OkHttp 多客户端、Dispatcher、AdBlocker、Mezzanine 生成的各 Reader 等）+ `AppBindsModule`（绑定各 Repository 接口）。
- 子组件：`BrowserComponent`（`@BrowserScope`，`BrowserModule` + `BrowserBindsModule`，BindsInstance 传入 activity / browserFrame / customFrame / intent / `@IncognitoMode`）、`SettingsComponent`（`@SettingsScope`）。
- `DiExtensions.kt`：`Context.injector` 即 `(applicationContext as BrowserApp).applicationComponent`，这是取注入器的唯一入口。`BrowserApp.onCreate` 里构建 `applicationComponent`。

### 数据层

- `database/`：**手写 `SQLiteOpenHelper`，不是 Room / Realm**，`databaseDelegate()` 缓存实例；分表为 Bookmark / History / Downloads / Hosts / AdBlockAllowList。
- `preference/`：**DataStore Preferences**（`PreferenceDataStoreFactory` + `SharedPreferencesMigration` 迁移旧 SP）。`UserPreferencesDataStore.kt` 是所有偏好项的唯一声明处，类型化封装 `PreferenceStore<T>`（`NonNull` / `Nullable` / `Enum`，枚举走 `IntEnum`，无反射）。
- tab 与池化：`browser/tab/` 的 `TabModel` / `TabAdapter` / `TabFactory` / `TabsRepository`（实现 `BrowserContract.Model`）；`pool/` 的 `ObjectPool` 池化复用 WebView，WebView 一律经 `WebViewFactory` 创建，不要直接 `new`。
- `search/`：`SearchEngineProvider` + 各引擎 + OkHttp 网络建议 + `SuggestionsModel`（书签 : 历史 : 建议 = 2:2:1 合并排序）。

### 引擎与注入资源层

- `browser/view/`：触摸 / 长按手势封装；`browser/webrtc/`：`WebRtcPermissionsModel` / `View`（权限与资源授予流程）；`adblock/`：`BloomFilterAdBlocker` + `HostsFileParser` + BloomFilter + allowlist（`adblock/allowlist/`）；`ssl/`、`useragent/`、`favicon/`、`icon/`、`graphics/`、`device/`、`shortcuts/`、`migration/Cleanup`（启动时清理）。
- `html/` + `js/`：用 **Mezzanine**（`@FileStream` 注解 + KSP）把 `src/main/html/*.html`、`src/main/js/*.js` 在编译期生成为 Kotlin Reader；各 `*PageFactory` 再结合 jsoup 动态拼出 `about:bookmarks` / `list` / `history` / `download` / `homepage` 页面。**改 html/js 后必须重新构建才生效**；`@GeneratedHtmlDir` 限定符在 `AppModule` 中提供生成目录的 `ThreadSafeFileProvider`，这些页面靠它落临时文件。

### Compose 与设置页

- `compose/`：`Theme.kt`（Material3 `BrowserTheme` / `ColorScheme`）与 `Color` / `StatusBar` / `Transitions`。
- `browser/compose/`：全部浏览器界面（搜索栏、状态栏、溢出菜单、弹窗、查找、进度条、三种 tab 布局）与 `sheets/`（页面工具、长按、书签、SSL 信息等底部面板）。
- `settings/`：设置页同样 Compose 化 —— `framework/`（通用控件）+ `screens/`（各设置屏）+ `navigation/` + `SettingsScreenStateProvider`，宿主是 `settings/activity/SettingsActivity`。

### 变体（Flavor）

flavor 维度 `capabilities` 有两个，且**只覆盖 `AndroidManifest.xml`**：

- `lightningPlus` → `src/LightningPlus/`，`acr.browser.lightning`，`FULL_VERSION = true`，橙图标。
- `lightningLite` → `src/LightningLite/`，`acr.browser.barebones`，`FULL_VERSION = false`，蓝图标。
- 启动图标是 `<activity-alias>`（Orange enabled / Blue disabled，「更换应用图标」靠切换 alias 的 enabled 实现）。
- `FULL_VERSION` 只被 `BrowserApp.kt`（`createBuildInfo()` → `BuildInfo.isPlus`）与 `AdBlockSettingsScreen.kt` 读取。`src/debug/`、`src/release/` 仅覆盖 `LeakCanaryUtils.kt`。

### Gradle 机制（改依赖前必看）

- 仓库只在 `settings.gradle.kts` 声明（`FAIL_ON_PROJECT_REPOS`，google / mavenCentral，无镜像）。
- `gradle.properties` 开启了 configuration-cache、isolated-projects、parallel、KSP2、incremental → 构建慢或行为诡异时先怀疑这几项。
- **依赖锁定**：`activateDependencyLocking()` + `app/gradle.lockfile`，新增 / 升级依赖必须同步更新 lockfile（`--write-locks`），否则构建失败。
- `sort-dependencies`（依赖块字母序）、`dependency-analysis`（声明与使用一致）、`licensee`（许可证白名单）都会在构建期让不规范改动失败。
- `jvmToolchain(21)`；`compileSdk` / `targetSdk` 37、`buildToolsVersion` 37.0.0、`minSdk 24`（为兼容旧机型下调过，不要再抬高）。
- 单测 `jvmArgs` 里有大量 `--add-opens`（Robolectric + JDK 21 必需），不要删。
- `org.gradle.unsafe.isolated-projects=true`：构建脚本里**禁止跨工程访问 Project API**（典型如 `rootProject.file(...)`、`project(":x")`），配置阶段会直接失败。要读工程外的文件就用 `java.io.File` 基于 `layout.projectDirectory` 自行拼路径。
- 依赖锁定 + configuration-cache 已开启：改依赖后要用 `--write-locks` 更新 `app/gradle.lockfile`；遇到诡异的陈旧配置可先删 `build/reports/configuration-cache` 或加 `--no-configuration-cache` 复现。

### 启动流程

Manifest 的 activity-alias → `DefaultBrowserActivity` → `BrowserActivity.onCreate`。`BrowserApp.onCreate` 顺序：StrictMode（debug）→ 判定 `:incognito` 进程并 `setDataDirectorySuffix("incognito")` → 安装全局异常处理器（debug 写崩栈）→ 构建 `DaggerAppComponent` → `injector.inject(this)` → 协程跑 `Cleanup` 与首次书签导入 → debug 下装 LeakCanary 与 WebView 调试。

## 关键约定与坑

- WebView 必须经 `WebViewFactory` + `pool/` 池化获取；新 UI 优先写 Compose，需要嵌 View 时才用 `AndroidView`。
- 诺基亚适配时：`catch` 分支必须落日志；菜单/弹窗按键必须接入 `KeydroidxKeyBinding`（禁止硬编码 keyCode）；消费了 DOWN 就必须消费同键 UP/REPEAT；执行类动作要过滤 key repeat；字号只能用 `keydroidx_font_*` 语义 Token；Java 动态构建的含 TextView 列表行用 `WRAP_CONTENT` + `setMinimumHeight()` 而非固定行高（大字号会裁字）。
- 上游无 CI；`.github` 已被删除。不要重新引入 `-x lint` 的构建方式。
- 未经用户明确许可，不要执行 `git commit`。
