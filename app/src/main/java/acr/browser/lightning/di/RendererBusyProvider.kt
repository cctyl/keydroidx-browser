package acr.browser.lightning.di

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 渲染进程是否很可能繁忙（事前预判）。
 *
 * 浏览器外壳的某些路径（关标签 / 切标签 / 停止加载）会同步调用 WebView，
 * 而 WebView 的这些调用会跨进程等渲染进程。当渲染进程被重型页面脚本占死时，
 * 同步等待会把主线程一并冻住——外壳失灵、按键无响应。
 *
 * 注入到 [acr.browser.lightning.browser.tab.TabsRepository] /
 * [acr.browser.lightning.browser.tab.TabAdapter] 后，这些路径可以在判定繁忙时
 * 走降级流程（先把标签从视图树摘除、列表移除，让用户立即看到效果；WebView 的
 * 跨进程销毁推迟到渲染进程恢复后再做），避免把主线程押进去等。
 */
fun interface RendererBusyProvider {
    fun isRendererBusy(): Boolean
}

/**
 * App 作用域的可变容器：浏览器 Activity 在 `onCreate` 后把当前页面繁忙判定注入进来，
 * 其它（触屏）浏览器 Activity 不设置即恒为 false。外壳层据此决定是否走降级路径。
 */
@Singleton
class RendererBusyHolder @Inject constructor() {
    @Volatile
    var provider: () -> Boolean = { false }
}
