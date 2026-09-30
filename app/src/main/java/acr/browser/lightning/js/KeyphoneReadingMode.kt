package acr.browser.lightning.js

import com.anthonycr.mezzanine.FileStream

/**
 * 按键机阅读模式脚本：正文提纯、下一页识别、滚动自动拼页与页数裁剪。
 *
 * 脚本入口是 IIFE，注入后立即尝试进入阅读模式，`evaluateJavascript` 的返回值为
 * `"ok"` / `"empty"` / `"error"`；退出调用 `window.__keyphone_rm__.exit()`。
 * 运行期事件经 `window.__keyphoneReadingBridge__.onEvent(type, payload)` 回传。
 *
 * 注意：`src/main/js/KeyphoneReadingMode.js` 由 Mezzanine 在编译期读取并生成字符串，
 * 改脚本后必须重新构建才生效；脚本内容保持纯 ASCII（中文关键词用 \uXXXX 转义）。
 */
@FileStream("src/main/js/KeyphoneReadingMode.js")
interface KeyphoneReadingMode {

    fun provideJs(): String

}
