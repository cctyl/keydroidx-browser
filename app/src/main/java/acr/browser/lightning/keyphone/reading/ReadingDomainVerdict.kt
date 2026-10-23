package acr.browser.lightning.keyphone.reading

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 域名 → 「静态 HTML 能直接提出来吗」的运行时判定缓存（内存级）。
 *
 * 只在**有了确定结论**时回填：
 * - A 成功 → [Verdict.SSR]（后续同域继续走 A）；
 * - A 提空 → [Verdict.JS]（后续同域直接走 B，不再每次白试一次 HTTP）。
 *
 * 网络错误**不回填**：偶发超时不能把一个 SSR 站点永久打成 CSR。
 * 重启进程即清空（重判一次的成本很低，也能让站点改版后自愈）。
 */
@Singleton
class ReadingDomainVerdict @Inject constructor() {

    enum class Verdict { SSR, JS }

    private val map = HashMap<String, Verdict>()

    fun get(host: String?): Verdict? = host?.let { map[it] }

    fun set(host: String, verdict: Verdict) {
        map[host] = verdict
    }

    fun clear(host: String) {
        map.remove(host)
    }

    fun clearAll() {
        map.clear()
    }
}
