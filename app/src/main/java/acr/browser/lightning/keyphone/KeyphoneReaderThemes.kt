package acr.browser.lightning.keyphone

import android.graphics.Color

/**
 * 阅读模式外观常量（背景主题 + 字号档位）。
 *
 * 这两个表原来只存在于 `KeyphoneReadingMode.js`（CSS 侧），显示层换成原生 TextView 后
 * 必须搬到 Kotlin：JS 不再管样式，只负责取章。**这里是唯一事实源**，
 * `KeyphoneReadingPrefs` 落盘的 `theme_index` / `font_size` 直接对应本表的下标与 px 值。
 *
 * 主题序号与历史落盘数据兼容（0 米白 → 4 纯黑），不要重排顺序，否则用户既有偏好会变样。
 */
object KeyphoneReaderThemes {

    /** 一套配色：正文 / 标题 / 分隔线 / 次要文字，背景由 [bg] 决定 */
    data class Theme(
        val bg: Int,
        val text: Int,
        val head: Int,
        val rule: Int,
        val dim: Int
    )

    /** 5 档主题：米白 / 护眼黄 / 护眼绿 / 夜间灰 / 夜间纯黑（与旧 JS THEMES 表一一对应） */
    val THEMES: List<Theme> = listOf(
        // 米白（默认，最接近纸面）
        Theme(bg = Color.parseColor("#F4F4EE"), text = Color.parseColor("#222222"),
            head = Color.parseColor("#111111"), rule = Color.parseColor("#B9B9AF"),
            dim = Color.parseColor("#7A7A72")),
        // 护眼黄
        Theme(bg = Color.parseColor("#EAE0C8"), text = Color.parseColor("#3E3626"),
            head = Color.parseColor("#2A2418"), rule = Color.parseColor("#C6B994"),
            dim = Color.parseColor("#877B60")),
        // 护眼绿
        Theme(bg = Color.parseColor("#CCE8CF"), text = Color.parseColor("#22331E"),
            head = Color.parseColor("#16240F"), rule = Color.parseColor("#9CBB9C"),
            dim = Color.parseColor("#6C8069")),
        // 夜间灰
        Theme(bg = Color.parseColor("#1B1B1B"), text = Color.parseColor("#BDBDBD"),
            head = Color.parseColor("#DCDCDC"), rule = Color.parseColor("#4A4A4A"),
            dim = Color.parseColor("#8A8A8A")),
        // 夜间纯黑（OLED 省电）
        Theme(bg = Color.parseColor("#000000"), text = Color.parseColor("#B0B0B0"),
            head = Color.parseColor("#D2D2D2"), rule = Color.parseColor("#333333"),
            dim = Color.parseColor("#7A7A7A"))
    )

    /**
     * 正文字号档位（px）：14~24 每档 +2，28~40 每档 +4。
     *
     * 与旧 JS `FONT_SIZES` 完全一致（`KeyphoneReadingPrefs` 落盘的就是这里面的 px 值）。
     * 小字密档方便弱光阅读，大字粗档让「坐在桌上看手机」的场景少按几次。
     */
    val FONT_SIZES: IntArray = intArrayOf(14, 16, 18, 20, 22, 24, 28, 32, 36, 40)

    /** 默认字号档位下标（16px） */
    const val DEFAULT_FONT_INDEX: Int = 1

    /**
     * 行高倍数：与旧外壳 CSS 的 `line-height: 1.8` 对齐。
     * 换成 StaticLayout 后要换算成 `lineSpacingMultiplier`，见 [KeyphoneReaderView]。
     */
    const val LINE_SPACING: Float = 1.8f

    /** 正文左右内边距（旧 CSS 的 10px，按当前密度换算成 px） */
    const val PADDING_DP: Int = 10

    /** 主题数量 */
    val count: Int get() = THEMES.size

    /** 按下标取主题，越界回落到米白 */
    fun theme(index: Int): Theme = THEMES.getOrElse(index) { THEMES[0] }

    /** 把落盘的 px 字号映射回档位下标（取最接近的一档） */
    fun nearestFontIndex(px: Int): Int {
        var best = DEFAULT_FONT_INDEX
        var bestDiff = Int.MAX_VALUE
        FONT_SIZES.forEachIndexed { index, size ->
            val diff = kotlin.math.abs(size - px)
            if (diff < bestDiff) {
                bestDiff = diff
                best = index
            }
        }
        return best
    }
}
