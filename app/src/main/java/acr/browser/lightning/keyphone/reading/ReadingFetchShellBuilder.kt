package acr.browser.lightning.keyphone.reading

import acr.browser.lightning.js.KeyphoneReadingMode
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 取章外壳的 HTML 拼装（方案 B 用）。
 *
 * 外壳里**只有** boot 载荷 + 阅读脚本：没有正文、没有站点脚本、没有任何样式。
 * 它的唯一职责是「以章节页为 baseUrl 跑在同源上下文里，于是同步 XHR 带得上 Cookie」。
 *
 * 抽成独立类的两个理由：纯字符串、可单测；A/B 与将来的兜底外壳共用同一份拼装逻辑。
 */
@Singleton
class ReadingFetchShellBuilder @Inject constructor(
    private val readingMode: KeyphoneReadingMode
) {

    /** 拼外壳；`next` 为空表示当前章没有下一章，`seen` 是已读 URL（供 JS 判环） */
    fun build(baseUrl: String, next: String?, seen: List<String>): String {
        val boot = JSONObject().apply {
            put("url", baseUrl)
            put("next", next.orEmpty())
            put("seen", JSONArray(seen))
        }.toString()
        return buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"")
            append(VIEWPORT)
            append("\">")
            append("</head><body><script>")
            append("window.__keyphone_rm_boot__=").append(boot).append(";\n")
            append(readingMode.provideJs())
            append("</script></body></html>")
        }
    }

    companion object {
        /**
         * 外壳 viewport（与 `KeyphoneReadingMode.js` 的约定一致）。
         * 外壳只是取章工具，用户看不到它，但保持与真实页面一致的排版口径不会有害。
         */
        const val VIEWPORT = "width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"

        /**
         * 取下一章的注入脚本。
         *
         * 返回 JSON 字符串（`{ok:true,...}` / `{ok:false,end:true}` / `{ok:false,pending:true}`）；
         * `missing:true` 表示外壳已经不在了（渲染进程被杀 / 页面被替换），宿主据此重建。
         */
        const val FETCH_NEXT_SCRIPT =
            "(function(){try{var r=window.__keyphone_rm__;" +
                "if(!r||!r.fetchNext)return JSON.stringify({ok:false,missing:true});" +
                "return r.fetchNext();}catch(e){return JSON.stringify({ok:false,error:String(e)});}})()"
    }
}
