package acr.browser.lightning.keyphone

import acr.browser.lightning.R
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.cctyl.nokia.common.log.KeydroidxLog
import io.github.cctyl.nokia.common.model.KeydroidxKeyAction
import io.github.cctyl.nokia.common.ui.KeydroidxFontManager
import io.github.cctyl.nokia.common.ui.KeydroidxIcons
import io.github.cctyl.nokia.common.ui.KeydroidxTheme
import io.github.cctyl.nokia.common.ui.dialog.KeydroidxConfirmDialog
import io.github.cctyl.nokia.common.ui.page.KeydroidxListFocusHelper
import io.github.cctyl.nokia.common.util.KeydroidxDimens
import io.github.cctyl.nokia.keycore.ui.KeydroidxBaseActivity

/**
 * 安全拦截规则管理页（按键机专属）。
 *
 * 三组规则统一查看 / 改判 / 撤销：
 * - 协议跳转规则：SELECT 弹操作卡（改为永久允许 / 改为永久忽略 / 删除规则）；
 * - 站内跳转拦截开关：SELECT 直接开 ↔ 关切换；
 * - 下载站点规则：SELECT 确认后删除（恢复下载审批）。
 *
 * 进入路径：选项页「安全拦截」（LSK 软键 / 首页与页面模式均有入口）。
 * LSK = 清空全部规则（带确认）；RSK/BACK 返回。
 */
class KeyphoneGuardListActivity : KeydroidxBaseActivity() {

    private enum class Kind { SCHEME, JUMP, DOWNLOAD, REDIRECTOR }

    private data class RowKey(
        val kind: Kind,
        val key: String,
        val domain: String? = null,
        val scheme: String? = null
    )

    private var focusHelper: KeydroidxListFocusHelper? = null
    private var rowsContainer: LinearLayout? = null
    private var scroll: ScrollView? = null
    private val focusables = ArrayList<View>()
    private var lastFocusIndex = 0

    override fun getContentLayoutRes(): Int = R.layout.keyphone_list

    override fun onInitViews() {
        setPageTitle(getString(R.string.keyphone_guard_title))
        setTitleIcon(KeydroidxIcons.ICON_LOCK)
        setStatusBarVisible(false)
        setSoftKeys(
            getString(R.string.keyphone_guard_clear),
            null,
            getString(R.string.keyphone_soft_back)
        )
        scroll = findViewById(R.id.keyphone_scroll)
        rowsContainer = findViewById(R.id.keyphone_rows)
        rebuild()
    }

