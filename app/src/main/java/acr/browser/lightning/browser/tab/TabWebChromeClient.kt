package acr.browser.lightning.browser.tab

import acr.browser.lightning.R
import acr.browser.lightning.browser.view.CustomViewCoordinator
import acr.browser.lightning.browser.webrtc.WebRtcPermissionsModel
import acr.browser.lightning.browser.webrtc.WebRtcPermissionsView
import acr.browser.lightning.concurrency.TabCoroutineScope
import acr.browser.lightning.dialog.BrowserDialog
import acr.browser.lightning.dialog.DialogItem
import acr.browser.lightning.extensions.color
import acr.browser.lightning.extensions.resizeAndShow
import acr.browser.lightning.favicon.FaviconModel
import acr.browser.lightning.keyphone.KeyphoneJsDialogs
import acr.browser.lightning.keyphone.KeyphoneUrlGuard
import acr.browser.lightning.preference.UserPreferencesDataStore
import acr.browser.lightning.utils.Utils
import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.result.ActivityResult
import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import androidx.fragment.app.FragmentActivity
import androidx.palette.graphics.Palette
import coil3.decode.DecodeUtils
import coil3.size.Scale
import coil3.size.Size
import com.permissionx.guolindev.PermissionX
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.github.cctyl.nokia.common.log.KeydroidxLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A [WebChromeClient] that supports the tab adaptation.
 */
