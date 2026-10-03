package acr.browser.lightning.browser.tab.settings

import acr.browser.lightning.preference.UserPreferencesDataStore
import acr.browser.lightning.useragent.UserAgentProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Represents the settings used by a tab.
 *
 * @param openAvailableAppsEnabled see [UserPreferencesDataStore.openAvailableAppsEnabled].
 * @param algorithmicDarkeningEnabled see [UserPreferencesDataStore.algorithmicDarkeningEnabled].
 * @param doNotTrackEnabled see [UserPreferencesDataStore.doNotTrackEnabled].
 * @param saveDataEnabled see [UserPreferencesDataStore.saveDataEnabled].
 * @param removeIdentifyingHeadersEnabled see [UserPreferencesDataStore.removeIdentifyingHeadersEnabled].
 * @param textEncoding see [UserPreferencesDataStore.textEncoding].
 * @param renderingMode see [UserPreferencesDataStore.renderingMode].
 * @param locationEnabled see [UserPreferencesDataStore.locationEnabled].
 * @param userAgent see [UserAgentProvider.getUserAgent].
 * @param javaScriptEnabled see [UserPreferencesDataStore.javaScriptEnabled].
 * @param textReflowEnabled see [UserPreferencesDataStore.textReflowEnabled].
 * @param blockImagesEnabled see [UserPreferencesDataStore.blockImagesEnabled].
 * @param popupsEnabled see [UserPreferencesDataStore.popupsEnabled].
 * @param useWideViewPortEnabled see [UserPreferencesDataStore.useWideViewPortEnabled].
 * @param overviewModeEnabled see [UserPreferencesDataStore.overviewModeEnabled].
 * @param textSize see [UserPreferencesDataStore.textSize].
 * @param blockThirdPartyCookiesEnabled see [UserPreferencesDataStore.blockThirdPartyCookiesEnabled].
 */
data class TabSettings(
    val openAvailableAppsEnabled: Boolean,
    val algorithmicDarkeningEnabled: Boolean,
    val doNotTrackEnabled: Boolean,
    val saveDataEnabled: Boolean,
    val removeIdentifyingHeadersEnabled: Boolean,
    val textEncoding: String,
    val renderingMode: RenderingMode,
    val locationEnabled: Boolean,
    val userAgent: String,
    val javaScriptEnabled: Boolean,
    val textReflowEnabled: Boolean,
    val blockImagesEnabled: Boolean,
    val popupsEnabled: Boolean,
    val useWideViewPortEnabled: Boolean,
    val overviewModeEnabled: Boolean,
    val textSize: TextSize,
    val cookiesEnabled: Boolean,
    val blockThirdPartyCookiesEnabled: Boolean,
) {
    companion object {
        /**
         * Construct a [TabSettings] instance from the [UserPreferencesDataStore] and [UserAgentProvider].
         *
         * 这 18 项偏好**并发**取，而不是一项一项 await。原因：调用方
         * （`TabsRepository.createTabUnsafe`）是在 `withContext(coroutineDispatchers.main)`
         * 里等本函数的，而每次 `Preference.get()` 都要走一次「主线程 → IO → 读文件 → 主线程」，
         * 串行 18 次就是 18 轮线程往返；建标签恰好卡在这后面（`loadUrl` 之前），
         * 表现为「点了书签要等一下才出新标签」。并发后 DataStore 对同一文件只做一次读，
         * 其余 `await` 复用同一份内存数据，省掉的是往返与串行等待。
         */
        suspend fun create(
            userPreferencesDataStore: UserPreferencesDataStore,
            userAgentProvider: UserAgentProvider,
        ): TabSettings = coroutineScope {
            val openAvailableApps = async { userPreferencesDataStore.openAvailableAppsEnabled.get() }
            val algorithmicDarkening = async { userPreferencesDataStore.algorithmicDarkeningEnabled.get() }
            val doNotTrack = async { userPreferencesDataStore.doNotTrackEnabled.get() }
            val saveData = async { userPreferencesDataStore.saveDataEnabled.get() }
            val removeIdentifying = async { userPreferencesDataStore.removeIdentifyingHeadersEnabled.get() }
            val textEncoding = async { userPreferencesDataStore.textEncoding.get() }
            val renderingMode = async { userPreferencesDataStore.renderingMode.get() }
            val locationEnabled = async { userPreferencesDataStore.locationEnabled.get() }
            val userAgent = async { userAgentProvider.getUserAgent() }
            val javaScriptEnabled = async { userPreferencesDataStore.javaScriptEnabled.get() }
            val textReflowEnabled = async { userPreferencesDataStore.textReflowEnabled.get() }
            val blockImagesEnabled = async { userPreferencesDataStore.blockImagesEnabled.get() }
            val popupsEnabled = async { userPreferencesDataStore.popupsEnabled.get() }
            val useWideViewPortEnabled = async { userPreferencesDataStore.useWideViewPortEnabled.get() }
            val overviewModeEnabled = async { userPreferencesDataStore.overviewModeEnabled.get() }
            val textSize = async { userPreferencesDataStore.textSize.get() }
            val cookiesEnabled = async { userPreferencesDataStore.cookiesEnabled.get() }
            val blockThirdPartyCookies = async { userPreferencesDataStore.blockThirdPartyCookiesEnabled.get() }

            TabSettings(
                openAvailableAppsEnabled = openAvailableApps.await(),
                algorithmicDarkeningEnabled = algorithmicDarkening.await(),
                doNotTrackEnabled = doNotTrack.await(),
                saveDataEnabled = saveData.await(),
                removeIdentifyingHeadersEnabled = removeIdentifying.await(),
                textEncoding = textEncoding.await(),
                renderingMode = renderingMode.await(),
                locationEnabled = locationEnabled.await(),
                userAgent = userAgent.await(),
                javaScriptEnabled = javaScriptEnabled.await(),
                textReflowEnabled = textReflowEnabled.await(),
                blockImagesEnabled = blockImagesEnabled.await(),
                popupsEnabled = popupsEnabled.await(),
                useWideViewPortEnabled = useWideViewPortEnabled.await(),
                overviewModeEnabled = overviewModeEnabled.await(),
                textSize = textSize.await(),
                cookiesEnabled = cookiesEnabled.await(),
                blockThirdPartyCookiesEnabled = blockThirdPartyCookies.await(),
            )
        }
    }
}
