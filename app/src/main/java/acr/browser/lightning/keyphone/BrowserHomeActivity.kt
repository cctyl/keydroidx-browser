package acr.browser.lightning.keyphone

import android.content.Intent
import android.os.Bundle
import io.github.cctyl.nokia.keycore.ui.KeydroidxBaseActivity

/**
 * 按键机浏览器主入口代理。
 *
 * 为保持与 AndroidManifest.xml 及历史启动入口兼容，
 * 启动时直接转发至统一的 [KeyphoneBrowserActivity]（该 Activity 已内聚 UC 风格标签栏及主页视图）。
 */
class BrowserHomeActivity : KeydroidxBaseActivity() {

    override fun getContentLayoutRes(): Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forwardIntent = Intent(this, KeyphoneBrowserActivity::class.java).apply {
            action = intent?.action
            data = intent?.data
            intent?.extras?.let { putExtras(it) }
            flags = Intent.FLAG_ACTIVITY_FORWARD_RESULT
        }
        startActivity(forwardIntent)
        finish()
    }

    override fun onInitViews() {
        setStatusBarVisible(false)
    }
}
