# Keydroidx Browser（原键浏览器）

**面向按键功能机的浏览器**：基于 [Lightning Browser](https://github.com/anthonycr/Lightning-Browser) 的下游分叉，
为诺基亚功能机与低分辨率 Android 设备（240×320 / 320×480）重做整套交互，
是 keydroidx 生态中的浏览器组件。

> 上游 Lightning Browser 由 **Anthony Restaino** 创建并以 **MPL 2.0** 开源。
> 本项目保留其内核与特性，交互层完全重做；继续以 MPL 2.0 开源，完整致谢见文末。

---

## 为什么要做这个项目

功能机上的浏览器，几乎都是把"触屏逻辑"硬塞进方向键：光标跑不动、页面点不准、菜单要翻三层。
这个分叉不是追求"更快的浏览器"，而是把交互从零重做成**按键优先**：

- 方向键驱动光标 / 整屏翻页，数字键直接执行动作，左右软键就是返回与菜单；
- 没有地址栏时也能一眼看清自己在哪、在加载什么；
- 弱网 / 小内存环境下优先保证"能读完一篇文章"，而不是"能开十个标签"。

## 主要特性

### 按键机交互

- **光标层**：方向键在网页上移动可视光标，确认键点击，长按连续移动；
- **数字键快捷动作**：常用操作一键直达（如 `*` 出菜单、`6` 进入阅读模式）；
- **顶部标签栏 + 全屏状态**：网页态自动隐藏地址栏，阅读态彻底全屏；
- **站点按键映射**：可针对特定站点配置按键行为（`KeyphoneSiteKeyStore`）；
- **网址守卫**：白名单 / 拦截列表（`KeyphoneGuardListActivity`、`KeyphoneUrlGuard`）。

### 阅读模式（本项目重点重做的部分）

- **正文提取**：在源页面上做一次只读提取（正文 HTML、下一章链接、章节标题），
  随后导航到一个干净页面渲染，**源页面连同它的脚本、定时器、埋点一起被丢弃**；
- **连续阅读**：章节文字按顺序**追加**到页面尾部（不拼接站点 DOM），滚动不中断、
  章与章之间没有跳变，接缝处是一条章节标题分隔；
- **有界窗口**：按**文档高度**裁剪，超过 40k px（约 2~3 章）就丢弃最早读过的章节。
  实测该设备上文本文档超过约 10 万 px 时，沙箱渲染进程会被系统直接杀掉，所以窗口不能按章数给；
- **丢失自愈**：阅读页被 WebView 池回收 / Activity 重建后，检测到阅读脚本消失或
  WebView 离开阅读页，自动回到最后读到的章节并重新进入阅读；
- **按键**：`1` / `3` 调整字号，`7` 轮换背景（米白 / 护眼黄 / 护眼绿 / 夜间灰 / 夜间纯黑），
  方向键整屏翻页并跨章连续。

### 其他

- 书签、历史、多搜索引擎、无痕模式（继承上游）；
- 下载、设置项、帮助页与站点守卫列表；
- **按天落盘的日志**（`Android/data/<包名>/files/log/yyyyMMdd.log`，保留 7 天）与
  崩溃现场记录，便于在无 adb 环境复现问题。

## 与上游的关系

| 层 | 处理方式 |
|---|---|
| MVP 内核（`BrowserPresenter` / `BrowserUiEvent` / 数据层） | **原样复用** |
| UI | 上游的 Compose `BrowserScreen` 在按键机上**不使用**，改为原生 View + keydroidx SDK UI |
| 按键机适配层 | `acr.browser.lightning.keyphone` 包，新增 |
| 阅读模式 | 由上游的就地重排改为"提取 + 干净页 + 连续追加"，见 `docs/` |

因此上游的书签、历史、搜索引擎、无痕模式等能力仍然可用，只是入口与呈现方式重新设计过。

## 构建

```bash
# 全功能版：acr.browser.lightning
./gradlew assembleLightningPlusDebug

# 精简版：acr.browser.barebones
./gradlew assembleLightningLiteDebug
```

Windows 下用 `gradlew.bat`。产物位于 `app/build/outputs/apk/<flavor>/<buildType>/`。

当前版本 **5.1.0**（versionCode 102）。

## 目录结构

```
app/src/main/java/acr/browser/lightning/
├── keyphone/              # 按键机适配层（Activity / 光标 / 菜单 / 阅读 / 守卫 / 历史 …）
├── browser/               # 上游 MVP：presenter、tab、搜索、书签等
├── js/                    # Mezzanine 生成的 JS 常量
└── ...
app/src/main/js/
└── KeyphoneReadingMode.js # 阅读模式脚本（提取 / 连续追加 / 窗口裁剪 / 进度上报）
docs/                      # 设计与故障排查文档
```

## 文档

- `docs/reading-mode-render-isolation-plan.md` —— 阅读模式的方案演进、真机数据与实现记录
- `docs/reading-mode-blackscreen-diagnostics.md` —— 黑屏 / 卡屏的排查方法与现场采样
- `docs/reading-mode-log-persistence.md` —— 日志落盘与崩溃现场保留策略

## 已知限制

- 阅读窗口受设备内存限制：本文档约 10 万 px 会触发渲染进程回收（已在窗口裁剪中规避）；
- 阅读页是 `data:` 页面，`WebView.getUrl()` 返回 `about:blank`，需要真实地址的地方统一走
  `currentRealUrl()`；
- 仓库无 CI 工作流，仅包含单元测试。

## 许可证

本项目继承上游的 **Mozilla Public License 2.0**：

```
Copyright 2014 Anthony Restaino

Lightning Browser

   This Source Code Form is subject to the terms of the
   Mozilla Public License, v. 2.0. If a copy of the MPL
   was not distributed with this file, You can obtain one at

   http://mozilla.org/MPL/2.0/
```

分发本项目的修改版本时，请同样保留上述声明并以 MPL 2.0 提供对应源码。

## 致谢

这个项目能存在，首先是因为有人愿意把一款完成度很高的浏览器完整开源出来。

### 特别感谢

- **[Anthony Restaino](https://github.com/anthonycr)** —— Lightning Browser 的作者。
  书签、历史、标签管理、搜索引擎体系、无痕模式、下载、MVP 内核与整套架构，都是他一个人
  长期维护的成果。本项目的按键机交互与阅读模式是建立在这套内核之上的二次设计，
  没有上游就没有这个分叉。
- **Lightning Browser 的所有贡献者** —— 包括提交补丁、翻译、复现问题与撰写文档的社区成员，
  上游的每一次修复都直接惠及本分叉。

### 依赖的开源项目

- **Kotlin** 与 **Kotlin Coroutines** —— 语言与并发模型；
- **AndroidX**（AppCompat、Fragment、Lifecycle、DataStore、Compose 等）—— 基础组件；
- **[Mezzanine](https://github.com/anthonycr/Mezzanine)**（同由 Anthony Restaino 开发）——
  在构建期把 JS 文件嵌入 APK；
- **Dagger** —— 依赖注入；
- **keydroidx SDK / core** —— 按键事件分发、UI 组件与排版规范。

感谢以上项目的作者与维护者，他们的工作让这个分叉可以把精力集中在"按键机体验"本身。
