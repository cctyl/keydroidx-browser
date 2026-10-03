package acr.browser.lightning.browser.tab

import acr.browser.lightning.browser.BrowserContract
import acr.browser.lightning.browser.tab.bundle.BundleStore
import acr.browser.lightning.browser.tab.settings.TabSettings
import acr.browser.lightning.concurrency.AppCoroutineScope
import acr.browser.lightning.concurrency.CoroutineDispatchers
import acr.browser.lightning.constant.SCHEME_BOOKMARKS
import acr.browser.lightning.constant.SCHEME_HISTORY
import acr.browser.lightning.constant.SCHEME_HOMEPAGE
import acr.browser.lightning.di.InitialAction
import acr.browser.lightning.di.RendererBusyProvider
import acr.browser.lightning.extensions.activeTabLimit
import acr.browser.lightning.ids.ViewIdGenerator
import acr.browser.lightning.pool.LimitedObjectPool
import acr.browser.lightning.pool.ObjectPool
import acr.browser.lightning.pool.UnlimitedObjectPool
import acr.browser.lightning.preference.UserPreferencesDataStore
import acr.browser.lightning.search.SearchEngineProvider
import acr.browser.lightning.search.engine.search
import acr.browser.lightning.useragent.UserAgentProvider
import acr.browser.lightning.utils.isFileUrl
import android.app.ActivityManager
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The repository for tabs that implements the [BrowserContract.Model] interface. Manages the state
 * of the tabs list and adding new tabs to it or removing tabs from it.
 */