    /** 重建整页列表（保留焦点位置）。 */
    private fun rebuild() {
        val container = rowsContainer ?: return
        container.removeAllViews()
        focusables.clear()

        // —— 协议跳转规则 ——
        container.addView(
            KeyphoneViews.sectionLabel(this, getString(R.string.keyphone_guard_section_scheme))
        )
        val schemeRules = KeyphoneGuardStore.schemeRules(this)
        if (schemeRules.isEmpty()) {
            container.addView(hintRow(getString(R.string.keyphone_guard_empty_scheme)))
        } else {
            schemeRules.forEach { rule ->
                val state = getString(
                    if (rule.action == KeyphoneGuardStore.RuleAction.ALLOW) {
                        R.string.keyphone_guard_rule_allow
                    } else {
                        R.string.keyphone_guard_rule_deny
                    }
                )
                addRow(
                    title = "${rule.display}（$state）",
                    subtitle = "${rule.source} · ${getString(R.string.keyphone_guard_hits, rule.hits)}",
                    iconCode = KeydroidxIcons.ICON_LOCK,
                    rowKey = RowKey(Kind.SCHEME, rule.key, scheme = rule.display, domain = rule.source)
                )
            }
        }

        // —— 站内跳转拦截开关 ——
        container.addView(KeyphoneViews.divider(this))
        container.addView(
            KeyphoneViews.sectionLabel(this, getString(R.string.keyphone_guard_section_jump))
        )
        val jumpRules = KeyphoneGuardStore.jumpRules(this)
        if (jumpRules.isEmpty()) {
            container.addView(hintRow(getString(R.string.keyphone_guard_empty_jump)))
        } else {
            jumpRules.forEach { rule ->
                val state = getString(
                    if (KeyphoneGuardStore.isJumpEnabled(this, rule.display)) {
                        R.string.keyphone_guard_rule_on
                    } else {
                        R.string.keyphone_guard_rule_off
                    }
                )
                addRow(
                    title = rule.display,
                    subtitle = "$state · ${getString(R.string.keyphone_guard_hits, rule.hits)}",
                    iconCode = KeydroidxIcons.ICON_LOCK,
                    rowKey = RowKey(Kind.JUMP, rule.key, domain = rule.display)
                )
            }
        }

        // —— 下载站点规则 ——
        container.addView(KeyphoneViews.divider(this))
        container.addView(
            KeyphoneViews.sectionLabel(this, getString(R.string.keyphone_guard_section_download))
        )
        val downloadRules = KeyphoneGuardStore.downloadRules(this)
        if (downloadRules.isEmpty()) {
            container.addView(hintRow(getString(R.string.keyphone_guard_empty_download)))
        } else {
            downloadRules.forEach { rule ->
                addRow(
                    title = "${rule.display}（${getString(R.string.keyphone_guard_rule_deny)}）",
                    subtitle = getString(R.string.keyphone_guard_hits, rule.hits),
                    iconCode = KeydroidxIcons.ICON_DOWNLOAD,
                    rowKey = RowKey(Kind.DOWNLOAD, rule.key, domain = rule.display)
                )
            }
        }

        // —— 跳转中转黑名单（自动学习） ——
        container.addView(KeyphoneViews.divider(this))
        container.addView(
            KeyphoneViews.sectionLabel(this, getString(R.string.keyphone_guard_section_redirector))
        )
        val redirectorRules = KeyphoneGuardStore.redirectorRules(this)
        if (redirectorRules.isEmpty()) {
            container.addView(hintRow(getString(R.string.keyphone_guard_empty_redirector)))
        } else {
            redirectorRules.forEach { rule ->
                addRow(
                    title = rule.display,
                    subtitle = getString(R.string.keyphone_guard_hits, rule.hits),
                    iconCode = KeydroidxIcons.ICON_LOCK,
                    rowKey = RowKey(Kind.REDIRECTOR, rule.key)
                )
            }
        }

        val helper = KeydroidxListFocusHelper(scroll ?: return)
        helper.setItems(focusables)
        if (focusables.isNotEmpty()) {
            helper.setFocusIndex(lastFocusIndex.coerceIn(0, focusables.size - 1))
        }
        focusHelper = helper
    }

    private fun addRow(title: String, subtitle: String, iconCode: String, rowKey: RowKey) {
        val container = rowsContainer ?: return
        val row = KeyphoneViews.iconRow(this, iconCode, null, title, subtitle)
        row.setOnClickListener {
            focusHelper?.let { helper ->
                lastFocusIndex = focusables.indexOf(row).coerceAtLeast(0)
                helper.setFocusIndex(lastFocusIndex)
            }
            onRowAction(rowKey)
        }
        container.addView(row)
        focusables.add(row)
    }

