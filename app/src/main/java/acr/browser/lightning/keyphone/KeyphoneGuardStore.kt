package acr.browser.lightning.keyphone

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * 安全拦截规则存储（按键机专属，规则仅存本机 SharedPreferences）。
 *
 * 三组规则：
 * - 协议跳转规则：`QREADER://` 等外部 scheme，按「协议 + 来源域名(eTLD+1)」记 允许/忽略 + 命中次数；
 * - 站内跳转拦截开关：按来源域名记 开/关 + 拦截次数（# 菜单 4 设置）；
 * - 下载站点规则：按下载目标域名记 永久忽略 + 拦截次数。
 *
 * 域名一律归一化为 eTLD+1（`www.m.example.com` → `example.com`），
 * 简易二级后缀表覆盖 `com.cn` / `co.uk` 这类常见组合，够用于拦截比对。
 */
object KeyphoneGuardStore {

    /** 规则动作：允许放行 / 永久忽略（静默拦截）。 */
    enum class RuleAction { ALLOW, DENY }

    /** 一条规则（供管理页展示）。 */
    data class Rule(
        val key: String,
        val display: String,
        val source: String,
        val action: RuleAction,
        val hits: Int
    )

    private const val PREF_SCHEME = "keyphone_guard_scheme"
    private const val PREF_JUMP = "keyphone_guard_jump"
    private const val PREF_DOWNLOAD = "keyphone_guard_download"

    /** 常见「二级域 + 双字母顶级域」组合（com.cn / co.uk 等）。 */
    private val TWO_LEVEL_SUFFIXES = setOf(
        "com", "net", "org", "gov", "edu", "ac", "co", "ne", "or"
    )

    // ------------------------------------------------------------------
    // 域名归一化
    // ------------------------------------------------------------------

