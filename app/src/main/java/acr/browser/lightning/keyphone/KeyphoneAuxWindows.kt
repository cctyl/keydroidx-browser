package acr.browser.lightning.keyphone

import android.app.Activity
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * 辅助窗口管理：把 WebView 与外壳覆盖层挪进**独立窗口**（spike 阶段只做 Web 窗口）。
 *
 * 为什么：渲染进程烧爆（巨型页面）时，主窗口的帧管线会被拖垮（`IntendedVsync→Vsync`
 * 5.5s、输入 ANR）。WebView 还挂在壳窗口里，壳就陪死。每个窗口有独立帧管线——
 * 把 WebView 挪进独立窗口，烧爆时只有那个窗口卡（页面定格在最后一帧），壳窗口
 * 照常出帧、照常消费按键。
 *
 * 几何：窗口严格等于内容区矩形（[sync]），与 `browserFrameHost` 共用坐标系，零换算。
 */
class KeyphoneAuxWindows(private val activity: Activity) {

    private val wm: WindowManager = activity.windowManager

    /** Web 页面窗口根布局：browserFrameHost（WebView）+ shellOverlay（光标/菜单覆盖层） */
    private val webRoot = FrameLayout(activity)

    private val webLp = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    private var attached = false

    /** 待执行的延迟 attach（token 未就绪时 post 的 addView）；onDestroy 时取消，防 WindowLeaked */
    private var pendingAttach: Runnable? = null

    /** 挂到 WindowManager；browserFrameHost 与 shellOverlay 以 MATCH_PARENT 放满窗口。
     *  onInitViews 阶段 decorView 尚未 attach（windowToken 为空），需要推迟到 token 就绪；
     *  若 Activity 在回调执行前被销毁则放弃 addView（否则 WindowLeaked）。 */
    fun attach(browserFrameHost: FrameLayout, shellOverlay: KeyphoneShellOverlay) {
        if (attached || pendingAttach != null) return
        webRoot.addView(
            browserFrameHost,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        webRoot.addView(
            shellOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val decor = activity.window.decorView
        val token = decor.windowToken
        if (token != null) {
            webLp.token = token
            wm.addView(webRoot, webLp)
            attached = true
        } else {
            val r = Runnable {
                pendingAttach = null
                if (attached || activity.isDestroyed) return@Runnable
                webLp.token = decor.windowToken
                try {
                    wm.addView(webRoot, webLp)
                    attached = true
                } catch (t: Throwable) {
                    // token 仍不可用（极少见）：放弃 attach，WebView 走壳窗口旧路径由调用方处理
                }
            }
            pendingAttach = r
            decor.post(r)
        }
    }

    /** 几何同步：把 Web 窗口对齐到内容区矩形（地址栏下沿 ~ 软键栏上沿） */
    fun sync(contentFrame: View) {
        if (!attached) return
        val loc = IntArray(2)
        contentFrame.getLocationOnScreen(loc)
        webLp.x = loc[0]
        webLp.y = loc[1]
        webLp.width = contentFrame.width
        webLp.height = contentFrame.height
        try {
            wm.updateViewLayout(webRoot, webLp)
        } catch (t: Throwable) {
            // 窗口已 detach 等竞态，忽略
        }
    }

    /** 网页态显示 Web 窗口；主页态隐藏（内容 GONE + 不接收触摸） */
    fun setWebVisible(visible: Boolean) {
        if (!attached) return
        if (visible) {
            webLp.flags = webLp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            webRoot.visibility = View.VISIBLE
        } else {
            webLp.flags = webLp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            webRoot.visibility = View.GONE
        }
        try {
            wm.updateViewLayout(webRoot, webLp)
        } catch (t: Throwable) {
            // 忽略竞态
        }
    }

    fun detach() {
        // 取消还没执行的延迟 attach（token 未就绪时 post 的 addView），
        // 否则回调会在已销毁的 Activity 上加窗口 → WindowLeaked
        pendingAttach?.let { activity.window.decorView.removeCallbacks(it) }
        pendingAttach = null
        if (!attached) return
        attached = false
        try {
            wm.removeView(webRoot)
        } catch (t: Throwable) {
            // 忽略
        }
    }
}
