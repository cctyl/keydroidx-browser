package acr.browser.lightning

import acr.browser.lightning.concurrency.AppCoroutineScope
import acr.browser.lightning.database.bookmark.BookmarkExporter
import acr.browser.lightning.database.bookmark.BookmarkRepository
import acr.browser.lightning.device.BuildInfo
import acr.browser.lightning.device.BuildType
import acr.browser.lightning.di.AppComponent
import acr.browser.lightning.di.DaggerAppComponent
import acr.browser.lightning.di.injector
import acr.browser.lightning.migration.Cleanup
import acr.browser.lightning.utils.FileUtils
import acr.browser.lightning.utils.LeakCanaryUtils
import android.app.Application
import android.os.Build
import android.os.StrictMode
import android.webkit.WebView
import io.github.cctyl.nokia.common.log.KeydroidxLog
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.system.exitProcess

/**
 * The browser application.
 */
class BrowserApp : Application() {

    @Inject
    internal lateinit var leakCanaryUtils: LeakCanaryUtils

    @Inject
    internal lateinit var bookmarkModel: BookmarkRepository

    @Inject
    internal lateinit var buildInfo: BuildInfo

    @Inject
    internal lateinit var cleanup: Cleanup

    @Inject
    internal lateinit var appCoroutineScope: AppCoroutineScope

    @Inject
    internal lateinit var bookmarkExporter: BookmarkExporter

    lateinit var applicationComponent: AppComponent

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }

        val isIncognito = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName() == "$packageName:incognito"
        } else {
            false
        }

        // 生态标准文件日志器（keydroidx-common 的 KeydroidxLog）：按天落盘
        // `Android/data/<包名>/files/log/yyyyMMdd.log`（外部目录不可用时回退内部 `files/log`），
        // 保留 7 天；debug 包默认「详细日志」全开，release 只在 ERROR / 崩溃时落盘。
        //
        // 为什么本项目必须显式 init：KeydroidxLog 未 init 时只写 logcat，而本机型 logcat
        // 三个缓冲区各只有 256 KiB，系统自身日志十几分钟就会把应用日志冲掉——复现
        // 「阅读模式黑屏」时崩溃前的现场一条都取不到（2026-10-19 两次复现即此结果）。
        // init 必须早于全局异常处理器安装，崩溃栈才会写进当天文件。
        if (!isIncognito) {
            KeydroidxLog.setTag(PACKAGE_NAME_LOG_TAG)
            KeydroidxLog.init(this)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isIncognito) {
            File(dataDir, "app_webview_incognito").deleteRecursively()
            WebView.setDataDirectorySuffix("incognito")
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        // 注意：KeydroidxLog.init 之后不要再调用 installCrashHandler——它会覆盖这个处理器，
        // 让 FileUtils.writeCrashToStorage（debug 崩栈留档）失效；崩溃栈由 KeydroidxLog.e
        // 记录后交给原处理器继续抛，两者兼顾。
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            KeydroidxLog.e(
                "BrowserApp",
                "未捕获异常 thread=${thread.name}: ${ex::class.java.name}: ${ex.message}",
                ex
            )
            if (BuildConfig.DEBUG) {
                FileUtils.writeCrashToStorage(ex)
            }

            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, ex)
            } else {
                exitProcess(2)
            }
        }

        applicationComponent = DaggerAppComponent.builder()
            .application(this)
            .buildInfo(createBuildInfo())
            .incognitoMode(isIncognito)
            .build()
        injector.inject(this)

        appCoroutineScope.launch {
            cleanup.cleanup()
        }

        appCoroutineScope.launch {
            if (bookmarkModel.count() == 0L) {
                val assetsBookmarks = bookmarkExporter.importBookmarksFromAssets()
                bookmarkModel.addBookmarkList(assetsBookmarks)
            }
        }

        if (buildInfo.buildType == BuildType.DEBUG) {
            leakCanaryUtils.setup()
        }

        if (buildInfo.buildType == BuildType.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        appCoroutineScope.cancel()
    }

    /**
     * Create the [BuildType] from the [BuildConfig].
     */
    private fun createBuildInfo() = BuildInfo(
        packageName = packageName,
        buildType = when {
            BuildConfig.DEBUG -> BuildType.DEBUG
            else -> BuildType.RELEASE
        },
        versionCode = BuildConfig.VERSION_CODE,
        isPlus = BuildConfig.FULL_VERSION,
    )

    companion object {
        private const val TAG = "BrowserApp"

        /** KeydroidxLog 的默认 TAG（未显式传 TAG 的调用会用它；与 logcat 主 TAG 保持一致） */
        private const val PACKAGE_NAME_LOG_TAG = "KeyphoneBrowser"
    }
}
