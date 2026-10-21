package acr.browser.lightning.keyphone

import android.content.Intent
import android.os.Bundle
import io.github.cctyl.nokia.keycore.ui.KeydroidxBaseActivity

/**
 * 按键机浏览器「回首页」代理。
 *
 * 历史背景：桌面图标（`activity-alias`）曾经指向本 Activity，再由这里转发到
 * [KeyphoneBrowserActivity]。但本 Activity 在 `onCreate` 里转发完立刻 `finish()`，
 * 所以每次从桌面进入都会走一遍「新建浏览页实例 → 从 bundle 按 URL 恢复标签 → 联网重载」，
 * 阅读模式等内存态全部丢失（用户视角：挂机键回桌面再进来，页面又加载了一次）。
 *
 * 现在：
 * - 桌面入口（alias）仍指向本 Activity，但转发改为 `CLEAR_TOP|SINGLE_TOP`（不再用
 *   `FLAG_ACTIVITY_FORWARD_RESULT`）：[KeyphoneBrowserActivity] 是 `singleTask`，转发会命中已有
 *   实例并走 `onNewIntent`，WebView 与阅读现场原封不动（之前每次都在同一任务里新建一个浏览页
 *   实例，再从 bundle 按 URL 恢复标签 → 联网重载、阅读模式丢失）；
 * - 本 Activity 仍承担「新建标签页 / 回首页」转发：调用方只需带
 *   [KeyphoneBrowserActivity.EXTRA_SHOW_HOME]，本页原样透传 extras。
 */
class BrowserHomeActivity : KeydroidxBaseActivity() {

    override fun getContentLayoutRes(): Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forwardIntent = Intent(this, KeyphoneBrowserActivity::class.java).apply {
            action = intent?.action
            data = intent?.data
            intent?.extras?.let { putExtras(it) }
            // 命中已有的 singleTask 浏览页实例（onNewIntent），并清掉其上层选项页 / 历史页；
            // 不能再用 FORWARD_RESULT：它要求目标页面是新建的，复用实例时会被丢弃甚至报错
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try {
            startActivity(forwardIntent)
        } catch (t: Throwable) {
            io.github.cctyl.nokia.common.log.KeydroidxLog.e(TAG, "转发到浏览页失败", t)
        }
        finish()
    }

    override fun onInitViews() {
        setStatusBarVisible(false)
    }

    private companion object {
        private const val TAG = "BrowserHomeActivity"
    }
}
