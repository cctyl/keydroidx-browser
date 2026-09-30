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

    /**
     * 站内跳转拦截是否生效：**默认开启**——无记录的域名一律视为开启，
     * 只有用户手动关闭过的域名（存有 `{"on":false}` 记录）才放行站外跳转。
     */
    fun isJumpEnabled(context: Context, domain: String?): Boolean {
        if (domain.isNullOrEmpty()) return false
        val raw = prefs(context, PREF_JUMP).getString(domain, null) ?: return true
        return try {
            JSONObject(raw).optBoolean("on", true)
        } catch (t: Throwable) {
            true
        }
    }

    /** 开关切换：保留既有命中次数。 */
    fun setJumpEnabled(context: Context, domain: String, enabled: Boolean) {
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
    // ------------------------------------------------------------------

    private const val PREF_REDIRECTOR = "keyphone_guard_redirector"

    /** 中转黑名单键：`host|path`（不含 query——query 里多是一次性的点击令牌）。 */
    fun redirectorKeyOf(url: String?): String? {
        val host = hostOf(url) ?: return null
        val path = try {
            Uri.parse(url!!.trim()).path.orEmpty()
        } catch (t: Throwable) {
            return null
        }
        if (path.isBlank()) return null
        return "$host|$path"
    }

    /** 请求 URL 是否命中中转黑名单（host 相等——忽略 www 前缀，且 path 前缀匹配）。 */
    fun isRedirectorBlocked(context: Context, url: String?): Boolean {
        val key = redirectorKeyOf(url) ?: return false
        val host = key.substringBefore('|')
        val path = key.substringAfter('|')
        val norm = { h: String -> h.removePrefix("www.") }
        val pref = prefs(context, PREF_REDIRECTOR)
        for (entryKey in pref.all.keys) {
            val eHost = entryKey.substringBefore('|')
            val ePath = entryKey.substringAfter('|', "")
            // 根路径条目（host|/）等于整站拉黑，一律视为无效数据，不参与匹配
            if (ePath.length <= 1) {
                continue
            }
            if (norm(eHost) == norm(host) && path.startsWith(ePath)) {
                return true
            }
        }
        return false
    }

    /** 记录（或 +1）中转路径。 */
    fun addRedirector(context: Context, url: String) {
        val key = redirectorKeyOf(url) ?: return
        val current = decodeRule(prefs(context, PREF_REDIRECTOR).getString(key, null))
        val hits = (current?.second ?: 0) + 1
        prefs(context, PREF_REDIRECTOR)
            .edit()
            .putString(key, encodeRule(RuleAction.DENY, hits))
            .apply()
    }

    /** 中转路径命中次数 +1，返回累计次数。 */
    fun bumpRedirectorHits(context: Context, url: String): Int {
        val key = redirectorKeyOf(url) ?: return 0
        return bump(prefs(context, PREF_REDIRECTOR), key)
    }

    /** 全部中转黑名单条目。 */
    fun redirectorRules(context: Context): List<Rule> =
        rules(context, PREF_REDIRECTOR) { key -> Triple(key, key, key) }

    fun removeRedirector(context: Context, key: String) {
        prefs(context, PREF_REDIRECTOR).edit().remove(key).apply()
    }

    fun clearRedirectors(context: Context) {
        prefs(context, PREF_REDIRECTOR).edit().clear().apply()
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
