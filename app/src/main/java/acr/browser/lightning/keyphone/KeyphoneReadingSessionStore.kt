package acr.browser.lightning.keyphone

import android.content.Context
import io.github.cctyl.nokia.common.log.KeydroidxLog

/**
 * 阅读会话的落盘快照（阅读态随 Activity 重建 / 进程被杀而丢失时的兜底）。
 *
 * 背景：阅读模式把「进入时那一章 → 后面拼进来的每一章」渲染在同一个 data 外壳里，
 * 而浏览内核记录的标签 URL 只会停在**入口章**（外壳的 baseUrl）。一旦 Activity 被重建
 * （按挂机键回桌面后 Activity 被系统回收、或进程被杀），内存里的
 * `readingModeTabs` 随 Activity 一起消失，标签按 URL 重新加载的结果就是**回到入口章**
 * ——用户视角为「阅读模式没了，而且章节错了」（已读到第 59 章，回到第 1 章）。
 *
 * 这里只记最小恢复信息：入口章 URL（作为标签 URL 的匹配键）、最后读到的章节 URL、
 * 已读章数。恢复动作见 `KeyphoneBrowserActivity.maybeRestoreReadingSession`：
 * 导航回最后读到的章节，再自动重进阅读模式（重新提取一次正文即可，不丢章节）。
 *
 * 刻意**不**落盘正文 / 拼页 DOM：那些体积大且由 JS 侧重建，重进阅读模式会自然拼回来。
 */
object KeyphoneReadingSessionStore {

    private const val TAG = "KeyphoneReadingSession"
    private const val PREFS_NAME = "keyphone_reading_session"
    private const val KEY_SESSIONS = "sessions"

    /** 最多保留几条会话（多标签同时阅读时才用得上，溢出丢最旧的） */
    private const val MAX_SESSIONS = 5

    /** 超过这个时长的会话不再自动恢复（避免很久以前没读完的章节莫名其妙自己弹出来） */
    private const val STALE_MS = 24L * 60 * 60 * 1000

    /** 一次阅读会话的恢复快照 */
    data class Snapshot(
        val entryUrl: String,
        val lastUrl: String,
        val chapterCount: Int,
        val savedAt: Long
    )

    /**
     * 记一条会话（同一 [entryUrl] 覆盖旧记录，最近的排最前）。
     * [lastUrl] 为空时退回 [entryUrl]，保证恢复时一定有落点。
     */
    fun save(context: Context, entryUrl: String, lastUrl: String, chapterCount: Int) {
        if (entryUrl.isBlank()) return
        val target = lastUrl.ifBlank { entryUrl }
        try {
            val records = loadRecords(context)
                .filterNot { it.entryUrl == entryUrl }
                .toMutableList()
            records.add(
                0,
                Snapshot(entryUrl, target, chapterCount.coerceAtLeast(1), System.currentTimeMillis())
            )
            writeRecords(context, records.take(MAX_SESSIONS))
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "保存阅读会话失败 entry=$entryUrl last=$target", t)
        }
    }

    /**
     * 按标签 URL 查回会话。
     *
     * 标签恢复后的 URL 可能是入口章（外壳 baseUrl，绝大多数情况），也可能是最后读到的
     * 章节（外壳被真实页替换过），两种都算命中。
     */
    fun load(context: Context, tabUrl: String): Snapshot? {
        val key = tabUrl.trim()
        if (key.isEmpty()) return null
        return try {
            val now = System.currentTimeMillis()
            loadRecords(context).firstOrNull { record ->
                (record.entryUrl == key || record.lastUrl == key) && now - record.savedAt in 0..STALE_MS
            }
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "读取阅读会话失败 url=$key", t)
            null
        }
    }

    /** 清空全部会话（用户主动退出阅读模式 / 页面被导航替换时调用） */
    fun clear(context: Context) {
        try {
            prefs(context).edit().remove(KEY_SESSIONS).apply()
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "清空阅读会话失败", t)
        }
    }

    // 记录格式：每条一行，字段用 \t 分隔（URL 里不会出现制表符 / 换行）
    private fun loadRecords(context: Context): List<Snapshot> {
        val raw = prefs(context).getString(KEY_SESSIONS, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3) return@mapNotNull null
            val entryUrl = parts[0].trim()
            val lastUrl = parts[1].trim()
            if (!entryUrl.startsWith("http")) return@mapNotNull null
            Snapshot(
                entryUrl = entryUrl,
                lastUrl = lastUrl.ifBlank { entryUrl },
                chapterCount = parts[2].trim().toIntOrNull() ?: 1,
                savedAt = parts.getOrNull(3)?.trim()?.toLongOrNull() ?: 0L
            )
        }
    }

    private fun writeRecords(context: Context, records: List<Snapshot>) {
        if (records.isEmpty()) {
            prefs(context).edit().remove(KEY_SESSIONS).apply()
            return
        }
        val text = records.joinToString("\n") {
            "${it.entryUrl}\t${it.lastUrl}\t${it.chapterCount}\t${it.savedAt}"
        }
        prefs(context).edit().putString(KEY_SESSIONS, text).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
