package acr.browser.lightning.keyphone

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * 专属按键适配存储：按「站点键」持久化 1~9 键的自定义光标落点。
 *
 * - 站点键为层级前缀：`m.qidian.com`（纯域名）、`m.qidian.com/chapter`（+一级路径）、
 *   `m.qidian.com/chapter/xxx`（+二级路径），不含 scheme/query/fragment；
 * - 落点保存为宿主宽高的比例坐标（0~1），天然适配分辨率与旋屏；
 * - 匹配时从最具体（路径最长）到最泛（纯域名）依次命中，页面前缀落在已标定层级即生效；
 * - 标定时实时写入（apply 即时落盘），1~9 跳转时优先读取，无标定走默认九宫格。
 */
object KeyphoneSiteKeyStore {

    private const val PREFS_NAME = "keyphone_site_keys"

    /** 内存缓存：siteKey -> (digit -> (fx, fy)) */
    private val cache = HashMap<String, HashMap<Int, Pair<Float, Float>>>()

    /**
     * 当前网址的候选站点键（从泛到具体）：
     * [纯净域名, 域名/一级路径, 域名/二级路径]；路径段不足则相应减少。
     */
    fun candidatesOf(url: String?): List<String> {
        if (url.isNullOrBlank()) return emptyList()
        return try {
            val u = Uri.parse(url.trim())
            val host = u.host?.lowercase() ?: return emptyList()
            val segs = u.pathSegments.filter { it.isNotBlank() }
            val list = ArrayList<String>(3)
            list.add(host)
            if (segs.isNotEmpty()) list.add("$host/${segs[0]}")
            if (segs.size >= 2) list.add("$host/${segs[0]}/${segs[1]}")
            list.distinct()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * 读取某键的自定义落点（比例坐标），按 [candidates] 从最具体到最泛依次匹配，
     * 全部未标定返回 null
     */
    fun get(context: Context, candidates: List<String>, digit: Int): Pair<Float, Float>? {
        if (candidates.isEmpty() || digit !in 1..9) return null
        val cache = cached(context)
        for (i in candidates.indices.reversed()) {
            cache[candidates[i]]?.get(digit)?.let { return it }
        }
        return null
    }

    /** 只查指定站点键自身的标定值（不做层级回退），未标定返回 null */
    fun getExact(context: Context, siteKey: String, digit: Int): Pair<Float, Float>? {
        if (siteKey.isEmpty() || digit !in 1..9) return null
        return cached(context)[siteKey]?.get(digit)
    }

    /** 标定保存：实时写入内存缓存与 SharedPreferences */
    fun put(context: Context, siteKey: String, digit: Int, fx: Float, fy: Float) {
        if (siteKey.isEmpty() || digit !in 1..9) return
        val map = cached(context).getOrPut(siteKey) { HashMap() }
        map[digit] = fx.coerceIn(0f, 1f) to fy.coerceIn(0f, 1f)
        persist(context, siteKey, map)
    }

    /** 删除某站点某键的标定（重置回默认九宫格） */
    fun remove(context: Context, siteKey: String, digit: Int) {
        if (siteKey.isEmpty() || digit !in 1..9) return
        val map = cached(context)[siteKey] ?: return
        if (map.remove(digit) == null) return
        val prefs = prefs(context)
        if (map.isEmpty()) {
            cache.remove(siteKey)
            prefs.edit().remove(siteKey).apply()
        } else {
            persist(context, siteKey, map)
        }
    }

    private fun cached(context: Context): HashMap<String, HashMap<Int, Pair<Float, Float>>> {
        if (cache.isEmpty()) {
            val prefs = prefs(context)
            for ((key, value) in prefs.all) {
                val map = decode(value as? String) ?: continue
                cache[key] = map
            }
        }
        return cache
    }

    private fun persist(context: Context, siteKey: String, map: HashMap<Int, Pair<Float, Float>>) {
        val json = JSONObject()
        for ((digit, point) in map) {
            json.put(digit.toString(), "${point.first},${point.second}")
        }
        prefs(context).edit().putString(siteKey, json.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun decode(raw: String?): HashMap<Int, Pair<Float, Float>>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val json = JSONObject(raw)
            val map = HashMap<Int, Pair<Float, Float>>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val digit = keys.next().toIntOrNull() ?: continue
                val parts = json.optString(digit.toString()).split(',')
                if (parts.size != 2) continue
                val fx = parts[0].toFloatOrNull() ?: continue
                val fy = parts[1].toFloatOrNull() ?: continue
                map[digit] = fx to fy
            }
            if (map.isEmpty()) null else map
        } catch (t: Throwable) {
            null
        }
    }
}