    /** SELECT / 点击行：按规则类型分派操作。 */
    private fun onRowAction(key: RowKey) {
        when (key.kind) {
            Kind.JUMP -> {
                val domain = key.domain ?: return
                val enabled = KeyphoneGuardStore.isJumpEnabled(this, domain)
                KeyphoneGuardStore.setJumpEnabled(this, domain, !enabled)
                Toast.makeText(
                    this,
                    getString(
                        if (!enabled) {
                            R.string.keyphone_jump_enabled_toast
                        } else {
                            R.string.keyphone_jump_disabled_toast
                        },
                        domain
                    ),
                    Toast.LENGTH_SHORT
                ).show()
                rebuild()
            }

            Kind.DOWNLOAD -> {
                val domain = key.domain ?: return
                KeydroidxConfirmDialog(
                    this,
                    getString(R.string.keyphone_guard_delete_rule_title),
                    getString(R.string.keyphone_guard_delete_rule_message)
                )
                    .setPositiveButton(getString(R.string.keyphone_soft_confirm)) {
                        KeyphoneGuardStore.removeDownloadRule(this, domain)
                        rebuild()
                    }
                    .setNegativeButton(getString(R.string.keyphone_soft_cancel), null)
                    .show()
            }

            Kind.REDIRECTOR -> {
                KeydroidxConfirmDialog(
                    this,
                    getString(R.string.keyphone_guard_delete_rule_title),
                    getString(R.string.keyphone_guard_delete_rule_message)
                )
                    .setPositiveButton(getString(R.string.keyphone_soft_confirm)) {
                        KeyphoneGuardStore.removeRedirector(this, key.key)
                        Toast.makeText(
                            this,
                            R.string.keyphone_guard_rule_deleted,
                            Toast.LENGTH_SHORT
                        ).show()
                        rebuild()
                    }
                    .setNegativeButton(getString(R.string.keyphone_soft_cancel), null)
                    .show()
            }

            Kind.SCHEME -> {
                val scheme = key.scheme ?: return
                val sourceDomain = key.domain
                KeyphoneOptionSheetDialog(
                    context = this,
                    titleText = scheme,
                    bodyLines = listOf(sourceDomain ?: "-"),
                    options = listOf(
                        KeyphoneViews.QuickMenuItem(
                            "1",
                            getString(R.string.keyphone_guard_op_allow)
                        ) {
                            KeyphoneGuardStore.setSchemeRule(
                                this, scheme, sourceDomain,
                                KeyphoneGuardStore.RuleAction.ALLOW
                            )
                            rebuild()
                        },
                        KeyphoneViews.QuickMenuItem(
                            "2",
                            getString(R.string.keyphone_guard_op_deny)
                        ) {
                            KeyphoneGuardStore.setSchemeRule(
                                this, scheme, sourceDomain,
                                KeyphoneGuardStore.RuleAction.DENY
                            )
                            rebuild()
                        },
                        KeyphoneViews.QuickMenuItem(
                            "3",
                            getString(R.string.keyphone_guard_op_delete)
                        ) {
                            KeyphoneGuardStore.removeSchemeRule(this, scheme, sourceDomain)
                            Toast.makeText(
                                this,
                                R.string.keyphone_guard_rule_deleted,
                                Toast.LENGTH_SHORT
                            ).show()
                            rebuild()
                        }
                    ),
                    defaultFocusIndex = 0,
                    onSelected = { },
                    onCancelled = { }
                ).show()
            }
        }
    }

    /** LSK：清空全部拦截规则（协议 / 站内开关 / 下载站点）。 */
    private fun confirmClearAll() {
        KeydroidxConfirmDialog(
            this,
            getString(R.string.keyphone_guard_clear_title),
            getString(R.string.keyphone_guard_clear_message)
        )
            .setPositiveButton(getString(R.string.keyphone_guard_clear)) {
                try {
                    KeyphoneGuardStore.clearSchemeRules(this)
                    KeyphoneGuardStore.clearJumpRules(this)
                    KeyphoneGuardStore.clearDownloadRules(this)
                    KeyphoneGuardStore.clearRedirectors(this)
                    KeyphoneUrlGuard.resetNotifyThrottle()
                    lastFocusIndex = 0
                    rebuild()
                    Toast.makeText(this, R.string.keyphone_guard_cleared, Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    KeydroidxLog.e(TAG, "清空拦截规则失败", t)
                }
            }
            .setNegativeButton(getString(R.string.keyphone_soft_cancel), null)
            .show()
    }

    /** 空状态 / 指引行（非焦点项）。 */
    private fun hintRow(text: String): TextView {
        val theme = KeydroidxTheme.getCurrentTheme(this)
        val hint = TextView(this)
        hint.text = text
        hint.setTextColor(theme.subTextColor)
        hint.includeFontPadding = false
        KeydroidxFontManager.setTextSizeResource(
            hint,
            io.github.cctyl.nokia.common.R.dimen.keydroidx_font_caption
        )
        hint.setPadding(
            KeydroidxDimens.dp(resources, 12f), KeydroidxDimens.dp(resources, 6f),
            KeydroidxDimens.dp(resources, 8f), KeydroidxDimens.dp(resources, 6f)
        )
        hint.isFocusable = false
        hint.isFocusableInTouchMode = false
        return hint
    }

    override fun onAction(action: Int): Boolean {
        val helper = focusHelper ?: return super.onAction(action)
        return when (action) {
            KeydroidxKeyAction.UP, KeydroidxKeyAction.DOWN -> helper.onDirection(action)
            KeydroidxKeyAction.SELECT -> {
                helper.focusedView?.performClick()
                true
            }

            KeydroidxKeyAction.SOFT_LEFT -> {
                confirmClearAll()
                true
            }

            else -> super.onAction(action)
        }
    }

    companion object {
        private const val TAG = "KeyphoneGuardList"
    }
}
