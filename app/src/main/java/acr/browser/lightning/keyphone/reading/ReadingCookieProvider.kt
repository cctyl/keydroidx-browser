package acr.browser.lightning.keyphone.reading

import acr.browser.lightning.concurrency.CoroutineDispatchers
import android.webkit.CookieManager
import io.github.cctyl.nokia.common.log.KeydroidxLog
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 取章节 Cookie 的来源。
 *
 * 抽成接口是为了单测：生产实现走 [CookieManager]（必须在主线程调），
 * 单测传一个假的 `(url) -> "k=v"`，于是 `ReadingChapterFetcher` 可以在纯 JVM 里跑。
 */
interface ReadingCookieProvider {

    /** 该 URL 的 Cookie 头（`a=1; b=2`），没有则 null */
    suspend fun cookieFor(url: String): String?
}

/**
 * 生产实现：读 WebView 的全局 Cookie 存储。
 *
 * `CookieManager` 是**主线程 API**，在 IO 线程直接调会抛异常 —— 这里显式切主线程取一次，
 * 取完再切回调用方上下文（`withContext` 会切回去）。这样取到的就是用户浏览时积累的那套
 * Cookie，与旧「用 WebView 同步 XHR 取章」完全同源，不会丢登录态。
 */
@Singleton
class WebViewCookieProvider @Inject constructor(
    private val dispatchers: CoroutineDispatchers
) : ReadingCookieProvider {

    override suspend fun cookieFor(url: String): String? = withContext(dispatchers.main) {
        try {
            CookieManager.getInstance().getCookie(url)
        } catch (t: Throwable) {
            KeydroidxLog.w(TAG, "读取 Cookie 失败 url=$url", t)
            null
        }
    }

    private companion object {
        const val TAG = "KeyphoneReadingFetch"
    }
}
