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

    // Web 页面窗口：TYPE_APPLICATION（独立窗口，栈序在基础 Activity 窗口之上）。
    // 不能用 TYPE_APPLICATION_PANEL：面板子窗口在部分 ROM 上不浮到父窗口之上，
    // 被不透明壳窗口盖住 → 黑屏（dumpsys 实证：壳窗口 mTopFullscreenOpaque，栈序高于 Panel）。
    // token 不手动设置：经 Activity 的 WindowManager 添加时自动取 Activity token。
    private val webLp = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_APPLICATION,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    private var attached = false

    /** attach 请求已受理但窗口尚未 addView（等 onResume 时机）；onDestroy 时取消，防 WindowLeaked */
    private var attachPending = false

    /**
     * 组装窗口内容树（browserFrameHost + shellOverlay），但**不在此时 addView**——
     * Activity 自己的窗口到 onResume 才注册，先加的窗口栈序更低，会被不透明壳窗口盖住
     * （黑屏根因）。真正的 addView 推迟到 [onActivityResumed]。
     */
    fun attach(browserFrameHost: FrameLayout, shellOverlay: KeyphoneShellOverlay) {
        if (attached || attachPending) return
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
        attachPending = true
    }

    /** onResume 时机（Activity 窗口已注册）才执行 addView：后注册 → 栈序在壳窗口之上。
     *  onDestroy 之后不能再加（否则 WindowLeaked）。 */
    fun onActivityResumed() {
        if (attachPending && !attached && !activity.isDestroyed) {
            attachPending = false
            try {
                wm.addView(webRoot, webLp)
                attached = true
                android.util.Log.d(
                    "AuxWindows",
                    "webRoot addView webRoot=${webRoot.hashCode()} " +
                        "winToken=${webRoot.windowToken?.hashCode()} attached=${webRoot.isAttachedToWindow} " +
                        "size=${webRoot.width}x${webRoot.height}"
                )
            } catch (t: Throwable) {
                // 极少见：放弃 attach，WebView 走壳窗口旧路径由调用方处理
            }
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
        // 取消还没执行的 attach（等 onResume 时机），防 Activity 销毁后 addView → WindowLeaked
        attachPending = false
        if (!attached) return
        attached = false
        try {
            wm.removeView(webRoot)
        } catch (t: Throwable) {
            // 忽略
        }
    }
}
