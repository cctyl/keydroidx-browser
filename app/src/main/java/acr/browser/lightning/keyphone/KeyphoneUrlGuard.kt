package acr.browser.lightning.keyphone

import android.os.SystemClock
import android.webkit.URLUtil
import io.github.cctyl.nokia.common.log.KeydroidxLog

/**
 * 安全拦截判定引擎（按键机专属）。
 *
 * 由 `TabWebViewClient`（导航）与 `TabAdapter`（下载）调用，两处拦截点：
 * - **外部协议审批**：`QREADER://` 等自定义 scheme / `intent://` 一律不自动拉起第三方应用；
 *   无规则 → 弹审批卡（同一「协议+来源域」5s 内合并），永久忽略后静默拦截 + 计数；
 *   本次拒绝后在会话内静默（防疯狂弹窗）。
 * - **站内跳转拦截**：按来源域名开启后，目标 eTLD+1 ≠ 当前 eTLD+1 的一律拦截 + 计数提示。
 * - **下载审批**：所有下载先确认，永久忽略的站点静默拦截 + 计数。
 *
 * 时机说明：`shouldOverrideUrlLoading` 必须同步返回，因此 ASK 一律先返回「拦截」，
 * 审批卡由宿主（[KeyphoneBrowserActivity] 经 [Host]）异步弹出；
 * 用户允许后由调用方重新执行真正的跳起动作。
 *
 * 上游普通浏览器不受影响：仅当按键机浏览页注册了 [Host]（isActive）时判定才生效。
 */
object KeyphoneUrlGuard {

    /** 判定结果。 */
    enum class Decision {
        /** 放行，交回原流程处理。 */
        PROCEED,

        /** 拦截（静默规则命中 / 站外跳转 / 弹窗已在展示中）。 */
        BLOCK,

        /** 拦截本次导航，并弹审批卡。 */
        ASK
    }

    /** 一次导航判定结果；ASK 时附带协议与来源域，供审批后写规则。 */
    data class Verdict(
        val decision: Decision,
        val scheme: String? = null,
        val sourceDomain: String? = null,
        val mergedCount: Int = 1
    )

    /**
     * 宿主回调：由按键机浏览页实现，负责弹审批卡与提示条。
     * 全部回调都在主线程发生。
     */
    interface Host {
        /**
         * 外部协议审批卡。
         * @param onDecision (允许本次跳起, 永久允许?, 永久忽略?)
         */
        fun requestSchemeApproval(
            sourceUrl: String?,
            targetUrl: String,
            mergedCount: Int,
            onDecision: (allowLaunch: Boolean, rememberAllow: Boolean?, denyForever: Boolean) -> Unit
        )

        /**
         * 下载审批卡。
         * @param onDecision (允许下载, 永久忽略该站点?)
         */
        fun requestDownloadApproval(
            sourceUrl: String?,
            targetUrl: String,
            fileName: String,
            contentLength: Long,
            onDecision: (allow: Boolean, denySiteForever: Boolean) -> Unit
        )

        /** 拦截提示（状态条 / Toast）。 */
        fun notifyGuard(message: String)
    }

    /** 内置放行的 scheme（不参与外部协议审批）。 */
    private val PASS_SCHEMES = setOf(
        "http", "https", "about", "file", "data", "javascript", "blob", "ws", "wss"
    )

    /** mailto: 常规场景直接放行（不做外部审批）。 */
    private const val SCHEME_MAILTO = "mailto"

    /** intent:// 按独立协议参与审批。 */
    private const val SCHEME_INTENT = "intent"

    /** 同一规则 5s 内的重复请求合并为一次弹窗。 */
    private const val ASK_MERGE_MS = 5_000L

    @Volatile
    private var host: Host? = null

    private val askLock = Any()
    private var lastAskKey: String? = null
    private var lastAskAt: Long = 0L

    /** 本次拒绝（未写规则）的会话内静默集合：键 = "scheme|来源域" 或 "dl|域名"。 */
    private val sessionDenied = HashSet<String>()

    fun attach(newHost: Host) {
        host = newHost
    }

