package acr.browser.lightning.js

import com.anthonycr.mezzanine.FileStream

/**
 * 按键机光标滚动目标脚本：把「贴边滚动」的对象从文档根改成**光标下的元素**。
 *
 * 脚本安装 `window.__keyphone_scroll__`（幂等），核心入口：
 * `by(x, y, dx, dy, viewW, viewH, resolveAgain)`，
 * 返回 `scroll` / `end` / `consume` / `doc` 四种状态（见脚本头部注释）。
 *
 * 注意：`src/main/js/KeyphoneScroll.js` 由 Mezzanine 在编译期读取并生成字符串，
 * 改脚本后必须重新构建才生效；脚本内容保持纯 ASCII。
 */
@FileStream("src/main/js/KeyphoneScroll.js")
interface KeyphoneScroll {

    fun provideJs(): String
}
