package acr.browser.lightning.browser.tab

import acr.browser.lightning.R
import acr.browser.lightning.adblock.AdBlocker
import acr.browser.lightning.adblock.allowlist.AllowListModel
import acr.browser.lightning.browser.tab.settings.TabSettings
import acr.browser.lightning.concurrency.TabCoroutineScope
import acr.browser.lightning.constant.SCHEME_BLANK
import acr.browser.lightning.databinding.DialogAuthRequestBinding
import acr.browser.lightning.databinding.DialogSslWarningBinding
import acr.browser.lightning.di.FaviconCacheDir
import acr.browser.lightning.di.GeneratedHtmlDir
import acr.browser.lightning.extensions.resizeAndShow
import acr.browser.lightning.js.TextReflow
import acr.browser.lightning.keyphone.KeyphoneGuardStore
import acr.browser.lightning.keyphone.KeyphoneUrlGuard
import acr.browser.lightning.log.Logger
import acr.browser.lightning.ssl.SslState
import acr.browser.lightning.ssl.SslWarningPreferences
import acr.browser.lightning.utils.ThreadSafeFileProvider
import acr.browser.lightning.utils.isSpecialUrl
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.os.SystemClock
import android.view.LayoutInflater
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.webkit.WebViewAssetLoader.InternalStoragePathHandler
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import kotlin.math.abs

/**
 * A [WebViewClient] that supports the tab adaptation.
 */