    fun detach(oldHost: Host) {
        if (host === oldHost) {
            host = null
        }
    }

    /** 拦截判定是否生效（按键机浏览页在场才生效，上游浏览器不受影响）。 */
    fun isActive(): Boolean = host != null

    /** 读取当前宿主（可能为 null）。 */
    fun hostOrNull(): Host? = host

    fun notify(message: String) {
        host?.notifyGuard(message)
    }

    // ------------------------------------------------------------------
    // 新窗口（target="_blank" / window.open）拦截
    // ------------------------------------------------------------------

    /**
     * 弹新窗口前的判定：`shouldOverrideUrlLoading` 对弹窗链路不生效
     * （弹窗在新建标签页的 WebView 中加载，且新标签无来源页上下文），
     * 因此必须在 `onCreateWindow` 阶段拦截。
     *
     * 站内跳转拦截开启时，弹新窗口一律不放行（弹窗目标域在创建时不可知，且多为广告）。
     * 必须在主线程调用。
     */
    fun shouldBlockCreateWindow(context: android.content.Context, sourceUrl: String?): Boolean {
        if (!isActive()) {
            return false
        }
        val sourceDomain = KeyphoneGuardStore.baseDomain(sourceUrl) ?: return false
        if (!KeyphoneGuardStore.isJumpEnabled(context, sourceDomain)) {
            return false
        }
        val hits = KeyphoneGuardStore.bumpJumpHits(context, sourceDomain)
        notify("已拦截站外弹窗（累计 $hits 次）")
        KeydroidxLog.d(TAG, "Blocked createWindow: source=$sourceUrl")
        return true
    }

    // ------------------------------------------------------------------
    // 导航判定
    // ------------------------------------------------------------------

    /**
     * 判定一次主框架导航。
     * 必须在主线程调用（内部可能弹 Toast）。
     */
    fun evaluateNavigation(context: android.content.Context, sourceUrl: String?, targetUrl: String): Verdict {
        val verdict = evaluateNavigationInternal(context, sourceUrl, targetUrl)
        if (verdict.decision != Decision.PROCEED) {
            KeydroidxLog.d(TAG, "Navigation ${verdict.decision} scheme=${verdict.scheme} src=$sourceUrl -> $targetUrl")
        }
        return verdict
    }

    private fun evaluateNavigationInternal(context: android.content.Context, sourceUrl: String?, targetUrl: String): Verdict {
        val scheme = schemeOf(targetUrl) ?: return Verdict(Decision.PROCEED)

        if (scheme in PASS_SCHEMES) {
            return evaluateJumpGuard(context, sourceUrl, targetUrl)
        }
        if (scheme == SCHEME_MAILTO) {
            return Verdict(Decision.PROCEED)
        }

        // 外部协议：查规则 → ALLOW 放行 / DENY 静默拦截 / 无规则 → 合并后弹审批
        val sourceDomain = KeyphoneGuardStore.baseDomain(sourceUrl)
        val rule = KeyphoneGuardStore.schemeRule(context, scheme, sourceDomain)
        return when {
            rule?.first == KeyphoneGuardStore.RuleAction.ALLOW -> Verdict(Decision.PROCEED)

            rule?.first == KeyphoneGuardStore.RuleAction.DENY -> {
                val hits = KeyphoneGuardStore.bumpSchemeHits(context, scheme, sourceDomain)
                notify("已拦截外部跳转 $scheme://（累计 $hits 次）")
                Verdict(Decision.BLOCK)
            }

            else -> {
                val key = KeyphoneGuardStore.schemeKey(scheme, sourceDomain)
                if (isSessionDenied(key)) {
                    Verdict(Decision.BLOCK)
                } else {
                    val merged = registerAsk(key)
                    if (merged > 1) {
                        // 弹窗已在展示中，静默拦截重复请求
                        Verdict(Decision.BLOCK)
                    } else {
                        Verdict(Decision.ASK, scheme, sourceDomain, merged)
                    }
                }
            }
        }
    }

