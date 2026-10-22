package acr.browser.lightning.js

import com.anthonycr.mezzanine.FileStream

/**
 * 按键机阅读模式脚本：正文提纯 + 下一章识别 + 同源同步取章。
 *
 * 显示层在原生 `KeyphoneReaderView` 上（见 docs/reading-mode-native-textview-plan.md），
 * 本脚本只剩两个用途：
 * - 在**源页面**上被注入并调用 `extract()`，一次性只读提取当前章正文
 *   （宿主拼成 `readerExtractScript()` 注入，返回值是 JSON 字符串）；
 * - 在**取章外壳**（`loadDataWithBaseURL(章节URL, 外壳)`）里 boot 后，
 *   由宿主调 `fetchNext()` 同步 XHR 取下一章，返回值同样是 JSON 字符串。
 *
 * 脚本里没有 setInterval / rAF / 渲染 / 心跳，静止阅读时渲染进程完全空闲。
 *
 * 注意：`src/main/js/KeyphoneReadingMode.js` 由 Mezzanine 在编译期读取并生成字符串，
 * 改脚本后必须重新构建才生效；脚本内容保持纯 ASCII（中文关键词用 \uXXXX 转义）。
 */
@FileStream("src/main/js/KeyphoneReadingMode.js")
interface KeyphoneReadingMode {

    fun provideJs(): String

}