class TabWebViewClient @AssistedInject constructor(
    private val adBlocker: Deferred<@JvmSuppressWildcards AdBlocker>,
    private val allowListModel: AllowListModel,
    private val urlHandler: UrlHandler,
    @Assisted private val headers: Map<String, String>,
    private val sslWarningPreferences: SslWarningPreferences,
    private val textReflow: TextReflow,
    private val logger: Logger,
    @FaviconCacheDir private val faviconCacheDirThreadSafeFileProvider: ThreadSafeFileProvider,
    @GeneratedHtmlDir private val generatedHtmlDirThreadSafeFileProvider: ThreadSafeFileProvider,
    @Assisted("cache") private val cacheStoragePathHandler: InternalStoragePathHandler,
    @Assisted("files") private val filesStoragePathHandler: InternalStoragePathHandler,
    @Assisted private val tabCoroutineScope: TabCoroutineScope,
    @Assisted private val tabSettings: TabSettings,
) : WebViewClient() {

    /**
     * The factory for constructing the client.
     */
    @AssistedFactory
    interface Factory {

        /**
         * Create the client.
         */
        fun create(
            headers: Map<String, String>,
            @Assisted("cache") cacheStoragePathHandler: InternalStoragePathHandler,
            @Assisted("files") filesStoragePathHandler: InternalStoragePathHandler,
            tabCoroutineScope: TabCoroutineScope,
            tabSettings: TabSettings,
        ): TabWebViewClient
    }

    private val cache by lazy {
        runBlocking {
            faviconCacheDirThreadSafeFileProvider.file()
        }
    }

    private val files by lazy {
        runBlocking {
            generatedHtmlDirThreadSafeFileProvider.file()
        }
    }

    /**
     * Emits changes to the current URL.
     */
    val urlSharedFlow: MutableSharedFlow<String> = MutableSharedFlow()

    /**
     * The current SSL state of the page.
     */
    val sslStateFlow: MutableStateFlow<SslState> = MutableStateFlow(SslState.None)

    /**
     * Emits changes to the can go back state of the browser.
     */
    val goBackSharedFlow: MutableSharedFlow<Boolean> = MutableSharedFlow()

    /**
     * Emits changes to the can go forward state of the browser.
     */
    val goForwardSharedFlow: MutableSharedFlow<Boolean> = MutableSharedFlow()

    /**
     * Emit when the tab has started loading a new page
     */
    val startedSharedFlow = MutableSharedFlow<Unit>()

    /**
     * Emit when the tab has finished rendering its content.
     */
    val finishedSharedFlow = MutableSharedFlow<Unit>()

    /**
     * The latest search query entered by the user, or the latest loaded URL, whichever event
     * happened later.
     */
    var searchQuery: String = ""

    /**
     * The text selection in the search query, either the start and end of the selection if the
     * values are different, or the cursor position if they are the same.
     */
    var searchQuerySelection: Pair<Int, Int> = Pair(0, 0)

    private var currentUrl: String = ""
    private var isReflowRunning: Boolean = false
    private var zoomScale: Float = 0.0F
    private var urlWithSslError: String? = null

    /** 按键机提交兜底拦截：用户主动导航的一次性放行标记（精确 URL 或任意一次）。 */
    private var explicitAllowedUrl: String? = null
    private var allowAnyNextCommit = false

    /** 用户主动导航（地址栏 loadUrl）放行：下一次该 URL 的提交不做站内比对。 */
    fun allowExplicitNavigation(url: String?) {
        synchronized(this) {
            if (url == null) {
                allowAnyNextCommit = true
            } else {
                explicitAllowedUrl = url
            }
        }
    }

    /** 只窥视不消费（shouldInterceptRequest 在 IO 线程调用，消费统一在 onPageStarted）。 */
    private fun isNavigationPreAllowed(url: String): Boolean =
        synchronized(this) { allowAnyNextCommit || url == explicitAllowedUrl }

    private fun consumeExplicitAllow(url: String): Boolean {
        synchronized(this) {
            if (allowAnyNextCommit || url == explicitAllowedUrl) {
                explicitAllowedUrl = null
                allowAnyNextCommit = false
                return true
            }
            return false
        }
    }

    /** 最近的主帧请求环形缓冲（时间戳,URL）：用于 302 链的事后溯源。 */
    private val recentMainFrameRequests = ArrayDeque<Pair<Long, String>>()
    private val lastRedirectorNotifyAt = HashMap<String, Long>()

    /** 连续回退计数（5s 窗口）：超限熔断回空白页，防重定向环。 */
    private var bounceCount = 0
    private var lastBounceAt = 0L

    private fun recordMainFrameRequest(url: String) {
        synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            recentMainFrameRequests.addLast(now to url)
            while (recentMainFrameRequests.size > 8) {
                recentMainFrameRequests.removeFirst()
            }
        }
    }

    /** 被拦请求前 2s 内最近的一条主帧请求（即 302 的来源跳板），找不到返回 null。 */
    private fun recentMainFrameBefore(excludeUrl: String): String? {
        synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            for ((time, url) in recentMainFrameRequests.reversed()) {
                if (url != excludeUrl && now - time <= 2000L) {
                    return url
                }
            }
        }
        return null
    }

    /**
     * 事后溯源：站外跳转被拦时，把导致跳转的中转路径（同域 go.php 之类）记入黑名单，
     * 下次点击在第一跳请求阶段直接拦截。
     *
     * 候选优先级：最近放行的主帧请求 > Referer。
     * Referer 跨域时会被浏览器降级成 origin（`https://m.biquh.com/`，path 为 "/"），
     * 一旦收录等于整站拉黑——因此**path 非具体路径（根/空）的候选一律弃用**。
     *
     * @param referer 被拦请求的 Referer 头（onPageStarted 阶段没有请求对象，传 null）
     */
    private fun captureRedirectorCulprit(
        view: WebView,
        referer: String?,
        requestUrl: String
    ) {
        val candidates = listOfNotNull(recentMainFrameBefore(requestUrl), referer)
        val culprit = candidates.firstOrNull { candidate ->
            URLUtil.isNetworkUrl(candidate) &&
                candidate != currentUrl &&
                hasSpecificPath(candidate) &&
                KeyphoneGuardStore.baseDomain(candidate) !=
                KeyphoneGuardStore.baseDomain(requestUrl) &&
                !KeyphoneGuardStore.isRedirectorBlocked(view.context, candidate)
        } ?: return
        KeyphoneGuardStore.addRedirector(view.context, culprit)
        val key = KeyphoneGuardStore.redirectorKeyOf(culprit) ?: return
        logger.log(TAG, "Redirector learned: $key")
        view.post {
            KeyphoneUrlGuard.notify("已拉黑跳转中转：$key（下次直接拦截）")
        }
    }

    /** path 必须是具体路径（非根、非空），否则不允许进入中转黑名单。 */
    private fun hasSpecificPath(url: String): Boolean {
        val key = KeyphoneGuardStore.redirectorKeyOf(url) ?: return false
        return key.substringAfter('|').length > 1
    }

    /** 中转黑名单命中：计数 + 提示（2s 内同键去重，防止页面里几十个请求刷屏）。 */
    private fun onRedirectorBlocked(view: WebView, requestUrl: String) {
        val key = KeyphoneGuardStore.redirectorKeyOf(requestUrl) ?: return
        val shouldNotify = synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            val last = lastRedirectorNotifyAt[key] ?: 0L
            if (now - last < 2000L) {
                false
            } else {
                lastRedirectorNotifyAt[key] = now
                true
            }
        }
        if (shouldNotify) {
            view.post {
                val hits = KeyphoneGuardStore.bumpRedirectorHits(view.context, requestUrl)
                KeyphoneUrlGuard.notify("已拦截跳转中转：$key（累计 $hits 次）")
            }
        }
    }

    private fun shouldBlockRequest(pageUrl: String, requestUri: Uri) =
        !allowListModel.isUrlAllowedAds(pageUrl) &&
            runBlocking { adBlocker.await().isAd(requestUri) }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)

        // 按键机站内跳转拦截兜底：302 重定向 / 同域中转不经过 shouldOverrideUrlLoading，
        // 页面已提交时在此做最终把关。两类命中都回退：
        // ① 中转黑名单命中（无条件比对——回退重提交时 currentUrl == url，必须也能拦）
        // ② 跨域违规（仅在与当前页不同时判定）
        // 连续回退超过上限则熔断回空白页，防止重定向环把用户卡死
        if (KeyphoneUrlGuard.isActive() && !consumeExplicitAllow(url)) {
            val redirectorHit = KeyphoneGuardStore.isRedirectorBlocked(view.context, url)
            val jumpViolation = !redirectorHit && currentUrl != url &&
                KeyphoneUrlGuard.isJumpGuardViolation(view.context, currentUrl, url)
            if (redirectorHit || jumpViolation) {
                logger.log(TAG, "Guard bounced committed navigation: $currentUrl -> $url (redirector=$redirectorHit)")
                if (jumpViolation) {
                    captureRedirectorCulprit(view, null, url)
                }
                view.stopLoading()
                view.post {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastBounceAt > 5000L) {
                        bounceCount = 0
                    }
                    lastBounceAt = now
                    bounceCount++
                    if (bounceCount > 6) {
                        // 熔断：重定向环解不掉，回空白页终止循环
                        bounceCount = 0
                        view.loadUrl(SCHEME_BLANK)
                    } else if (view.canGoBack()) {
                        view.goBack()
                    } else {
                        view.loadUrl(SCHEME_BLANK)
                    }
                }
                return
            }
            bounceCount = 0
        }

        searchQuery = if (!url.isSpecialUrl()) {
            url
        } else {
            ""
        }
        searchQuerySelection = Pair(0, searchQuery.length)
        currentUrl = url
        tabCoroutineScope.launch {
            startedSharedFlow.emit(Unit)
            urlSharedFlow.emit(url)
            if (urlWithSslError != url) {
                urlWithSslError = null
                val sslState = if (URLUtil.isHttpsUrl(url)) {
                    SslState.Valid
                } else {
                    SslState.None
                }
                sslStateFlow.emit(sslState)
            }
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        tabCoroutineScope.launch {
            urlSharedFlow.emit(url)
            goBackSharedFlow.emit(view.canGoBack())
            goForwardSharedFlow.emit(view.canGoForward())
        }
        view.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                tabCoroutineScope.launch {
                    finishedSharedFlow.emit(Unit)
                }
            }
        })
    }


    override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
        if (view.isShown && tabSettings.textReflowEnabled) {
            if (isReflowRunning)
                return
            val changeInPercent = abs(100 - 100 / zoomScale * newScale)
            if (changeInPercent > 2.5f && !isReflowRunning) {
                isReflowRunning = view.postDelayed({
                    zoomScale = newScale
                    view.evaluateJavascript(textReflow.provideJs()) { isReflowRunning = false }
                }, 100)
            }

        }
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler,
        host: String,
        realm: String
    ) {
        val context = view.context
        AlertDialog.Builder(context).apply {
            val dialogView = DialogAuthRequestBinding.inflate(LayoutInflater.from(context))

            val realmLabel = dialogView.authRequestRealmTextview
            val name = dialogView.authRequestUsernameEdittext
            val password = dialogView.authRequestPasswordEdittext

            realmLabel.text = context.getString(R.string.label_realm, realm)

            setView(dialogView.root)
            setTitle(R.string.title_sign_in)
            setCancelable(true)
            setPositiveButton(R.string.title_sign_in) { _, _ ->
                val user = name.text.toString()
                val pass = password.text.toString()
                handler.proceed(user.trim(), pass.trim())
                logger.log(TAG, "Attempting HTTP Authentication")
            }
            setNegativeButton(R.string.action_cancel) { _, _ ->
                handler.cancel()
            }
        }.resizeAndShow()
    }

    override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) {
        val context = view.context
        AlertDialog.Builder(context).apply {
            setTitle(context.getString(R.string.title_form_resubmission))
            setMessage(context.getString(R.string.message_form_resubmission))
            setCancelable(true)
            setPositiveButton(context.getString(R.string.action_yes)) { _, _ ->
                resend.sendToTarget()
            }
            setNegativeButton(context.getString(R.string.action_no)) { _, _ ->
                dontResend.sendToTarget()
            }
        }.resizeAndShow()
    }

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(webView: WebView, handler: SslErrorHandler, error: SslError) {
        val context = webView.context
        urlWithSslError = webView.url

        tabCoroutineScope.launch {
            val sslState = SslState.Invalid(error)
            sslStateFlow.emit(sslState)
        }

        when (sslWarningPreferences.recallBehaviorForDomain(webView.url)) {
            SslWarningPreferences.Behavior.PROCEED -> return handler.proceed()
            SslWarningPreferences.Behavior.CANCEL -> return handler.cancel()
            null -> Unit
        }

        val errorCodeMessageCodes = error.getAllSslErrorMessageCodes()

        val stringBuilder = StringBuilder()
        for (messageCode in errorCodeMessageCodes) {
            stringBuilder.append(" - ").append(context.getString(messageCode)).append('\n')
        }
        val alertMessage =
            context.getString(R.string.message_insecure_connection, stringBuilder.toString())

        AlertDialog.Builder(context).apply {
            val view = DialogSslWarningBinding.inflate(LayoutInflater.from(context))
            val dontAskAgain = view.checkBoxDontAskAgain
            setTitle(context.getString(R.string.title_warning))
            setMessage(alertMessage)
            setCancelable(true)
            setView(view.root)
            setOnCancelListener { handler.cancel() }
            setPositiveButton(context.getString(R.string.action_yes)) { _, _ ->
                if (dontAskAgain.isChecked) {
                    sslWarningPreferences.rememberBehaviorForDomain(
                        webView.url.orEmpty(),
                        SslWarningPreferences.Behavior.PROCEED
                    )
                }
                handler.proceed()
            }
            setNegativeButton(context.getString(R.string.action_no)) { _, _ ->
                if (dontAskAgain.isChecked) {
                    sslWarningPreferences.rememberBehaviorForDomain(
                        webView.url.orEmpty(),
                        SslWarningPreferences.Behavior.CANCEL
                    )
                }
                handler.cancel()
            }
        }.resizeAndShow()
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()

        // 按键机安全拦截（仅按键机浏览页在场时生效）：外部协议审批 / 站内跳转拦截
        if (request.isForMainFrame && KeyphoneUrlGuard.isActive()) {
            val verdict = KeyphoneUrlGuard.evaluateNavigation(view.context, currentUrl, url)
            when (verdict.decision) {
                KeyphoneUrlGuard.Decision.BLOCK -> return true

                KeyphoneUrlGuard.Decision.ASK -> {
                    val host = KeyphoneUrlGuard.hostOrNull() ?: return true
                    val scheme = verdict.scheme.orEmpty()
                    val sourceDomain = verdict.sourceDomain
                    host.requestSchemeApproval(
                        currentUrl,
                        url,
                        verdict.mergedCount
                    ) { allowLaunch, rememberAllow, denyForever ->
                        if (denyForever) {
                            KeyphoneGuardStore.setSchemeRule(
                                view.context, scheme, sourceDomain,
                                KeyphoneGuardStore.RuleAction.DENY
                            )
                        } else if (rememberAllow == true) {
                            KeyphoneGuardStore.setSchemeRule(
                                view.context, scheme, sourceDomain,
                                KeyphoneGuardStore.RuleAction.ALLOW
                            )
                        } else {
                            KeyphoneUrlGuard.onOnceDecision(scheme, sourceDomain, allowLaunch)
                        }
                        if (allowLaunch && !denyForever) {
                            urlHandler.shouldOverrideLoading(
                                tabSettings.openAvailableAppsEnabled,
                                view,
                                url,
                                headers
                            )
                        }
                    }
                    return true
                }

                KeyphoneUrlGuard.Decision.PROCEED -> Unit
            }
        }

        return urlHandler.shouldOverrideLoading(
            tabSettings.openAvailableAppsEnabled,
            view,
            url,
            headers
        ) || super.shouldOverrideUrlLoading(view, request)
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val requestUrl = request.url.toString()

        // ① 跳转中转黑名单：曾导致站外跳转被拦的中转路径，主帧/子帧请求一律直接掐死
        if (KeyphoneUrlGuard.isActive() &&
            KeyphoneGuardStore.isRedirectorBlocked(view.context, requestUrl)
        ) {
            logger.log(TAG, "Guard blocked redirector request: $requestUrl")
            onRedirectorBlocked(view, requestUrl)
            return WebResourceResponse(
                BLOCKED_RESPONSE_MIME_TYPE,
                BLOCKED_RESPONSE_ENCODING,
                ByteArrayInputStream(emptyResponseByteArray)
            )
        }

        // ② 记录主帧请求（供 302 链事后溯源）
        if (request.isForMainFrame) {
            recordMainFrameRequest(requestUrl)
        }

        // ③ 站内跳转拦截（加载前拦截）：302 重定向目标的主帧请求也会经过这里，
        // 直接回空响应，广告页既不发网络请求也不渲染；回退由 onPageStarted 兜底完成
        if (request.isForMainFrame &&
            currentUrl != requestUrl &&
            !isNavigationPreAllowed(requestUrl) &&
            KeyphoneUrlGuard.isJumpGuardViolation(view.context, currentUrl, requestUrl)
        ) {
            logger.log(TAG, "Guard blocked main-frame request before load: $currentUrl -> $requestUrl")
            val requestReferer = request.requestHeaders?.entries
                ?.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
            captureRedirectorCulprit(view, requestReferer, requestUrl)
            return WebResourceResponse(
                BLOCKED_RESPONSE_MIME_TYPE,
                BLOCKED_RESPONSE_ENCODING,
                ByteArrayInputStream(emptyResponseByteArray)
            )
        }

        if (shouldBlockRequest(currentUrl, request.url)) {
            val empty = ByteArrayInputStream(emptyResponseByteArray)
            return WebResourceResponse(BLOCKED_RESPONSE_MIME_TYPE, BLOCKED_RESPONSE_ENCODING, empty)
        }
        return if (request.url.path?.startsWith(files.path) == true) {
            filesStoragePathHandler.handle(request.url.path!!.substring(files.path.length))
        } else if (request.url.path?.startsWith(cache.path) == true) {
            cacheStoragePathHandler.handle(request.url.path!!.substring(cache.path.length))
        } else {
            super.shouldInterceptRequest(view, request)
        }
    }

    private fun SslError.getAllSslErrorMessageCodes(): List<Int> {
        val errorCodeMessageCodes = ArrayList<Int>(1)

        if (hasError(SslError.SSL_DATE_INVALID)) {
            errorCodeMessageCodes.add(R.string.message_certificate_date_invalid)
        }
        if (hasError(SslError.SSL_EXPIRED)) {
            errorCodeMessageCodes.add(R.string.message_certificate_expired)
        }
        if (hasError(SslError.SSL_IDMISMATCH)) {
            errorCodeMessageCodes.add(R.string.message_certificate_domain_mismatch)
        }
        if (hasError(SslError.SSL_NOTYETVALID)) {
            errorCodeMessageCodes.add(R.string.message_certificate_not_yet_valid)
        }
        if (hasError(SslError.SSL_UNTRUSTED)) {
            errorCodeMessageCodes.add(R.string.message_certificate_untrusted)
        }
        if (hasError(SslError.SSL_INVALID)) {
            errorCodeMessageCodes.add(R.string.message_certificate_invalid)
        }

        return errorCodeMessageCodes
    }

    companion object {
        private const val TAG = "TabWebViewClient"

        private val emptyResponseByteArray: ByteArray = byteArrayOf()

        private const val BLOCKED_RESPONSE_MIME_TYPE = "text/plain"
        private const val BLOCKED_RESPONSE_ENCODING = "utf-8"
    }
}