    /** 从 URL（或裸 host）提取 host，失败返回 null。 */
    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            if (url.contains("://")) {
                Uri.parse(url.trim()).host?.lowercase()?.trimEnd('.')
            } else if (!url.contains('/') && !url.contains('?')) {
                url.lowercase().trim().trimEnd('.')
            } else {
                null
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** eTLD+1 归一化：`www.m.example.com` → `example.com`；`m.example.com.cn` → `example.com.cn`。 */
    fun baseDomain(url: String?): String? {
        val host = hostOf(url) ?: return null
        var h = if (host.startsWith("www.") && host.count { it == '.' } > 1) {
            host.removePrefix("www.")
        } else {
            host
        }
        val labels = h.split('.').filter { it.isNotBlank() }
        if (labels.size <= 2) return labels.joinToString(".")
        val second = labels[labels.size - 2]
        val tld = labels.last()
        h = if (tld.length == 2 && second in TWO_LEVEL_SUFFIXES) {
            labels.takeLast(3).joinToString(".")
        } else {
            labels.takeLast(2).joinToString(".")
        }
        return h
    }

    // ------------------------------------------------------------------
    // 协议跳转规则
    // ------------------------------------------------------------------

    /** 规则键：`协议|来源域名`（来源未知时用 `-`）。 */
    fun schemeKey(scheme: String, sourceDomain: String?): String = "$scheme|${sourceDomain ?: "-"}"

    /** 查询协议规则，无规则返回 null。 */
    fun schemeRule(context: Context, scheme: String, sourceDomain: String?): Pair<RuleAction, Int>? {
        val raw = prefs(context, PREF_SCHEME).getString(schemeKey(scheme, sourceDomain), null)
            ?: return null
        return decodeRule(raw)
    }

    /** 写入（或覆盖）协议规则，命中次数清零重计。 */
    fun setSchemeRule(context: Context, scheme: String, sourceDomain: String?, action: RuleAction) {
        prefs(context, PREF_SCHEME)
            .edit()
            .putString(schemeKey(scheme, sourceDomain), encodeRule(action, 0))
            .apply()
    }

    /** 命中 +1，返回累计次数。 */
    fun bumpSchemeHits(context: Context, scheme: String, sourceDomain: String?): Int =
        bump(prefs(context, PREF_SCHEME), schemeKey(scheme, sourceDomain))

    /** 全部协议规则（管理页展示用）。 */
    fun schemeRules(context: Context): List<Rule> =
        rules(context, PREF_SCHEME) { key ->
            val scheme = key.substringBefore('|')
            val source = key.substringAfter('|', "-")
            Triple(scheme, source, scheme)
        }

    /** 删除单条协议规则（回到「默认拦截并弹审批」状态）。 */
    fun removeSchemeRule(context: Context, scheme: String, sourceDomain: String?) {
        prefs(context, PREF_SCHEME).edit().remove(schemeKey(scheme, sourceDomain)).apply()
    }

    fun clearSchemeRules(context: Context) {
        prefs(context, PREF_SCHEME).edit().clear().apply()
    }

    // ------------------------------------------------------------------
    // 站内跳转拦截开关（按来源域名）
    // ------------------------------------------------------------------

    /** 站内开关判定的内存缓存（键 = 域名）：热路径调用频繁，避免每次都读 SharedPreferences。 */
    private val jumpEnabledCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * 站内跳转拦截是否生效：**默认开启**——无记录的域名一律视为开启，
     * 只有用户手动关闭过的域名（存有 `{"on":false}` 记录）才放行站外跳转。
     *
     * 走内存缓存：本函数在每次导航/提交/请求判定中都会被调用（主线程与 IO 线程），
     * 直接读 SharedPreferences 会在主线程产生磁盘访问（StrictMode 已告警），故缓存结果。
     */
    fun isJumpEnabled(context: Context, domain: String?): Boolean {
        if (domain.isNullOrEmpty()) return false
        jumpEnabledCache[domain]?.let { return it }
        val enabled = try {
            val raw = prefs(context, PREF_JUMP).getString(domain, null)
            if (raw == null) true else JSONObject(raw).optBoolean("on", true)
        } catch (t: Throwable) {
            true
        }
        jumpEnabledCache[domain] = enabled
        return enabled
    }

    /** 开关切换：保留既有命中次数。 */
    fun setJumpEnabled(context: Context, domain: String, enabled: Boolean) {
        jumpEnabledCache[domain] = enabled
        val pref = prefs(context, PREF_JUMP)
        val hits = try {
            pref.getString(domain, null)?.let { JSONObject(it).optInt("c", 0) } ?: 0
        } catch (t: Throwable) {
            0
        }
        pref.edit().putString(domain, jumpJson(enabled, hits)).apply()
    }

    /** 站外跳转拦截次数 +1，返回累计次数（必须保留 on 标志，否则开关会被计数重写破坏）。 */
    fun bumpJumpHits(context: Context, domain: String): Int {
        val pref = prefs(context, PREF_JUMP)
        val current = try {
            pref.getString(domain, null)?.let { JSONObject(it) }
        } catch (t: Throwable) {
            null
        }
        val hits = (current?.optInt("c", 0) ?: 0) + 1
        val on = current?.optBoolean("on", true) ?: true
        pref.edit().putString(domain, jumpJson(on, hits)).apply()
        return hits
    }

    /** 全部站内拦截开关（含已关闭条目，供管理页展示）。 */
    fun jumpRules(context: Context): List<Rule> {
        val pref = prefs(context, PREF_JUMP)
        return pref.all.mapNotNull { (key, value) ->
            val json = try {
                JSONObject(value as? String ?: return@mapNotNull null)
            } catch (t: Throwable) {
                return@mapNotNull null
            }
            Rule(
                key = key,
                display = key,
                source = key,
                action = if (json.optBoolean("on")) RuleAction.ALLOW else RuleAction.DENY,
                hits = json.optInt("c", 0)
            )
        }
    }

    fun clearJumpRules(context: Context) {
        prefs(context, PREF_JUMP).edit().clear().apply()
        jumpEnabledCache.clear()
    }

    // ------------------------------------------------------------------
    // 下载站点规则
    // ------------------------------------------------------------------

    fun isDownloadDenied(context: Context, domain: String?): Boolean {
        if (domain.isNullOrEmpty()) return false
        val raw = prefs(context, PREF_DOWNLOAD).getString(domain, null) ?: return false
        return decodeRule(raw)?.first == RuleAction.DENY
    }

    fun setDownloadDenied(context: Context, domain: String) {
        prefs(context, PREF_DOWNLOAD)
            .edit()
            .putString(domain, encodeRule(RuleAction.DENY, 0))
            .apply()
    }

    /** 下载拦截次数 +1，返回累计次数。 */
    fun bumpDownloadHits(context: Context, domain: String): Int =
        bump(prefs(context, PREF_DOWNLOAD), domain)

    /** 全部下载站点规则。 */
    fun downloadRules(context: Context): List<Rule> =
        rules(context, PREF_DOWNLOAD) { key -> Triple(key, key, key) }

    /** 删除单条下载站点规则（恢复下载审批）。 */
    fun removeDownloadRule(context: Context, domain: String) {
        prefs(context, PREF_DOWNLOAD).edit().remove(domain).apply()
    }

    fun clearDownloadRules(context: Context) {
        prefs(context, PREF_DOWNLOAD).edit().clear().apply()
    }

    // ------------------------------------------------------------------
    // 跳转中转黑名单（自动学习：导致 302 跳转被拦的中转路径）
    //
    // 两级学习：首次命中仅会话内生效（重启即失效），同一 key 累计 ≥2 次才落盘持久。
    // 避免一次性误学把整站目录永久锁死（曾把用户点击的章节/目录学成规则）。
    // ------------------------------------------------------------------

    private const val PREF_REDIRECTOR = "keyphone_guard_redirector"
    private const val PREF_META = "keyphone_guard_meta"
    private const val META_REDIRECTOR_VERSION = "redirector_version"

    /**
     * 中转黑名单数据版本：升到 2 时清空旧的自动学习数据。
     * 旧版本会把用户点击的合法章节/目录误学成中转规则，导致整站被锁死。
     */
    private const val REDIRECTOR_DATA_VERSION = 2

    /** 同一中转 key 累计学习次数达到该值才落盘持久。 */
    private const val REDIRECTOR_PERSIST_AFTER_HITS = 2

    /** 会话内的中转拦截（首次学习，不落盘）。 */
    private val sessionRedirectors = HashSet<String>()
    private val sessionRedirectorHits = HashMap<String, Int>()

    private val migrated = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 一次性迁移：清空旧版本误学的中转规则（每进程只执行一次）。 */
    private fun ensureMigrated(context: Context) {
        if (migrated.get() || !migrated.compareAndSet(false, true)) return
        try {
            val meta = prefs(context, PREF_META)
            if (meta.getInt(META_REDIRECTOR_VERSION, 0) >= REDIRECTOR_DATA_VERSION) return
            prefs(context, PREF_REDIRECTOR).edit().clear().apply()
            meta.edit().putInt(META_REDIRECTOR_VERSION, REDIRECTOR_DATA_VERSION).apply()
        } catch (t: Throwable) {
            // 迁移失败不影响拦截功能本身
        }
    }

    /** 中转黑名单键：`eTLD+1|path`（不含 query——query 里多是一次性的点击令牌）。 */
    fun redirectorKeyOf(url: String?): String? {
        val host = hostOf(url) ?: return null
        val normHost = baseDomain(host) ?: host
        val path = try {
            Uri.parse(url!!.trim()).path.orEmpty()
        } catch (t: Throwable) {
            return null
        }
        if (path.isBlank()) return null
        return "$normHost|$path"
    }

    /** path 边界匹配：完全相等或作为目录前缀；`/go.php` 命中 `/go.php/x`，不命中 `/go.php5`。 */
    private fun pathMatches(path: String, entry: String): Boolean {
        val p = path.trimEnd('/')
        val e = entry.trimEnd('/')
        if (e.isEmpty()) return false
        return p == e || p.startsWith("$e/")
    }

    private fun entryMatches(entryKey: String, host: String, path: String): Boolean {
        val eHost = entryKey.substringBefore('|')
        val ePath = entryKey.substringAfter('|', "")
        // 根路径条目（host|/）等于整站拉黑，一律视为无效数据，不参与匹配
        if (ePath.length <= 1) return false
        return eHost == host && pathMatches(path, ePath)
    }

    /** 请求 URL 是否命中中转黑名单（会话内 + 持久化）。 */
    fun isRedirectorBlocked(context: Context, url: String?): Boolean {
        val key = redirectorKeyOf(url) ?: return false
        if (isSessionRedirectorBlocked(key)) return true
        ensureMigrated(context)
        val host = key.substringBefore('|')
        val path = key.substringAfter('|')
        for (entryKey in prefs(context, PREF_REDIRECTOR).all.keys) {
            if (entryMatches(entryKey, host, path)) return true
        }
        return false
    }

    /** 收录 [culpritUrl] 后，[victimUrl] 是否也会被同一规则命中（会锁死当前页，则不应收录）。 */
    fun wouldBlockAfterLearning(culpritUrl: String, victimUrl: String?): Boolean {
        val culpritKey = redirectorKeyOf(culpritUrl) ?: return false
        val victimKey = redirectorKeyOf(victimUrl) ?: return false
        return entryMatches(
            culpritKey,
            victimKey.substringBefore('|'),
            victimKey.substringAfter('|')
        )
    }

    private fun isSessionRedirectorBlocked(key: String): Boolean {
        val host = key.substringBefore('|')
        val path = key.substringAfter('|')
        synchronized(sessionRedirectors) {
            return sessionRedirectors.any { entryMatches(it, host, path) }
        }
    }

    /**
     * 记录一次学习：首次仅会话内生效，累计 ≥ [REDIRECTOR_PERSIST_AFTER_HITS] 次才落盘持久。
     * @return true 表示本次已持久化
     */
    fun addRedirector(context: Context, url: String): Boolean {
        val key = redirectorKeyOf(url) ?: return false
        ensureMigrated(context)
        val hits = synchronized(sessionRedirectors) {
            sessionRedirectors.add(key)
            val h = (sessionRedirectorHits[key] ?: 0) + 1
            sessionRedirectorHits[key] = h
            h
        }
        if (hits < REDIRECTOR_PERSIST_AFTER_HITS) return false
        val current = decodeRule(prefs(context, PREF_REDIRECTOR).getString(key, null))
        val persisted = (current?.second ?: 0) + 1
        prefs(context, PREF_REDIRECTOR)
            .edit()
            .putString(key, encodeRule(RuleAction.DENY, persisted))
            .apply()
        return true
    }

    /** 中转路径累计命中次数（会话 + 持久，只读，避免每次请求都写盘）。 */
    fun redirectorHits(context: Context, url: String): Int {
        val key = redirectorKeyOf(url) ?: return 0
        val session = synchronized(sessionRedirectors) { sessionRedirectorHits[key] ?: 0 }
        val persisted = decodeRule(prefs(context, PREF_REDIRECTOR).getString(key, null))?.second ?: 0
        return session + persisted
    }

    /** 全部中转黑名单条目（含仅会话内生效的条目）。 */
    fun redirectorRules(context: Context): List<Rule> {
        ensureMigrated(context)
        val persistent = rules(context, PREF_REDIRECTOR) { key -> Triple(key, key, key) }
        val existing = persistent.mapTo(HashSet()) { it.key }
        val sessionOnly = synchronized(sessionRedirectors) {
            sessionRedirectors.filter { it !in existing }.map { key ->
                Rule(
                    key = key,
                    display = key,
                    source = key,
                    action = RuleAction.DENY,
                    hits = sessionRedirectorHits[key] ?: 1
                )
            }
        }
        return persistent + sessionOnly
    }

    fun removeRedirector(context: Context, key: String) {
        prefs(context, PREF_REDIRECTOR).edit().remove(key).apply()
        synchronized(sessionRedirectors) {
            sessionRedirectors.remove(key)
            sessionRedirectorHits.remove(key)
        }
    }

    fun clearRedirectors(context: Context) {
        prefs(context, PREF_REDIRECTOR).edit().clear().apply()
        synchronized(sessionRedirectors) {
            sessionRedirectors.clear()
            sessionRedirectorHits.clear()
        }
    }

    // ------------------------------------------------------------------
    // 内部：JSON 编解码（`{"a":"allow|deny","c":次数}`）
    // ------------------------------------------------------------------

    private fun prefs(context: Context, name: String) =
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun encodeRule(action: RuleAction, hits: Int): String =
        JSONObject().put("a", if (action == RuleAction.ALLOW) "allow" else "deny").put("c", hits)
            .toString()

    /** 站内拦截开关专用格式：`{"on":bool,"c":次数}`。 */
    private fun jumpJson(on: Boolean, hits: Int): String =
        JSONObject().put("on", on).put("c", hits).toString()

    private fun decodeRule(raw: String?): Pair<RuleAction, Int>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val json = JSONObject(raw)
            val action = when (json.optString("a")) {
                "allow" -> RuleAction.ALLOW
                "deny" -> RuleAction.DENY
                else -> return null
            }
            action to json.optInt("c", 0)
        } catch (t: Throwable) {
            null
        }
    }

    /** 命中 +1 并落盘，返回累计次数。 */
    private fun bump(pref: android.content.SharedPreferences, key: String): Int {
        val current = decodeRule(pref.getString(key, null))
        val hits = (current?.second ?: 0) + 1
        val action = current?.first ?: RuleAction.DENY
        pref.edit().putString(key, encodeRule(action, hits)).apply()
        return hits
    }

    private fun rules(
        context: Context,
        prefName: String,
        parse: (String) -> Triple<String, String, String>
    ): List<Rule> {
        val pref = prefs(context, prefName)
        return pref.all.mapNotNull { (key, value) ->
            val decoded = decodeRule(value as? String) ?: return@mapNotNull null
            val (display, source, _) = parse(key)
            Rule(
                key = key,
                display = display,
                source = source,
                action = decoded.first,
                hits = decoded.second
            )
        }
    }
}