class TabsRepository @Inject constructor(
    private val webViewFactory: WebViewFactory,
    private val bundleStore: BundleStore,
    private val recentTabModel: RecentTabModel,
    private val tabFactory: TabFactory,
    private val userPreferencesDataStore: UserPreferencesDataStore,
    private val userAgentProvider: UserAgentProvider,
    @InitialAction private val initialAction: BrowserContract.Action?,
    private val permissionInitializerFactory: PermissionInitializer.Factory,
    private val coroutineDispatchers: CoroutineDispatchers,
    private val searchEngineProvider: SearchEngineProvider,
    private val viewIdGenerator: ViewIdGenerator,
    private val activityManager: ActivityManager,
    private val homePageInitializer: HomePageInitializer,
    private val bookmarkPageInitializer: BookmarkPageInitializer,
    private val historyPageInitializer: HistoryPageInitializer,
    private val appCoroutineScope: AppCoroutineScope,
    private val rendererBusyProvider: RendererBusyProvider,
) : BrowserContract.Model {

    private val isInitialized = CompletableDeferred<Unit>()
    private val tabsListStateFlow = MutableStateFlow<List<TabModel>>(emptyList())
    private val saveMutex = Mutex()
    private var pendingSaveJob: Job? = null
    private val webViewPool: Deferred<ObjectPool<WebView>> = appCoroutineScope.async {
        if (userPreferencesDataStore.limitActiveTabs.get()) {
            LimitedObjectPool(
                factory = {
                    val tabSettings =
                        TabSettings.create(userPreferencesDataStore, userAgentProvider)
                    webViewFactory.createWebView(tabSettings)
                },
                poolSize = activityManager.activeTabLimit()
            )
        } else {
            UnlimitedObjectPool(
                factory = {
                    val tabSettings =
                        TabSettings.create(userPreferencesDataStore, userAgentProvider)
                    webViewFactory.createWebView(tabSettings)
                }
            )
        }
    }

    override var selectedTab: TabModel? = null

    override suspend fun deleteTab(id: Int): Unit = withContext(coroutineDispatchers.main) {
        val tab = tabsList.find { it.id == id } ?: return@withContext
        tabsList = tabsList - tab
        if (selectedTab?.id == id) {
            selectedTab = null
        }
        tabsListStateFlow.emit(tabsList)
        scheduleSaveTabs()

        appCoroutineScope.launch(coroutineDispatchers.main) {
            runCatching {
                recentTabModel.addClosedTab(tab.save())
                tab.destroy()
            }
        }
    }

    override suspend fun deleteAllTabs(): Unit = withContext(coroutineDispatchers.main) {
        isInitialized.await()
        if (rendererBusyProvider.isRendererBusy()) {
            val toDestroy = tabsList
            tabsList = emptyList()
            tabsListStateFlow.emit(tabsList)
            scheduleSaveTabs()
            appCoroutineScope.launch(coroutineDispatchers.main) {
                toDestroy.forEach { runCatching { it.destroy() } }
            }
        } else {
            tabsList.forEach { it.destroy() }
            tabsList = emptyList()
            tabsListStateFlow.emit(tabsList)
            scheduleSaveTabs()
        }
    }

    override suspend fun createTab(
        tabInitializer: TabInitializer,
        tabType: TabModel.Type,
        foreground: Boolean
    ): TabModel = withContext(coroutineDispatchers.main) {
        isInitialized.await()
        createTabUnsafe(tabInitializer, tabType, foreground)
    }

    private suspend fun TabInitializer.tabId(): Int = if (this is FreezableInitializer) {
        this.id.takeIf { it != -1 } ?: viewIdGenerator.generateViewId()
    } else {
        viewIdGenerator.generateViewId()
    }

    /**
     * Creates a tab without waiting for the browser to be initialized.
     */
    private suspend fun createTabUnsafe(
        tabInitializer: TabInitializer,
        tabType: TabModel.Type,
        foreground: Boolean,
        emitUpdate: Boolean = true,
    ): TabModel = withContext(coroutineDispatchers.main) {
        val id = tabInitializer.tabId()
        val tabSettings = TabSettings.create(userPreferencesDataStore, userAgentProvider)
        val tabModel = tabFactory.constructTab(
            id = id,
            tabInitializer = tabInitializer,
            webViewPool = webViewPool.await(),
            tabType = tabType,
            tabSettings = tabSettings,
            foreground = foreground,
        )
        tabsList = tabsList + tabModel

        if (emitUpdate) {
            tabsListStateFlow.emit(tabsList)
            scheduleSaveTabs()
        }

        tabModel
    }

    override suspend fun reopenTab(): TabModel? = withContext(coroutineDispatchers.main) {
        recentTabModel.lastClosed()?.let { createTab(BundleInitializer(it)) }
    }

    override fun selectTab(id: Int): TabModel {
        val selected = tabsList.forId(id)
        selectedTab = selected

        return selected
    }

    override var tabsList = emptyList<TabModel>()
        private set

    override fun tabsListChanges(): Flow<List<TabModel>> = tabsListStateFlow

    override suspend fun initializeTabs(): List<TabModel> =
        withContext(coroutineDispatchers.default) {
            val shouldRestore = userPreferencesDataStore.restoreLostTabsEnabled.get()
            val oldTabsDeferred = if (shouldRestore) {
                bundleStore.retrieve()
                    .also { initializers -> viewIdGenerator.claimViewIds(initializers.map { it.id }) }
                    .map {
                        async {
                            createTabUnsafe(
                                tabInitializer = it,
                                tabType = TabModel.Type.NORMAL,
                                foreground = false,
                                emitUpdate = false
                            )
                        }
                    }
            } else {
                bundleStore.deleteAll()
                emptyList()
            }

            val initialUrl = when (initialAction) {
                is BrowserContract.Action.LoadUrl -> initialAction.url
                is BrowserContract.Action.Search -> searchEngineProvider.provideSearchEngine()
                    .search(initialAction.query)

                else -> null
            }
            val newTabInitializer = when {
                initialUrl == null -> null
                // `about:home` / `about:bookmarks` 是「主页」偏好的哨兵值（对外也声明为 about scheme 的
                // VIEW 入口），必须翻译成对应 Initializer 去生成 HTML 页面；当成 URL 直接 loadUrl
                // 只会得到 `chrome://home/` 之类的加载失败页。
                initialUrl == SCHEME_HOMEPAGE -> homePageInitializer
                initialUrl == SCHEME_BOOKMARKS -> bookmarkPageInitializer
                initialUrl == SCHEME_HISTORY -> historyPageInitializer
                // 本地文件必须先走权限确认（安全闸门），不能直接加载
                initialUrl.isFileUrl() -> permissionInitializerFactory.create(initialUrl)
                else -> UrlInitializer(initialUrl)
            }

            // Wait until old tabs are loaded before creating new tab
            val oldTabs = oldTabsDeferred.awaitAll()
            val newTab = newTabInitializer?.let {
                createTabUnsafe(
                    tabInitializer = it,
                    tabType = TabModel.Type.EPHEMERAL,
                    foreground = true,
                    emitUpdate = false
                )
            }

            tabsList = if (newTab != null) {
                oldTabs + newTab
            } else {
                oldTabs
            }
            isInitialized.complete(Unit)

            tabsListStateFlow.emit(tabsList)

            tabsList
        }

    override fun markAllNonEphemeral() {
        tabsList.forEach { it.tabType = TabModel.Type.NORMAL }
    }

    override suspend fun pause() {
        if (userPreferencesDataStore.restoreLostTabsEnabled.get()) {
            // 丢弃尚未触发的防抖落盘，由本次 pause 统一保存，避免重复写盘
            pendingSaveJob?.cancel()
            pendingSaveJob = null
            saveMutex.withLock {
                withContext(coroutineDispatchers.main) {
                    bundleStore.save(tabsList)
                }
            }
        }
        tabsList.forEach { it.background(backgroundAll = true) }
    }

    /**
     * 窗口列表发生变更（新建/关闭）后立即调度一次落盘。
     *
     * 此前唯一的写盘点在 [pause]（onPause 时异步触发），而 adb install / force-stop /
     * 系统低内存杀进程不会执行任何生命周期回调，导致「关闭 A、打开 B 后进程被杀」时，
     * 磁盘上仍是含 A 的旧快照，下次启动恢复出已关闭的窗口。
     *
     * 使用 App 级作用域（而非 Browser 的协程作用域），保证不被 onDestroy 取消；
     * 短时间内的多次变更防抖合并为一次写盘，并通过 [saveMutex] 与 [pause] 互斥。
     */
    private fun scheduleSaveTabs() {
        pendingSaveJob?.cancel()
        pendingSaveJob = appCoroutineScope.launch {
            if (!userPreferencesDataStore.restoreLostTabsEnabled.get()) {
                return@launch
            }
            delay(SAVE_DEBOUNCE_MILLIS)
            saveMutex.withLock {
                withContext(coroutineDispatchers.main) {
                    bundleStore.save(tabsList)
                }
            }
        }
    }

    override suspend fun clean() {
        bundleStore.deleteAll()
    }

    private fun List<TabModel>.forId(id: Int): TabModel = requireNotNull(find { it.id == id })

    private companion object {
        private const val SAVE_DEBOUNCE_MILLIS = 300L
    }
}
