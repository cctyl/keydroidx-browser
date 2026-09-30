package acr.browser.lightning.keyphone

import android.content.Context
import io.github.cctyl.nokia.common.log.KeydroidxLog

/**
 * 阅读模式外观偏好：背景主题与正文字号。
 *
 * - 按键机专属，单独落盘在 `keyphone_reading`，不污染上游 UserPreferencesDataStore；
 * - 写入时机：JS 侧按键（1 减小 / 3 增大 / 7 轮换背景）执行后经 JS 桥回传 `font` / `theme` 事件；
 * - 读取时机：进入阅读模式前，作为 `window.__keyphone_rm_init__` 与脚本同批注入，
 *   保证换章、刷新、重进阅读模式后外观保持一致（脚本每次注入都会重置为默认值）。
 *
 * 主题序号与字号档位必须与 KeyphoneReadingMode.js 内的 THEMES / FONT_SIZES 表对应。
 */
object KeyphoneReadingPrefs {

    private const val TAG = "KeyphoneReadingPrefs"
    private const val PREFS_NAME = "keyphone_reading"
    private const val KEY_THEME = "theme_index"
    private const val KEY_FONT = "font_size"

    /** 主题数量：米白 / 护眼黄 / 护眼绿 / 夜间灰 / 夜间纯黑（JS 侧 THEMES 表长度） */
    const val THEME_COUNT = 5

    /** 字号档位区间（px），JS 侧 FONT_SIZES 表为 14/16/18/20/22/24/28/32/36/40 */
    private const val FONT_MIN = 14
    private const val FONT_MAX = 40
    private const val FONT_DEFAULT = 16

    /**
     * 内存缓存（-1 表示尚未加载）：进入阅读模式时要在按键路径里同步取值，
     * 直接读 SharedPreferences 会在主线程产生磁盘访问（StrictMode 已告警），故缓存结果。
     */
    private var cachedTheme = -1
    private var cachedFont = -1

    /** 背景主题序号，越界自动回落到米白 */
    fun themeIndex(context: Context): Int {
        if (cachedTheme < 0) {
            cachedTheme = try {
                prefs(context).getInt(KEY_THEME, 0).coerceIn(0, THEME_COUNT - 1)
            } catch (t: Throwable) {
                KeydroidxLog.e(TAG, "读取阅读模式背景偏好失败", t)
                0
            }
        }
        return cachedTheme
    }

    /** 正文字号（px），越界自动回落到默认 16 */
    fun fontSize(context: Context): Int {
        if (cachedFont < 0) {
            cachedFont = try {
                prefs(context).getInt(KEY_FONT, FONT_DEFAULT).coerceIn(FONT_MIN, FONT_MAX)
            } catch (t: Throwable) {
                KeydroidxLog.e(TAG, "读取阅读模式字号偏好失败", t)
                FONT_DEFAULT
            }
        }
        return cachedFont
    }

    fun setThemeIndex(context: Context, index: Int) {
        val value = index.coerceIn(0, THEME_COUNT - 1)
        cachedTheme = value
        try {
            prefs(context).edit().putInt(KEY_THEME, value).apply()
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "保存阅读模式背景偏好失败: $value", t)
        }
    }

    fun setFontSize(context: Context, size: Int) {
        val value = size.coerceIn(FONT_MIN, FONT_MAX)
        cachedFont = value
        try {
            prefs(context).edit().putInt(KEY_FONT, value).apply()
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "保存阅读模式字号偏好失败: $value", t)
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