    /** 本次「允许/拒绝」不写规则时维护会话内状态。 */
    fun onOnceDecision(scheme: String, sourceDomain: String?, allowLaunch: Boolean) {
        val key = KeyphoneGuardStore.schemeKey(scheme, sourceDomain)
        synchronized(askLock) {
            if (allowLaunch) {
                sessionDenied.remove(key)
            } else {
                sessionDenied.add(key)
            }
        }
    }

    private fun evaluateJumpGuard(
        context: android.content.Context,
        sourceUrl: String?,
        targetUrl: String
    ): Verdict {
        val sourceDomain = KeyphoneGuardStore.baseDomain(sourceUrl) ?: return Verdict(Decision.PROCEED)
        val targetDomain = KeyphoneGuardStore.baseDomain(targetUrl) ?: return Verdict(Decision.PROCEED)
        if (sourceDomain == targetDomain) {
            return Verdict(Decision.PROCEED)
        }
        if (!KeyphoneGuardStore.isJumpEnabled(context, sourceDomain)) {
            return Verdict(Decision.PROCEED)
        }
        val hits = KeyphoneGuardStore.bumpJumpHits(context, sourceDomain)
        notify("已拦截站外跳转：$targetDomain（累计 $hits 次）")
        return Verdict(Decision.BLOCK)
    }

    // ------------------------------------------------------------------
    // 下载审批
    // ------------------------------------------------------------------

    /**
     * 下载请求入口：命中「永久忽略」→ 静默拦截；5s 合并窗口内 → 忽略本次；
     * 其余弹审批卡，用户决定后回调 [onResult]。
     * 必须在主线程调用。
     */
    fun handleDownload(
        context: android.content.Context,
        sourceUrl: String?,
        targetUrl: String,
        fileName: String,
        contentLength: Long,
        onResult: (allowDownload: Boolean) -> Unit
    ) {
        val domain = KeyphoneGuardStore.baseDomain(targetUrl)
            ?: KeyphoneGuardStore.baseDomain(sourceUrl)
            ?: "-"
        if (KeyphoneGuardStore.isDownloadDenied(context, domain)) {
            val hits = KeyphoneGuardStore.bumpDownloadHits(context, domain)
            notify("已拦截该站点下载：$domain（累计 $hits 次）")
            onResult(false)
            return
        }
        val key = "dl|$domain"
        if (isSessionDenied(key)) {
            onResult(false)
            return
        }
        if (registerAsk(key) > 1) {
            onResult(false)
            return
        }
        val currentHost = host
        if (currentHost == null) {
            onResult(false)
            return
        }
        currentHost.requestDownloadApproval(sourceUrl, targetUrl, fileName, contentLength) { allow, denySiteForever ->
            if (denySiteForever) {
                KeyphoneGuardStore.setDownloadDenied(context, domain)
                notify("已永久忽略该站点下载：$domain")
            }
            synchronized(askLock) {
                if (!allow && !denySiteForever) {
                    sessionDenied.add(key)
                }
            }
            onResult(allow && !denySiteForever)
        }
    }

    /** 由目标 URL 与响应头推断文件名。 */
    fun guessFileName(url: String, contentDisposition: String?, mimeType: String?): String =
        try {
            URLUtil.guessFileName(url, contentDisposition, mimeType)
        } catch (t: Throwable) {
            url.substringAfterLast('/').ifBlank { "download" }
        }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private fun schemeOf(url: String): String? =
        url.substringBefore(':', "").lowercase().takeIf { it.isNotBlank() && url.length > it.length + 1 }

    private fun isSessionDenied(key: String): Boolean =
        synchronized(askLock) { key in sessionDenied }

    /** 登记一次询问；返回本次是该键的第几次（>1 表示弹窗已在展示，应合并）。 */
    private fun registerAsk(key: String): Int {
        val now = SystemClock.elapsedRealtime()
        return synchronized(askLock) {
            if (key == lastAskKey && now - lastAskAt < ASK_MERGE_MS) {
                lastAskAt = now
                lastAskCount + 1
            } else {
                lastAskKey = key
                lastAskAt = now
                lastAskCount = 1
                1
            }
        }
    }

    private var lastAskCount: Int = 0

    private const val TAG = "KeyphoneUrlGuard"
}
