package acr.browser.lightning

import acr.browser.lightning.keyphone.KeyphoneBrowserActivity
import android.content.Intent
import android.os.Bundle
import io.github.cctyl.nokia.common.log.KeydroidxLog
import io.github.cctyl.nokia.keycore.ui.KeydroidxBaseActivity

/**
 * 历史触屏版浏览主入口兼容代理。
 *
 * 当第三方应用或系统历史记录通过显式 ComponentName（acr.browser.lightning/.DefaultBrowserActivity）
 * 调用时，原样将 action、data、extras 透传给 [KeyphoneBrowserActivity] 并立即销毁自身，
 * 保证所有外部流量统一进入按键机诺基亚风格界面。
 */
class DefaultBrowserActivity : KeydroidxBaseActivity() {

    override fun getContentLayoutRes(): Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forwardIntent = Intent(this, KeyphoneBrowserActivity::class.java).apply {
            action = intent?.action
            data = intent?.data
            intent?.extras?.let { putExtras(it) }
            // 命中已有的 singleTask 浏览页实例（onNewIntent），并清掉其上层选项页 / 历史页
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        KeydroidxLog.d(
            TAG,
            "转发外部显式调用到 KeyphoneBrowserActivity: action=${intent?.action} data=${intent?.data}"
        )
        try {
            startActivity(forwardIntent)
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "转发外部链接到浏览页失败", t)
        }
        finish()
    }

    override fun onInitViews() {
        setStatusBarVisible(false)
    }

    private companion object {
        private const val TAG = "DefaultBrowserActivity"
    }
}