class TabWebChromeClient @AssistedInject constructor(
    private val activity: FragmentActivity,
    private val faviconModel: FaviconModel,
    private val userPreferencesDataStore: UserPreferencesDataStore,
    private val webRtcPermissionsModel: WebRtcPermissionsModel,
    @Assisted private val tabCoroutineScope: TabCoroutineScope,
    private val customViewCoordinator: CustomViewCoordinator,
) : WebChromeClient(), WebRtcPermissionsView {

    @AssistedFactory
    interface Factory {
        fun create(tabCoroutineScope: TabCoroutineScope): TabWebChromeClient
    }

    private val faviconMaxSize = with(Density(activity)) { 24.dp.roundToPx() }
    private val defaultColor = activity.color(R.color.primary_color)
    private val geoLocationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    /**
     * Emits changes to the page loading progress.
     */
    val progressSharedFlow: MutableSharedFlow<Int> = MutableSharedFlow()

    /**
     * Emits changes to the page title.
     */
    val titleShareFlow: MutableSharedFlow<String> = MutableSharedFlow()

    /**
     * Emits changes to the page favicon.
     */
    val faviconSharedFlow: MutableSharedFlow<TabModel.Favicon.Icon> = MutableSharedFlow()

    /**
     * Emits create window requests.
     */
    val createWindowSharedFlow: MutableSharedFlow<TabInitializer> = MutableSharedFlow()

    /**
     * Emits close window requests.
     */
    val closeWindowSharedFlow: MutableSharedFlow<Unit> = MutableSharedFlow()

    /**
     * Emits changes to the thematic color of the current page.
     */
    val colorChangeStateFlow: MutableStateFlow<Int> = MutableStateFlow(defaultColor)

    /**
     * Emits requests to open the file chooser for upload.
     */
    val fileChooserSharedFlow: MutableSharedFlow<Intent> = MutableSharedFlow()

    /**
     * Emits requests to show a custom view (i.e. full screen video).
     */
    val showCustomViewSharedFlow: MutableSharedFlow<Unit> = MutableSharedFlow()

    /**
     * Emits requests to hide the custom view that was shown prior.
     */
    val hideCustomViewObservable: MutableSharedFlow<Unit> = MutableSharedFlow()

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var customViewCallback: CustomViewCallback? = null


    /**
     * Handle the [activityResult] that was returned by the file chooser.
     */
    fun onResult(activityResult: ActivityResult) {
        val resultCode = activityResult.resultCode
        val intent = activityResult.data
        val result = FileChooserParams.parseResult(resultCode, intent)

        filePathCallback?.onReceiveValue(result)
        filePathCallback = null
    }

    /**
     * Notify the client that we have manually hidden the custom view.
     */
    fun hideCustomView() {
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message
    ): Boolean {
        // 按键机站内跳转拦截：弹新窗口（target="_blank"/window.open）一律在创建前拦截，
        // 该链路不经过 shouldOverrideUrlLoading，且新标签页无来源页上下文
        if (KeyphoneUrlGuard.shouldBlockCreateWindow(view.context, view.url)) {
            return true
        }
        tabCoroutineScope.launch {
            createWindowSharedFlow.emit(ResultMessageInitializer(resultMsg))
        }
        return true
    }

    override fun onCloseWindow(window: WebView) {
        tabCoroutineScope.launch {
            closeWindowSharedFlow.emit(Unit)
        }
    }

    // ------------------------------------------------------------------
    // 网页 JS 弹窗：按键机浏览页在场时一律接管
    //
    // WebView 默认实现弹的是**独立窗口的系统模态对话框**，实测会把宿主按键全部夺走
    // （`*` 菜单打不开、方向键光标不动、标签栏也点不了）——广告站只靠弹窗就能把浏览器
    // 钉死。接管后由宿主用键控组件承载，页面永远夺不走按键。
    // 未注册宿主（上游触屏浏览器）时返回 false，行为与改造前完全一致。
    // ------------------------------------------------------------------

    override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
        KeyphoneJsDialogs.handleAlert(view, url, message, result) ||
            super.onJsAlert(view, url, message, result)

    override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
        KeyphoneJsDialogs.handleConfirm(view, url, message, result) ||
            super.onJsConfirm(view, url, message, result)

    override fun onJsPrompt(
        view: WebView,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult
    ): Boolean = KeyphoneJsDialogs.handlePrompt(view, url, message, defaultValue, result) ||
        super.onJsPrompt(view, url, message, defaultValue, result)

    override fun onJsBeforeUnload(
        view: WebView,
        url: String?,
        message: String?,
        result: JsResult
    ): Boolean = KeyphoneJsDialogs.handleBeforeUnload(view, url, message, result) ||
        super.onJsBeforeUnload(view, url, message, result)

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        tabCoroutineScope.launch {
            progressSharedFlow.emit(newProgress)
        }
    }

    /**
     * 页面控制台消息（仅 ERROR 级）。
     *
     * 阅读模式是「把正文提纯后重排」的原地改 DOM 操作，一旦页面脚本报错（站点自己的定时器 /
     * 观察器与我们的重排打架），正文可能被脚本清空——这属于「文字突然全没了」的候选原因之一，
     * 而控制台消息默认只在 logcat 的 `chromium` tag 里，且会被系统日志冲掉。这里把页面自身的
     * ERROR 落盘（WARN 及以下太吵，不记录）。
     */
    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        try {
            if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                KeydroidxLog.w(
                    TAG,
                    "页面控制台错误: ${consoleMessage.message()} " +
                        "@${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
                )
            }
        } catch (t: Throwable) {
            KeydroidxLog.w(TAG, "记录页面控制台消息失败", t)
        }
        return super.onConsoleMessage(consoleMessage)
    }

    override fun onReceivedTitle(view: WebView, title: String) {
        tabCoroutineScope.launch {
            titleShareFlow.emit(title)
            generateColorAndPropagate(null)
        }
    }

    override fun onReceivedIcon(view: WebView, icon: Bitmap) {
        tabCoroutineScope.launch {
            val scaledBitmap = if (icon.width > faviconMaxSize || icon.height > faviconMaxSize) {
                val multiplier = DecodeUtils.computeSizeMultiplier(
                    srcWidth = icon.width,
                    srcHeight = icon.height,
                    dstWidth = faviconMaxSize,
                    dstHeight = faviconMaxSize,
                    scale = Scale.FIT,
                    maxSize = Size.ORIGINAL
                )
                val width = (multiplier * icon.width).roundToInt()
                val height = (multiplier * icon.height).roundToInt()
                try {
                    icon.scale(width, height)
                } finally {
                    icon.recycle()
                }
            } else {
                icon
            }
            faviconSharedFlow.emit(TabModel.Favicon.Icon(scaledBitmap.asImageBitmap()))
            val url = view.url ?: return@launch
            faviconModel.cacheFaviconForUrl(scaledBitmap, url)
            generateColorAndPropagate(scaledBitmap)
        }
    }

    private suspend fun generateColorAndPropagate(favicon: Bitmap?) {
        val icon = favicon ?: return run {
            colorChangeStateFlow.emit(defaultColor)
        }
        Palette.from(icon).generate { palette ->
            // OR with opaque black to remove transparency glitches
            val color = Color.BLACK or (palette?.getDominantColor(defaultColor) ?: defaultColor)

            // Lighten up the dark color if it is too dark
            val finalColor = if (Utils.isColorTooDark(color)) {
                Utils.mixTwoColors(defaultColor, color, 0.25f)
            } else {
                color
            }
            tabCoroutineScope.launch {
                colorChangeStateFlow.emit(finalColor)
            }
        }
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        // Ensure that previously set callbacks are resolved.
        this.filePathCallback?.onReceiveValue(null)
        this.filePathCallback = null

        this.filePathCallback = filePathCallback
        tabCoroutineScope.launch {
            fileChooserSharedFlow.emit(fileChooserParams.createIntent())
        }
        return true
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        customViewCallback = callback
        tabCoroutineScope.launch {
            showCustomViewSharedFlow.emit(Unit)
        }
        customViewCoordinator.showCustomView(view)
    }

    override fun onHideCustomView() {
        tabCoroutineScope.launch {
            hideCustomViewObservable.emit(Unit)
        }
        customViewCoordinator.hideCustomView()
        customViewCallback = null
    }

    override fun requestPermissions(permissions: Set<String>, onGrant: (Boolean) -> Unit) {
        val missingPermissions = permissions
            .filter { !PermissionX.isGranted(activity, it) }

        if (missingPermissions.isEmpty()) {
            onGrant(true)
        } else {
            PermissionX.init(activity).permissions(missingPermissions)
                .request { allGranted, _, _ ->
                    if (allGranted) {
                        onGrant(true)
                    } else {
                        onGrant(false)
                    }
                }
        }
    }

    override fun requestResources(
        source: String,
        resources: Array<String>,
        onGrant: (Boolean) -> Unit
    ) {
        activity.runOnUiThread {
            val resourcesString = resources.joinToString(separator = "\n")
            BrowserDialog.showPositiveNegativeDialog(
                activity = activity,
                title = R.string.title_permission_request,
                message = R.string.message_permission_request,
                messageArguments = arrayOf(source, resourcesString),
                positiveButton = DialogItem(title = R.string.action_allow) { onGrant(true) },
                negativeButton = DialogItem(title = R.string.action_dont_allow) { onGrant(false) },
                onCancel = { onGrant(false) }
            )
        }
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        tabCoroutineScope.launch {
            if (userPreferencesDataStore.webRtcEnabled.get()) {
                webRtcPermissionsModel.requestPermission(request, this@TabWebChromeClient)
            } else {
                request.deny()
            }
        }
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback
    ) {
        PermissionX.init(activity).permissions(geoLocationPermissions.toList())
            .request { allGranted, _, _ ->
                if (allGranted) {
                    val remember = true
                    AlertDialog.Builder(activity).apply {
                        setTitle(activity.getString(R.string.location))
                        val org = if (origin.length > 50) {
                            "${origin.subSequence(0, 50)}..."
                        } else {
                            origin
                        }
                        setMessage(org + activity.getString(R.string.message_location))
                        setCancelable(true)
                        setPositiveButton(activity.getString(R.string.action_allow)) { _, _ ->
                            callback.invoke(origin, true, remember)
                        }
                        setNegativeButton(activity.getString(R.string.action_dont_allow)) { _, _ ->
                            callback.invoke(origin, false, remember)
                        }
                    }.resizeAndShow()
                } else {
                    //TODO show message and/or turn off setting
                }
            }
    }

    private companion object {

        /** 日志 TAG（页面控制台错误等诊断用） */
        const val TAG = "TabWebChromeClient"
    }
}
