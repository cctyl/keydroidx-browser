package acr.browser.lightning.settings.screens

import acr.browser.lightning.BuildConfig
import acr.browser.lightning.R
import acr.browser.lightning.resources.ResourceProvider
import acr.browser.lightning.settings.framework.ClickableOnClick
import acr.browser.lightning.settings.framework.ClickableState
import acr.browser.lightning.settings.framework.SettingsFrameworkState
import acr.browser.lightning.settings.navigation.SettingsNavigation
import javax.inject.Inject

class AboutSettingsScreen @Inject constructor(
    private val resourceProvider: ResourceProvider,
) {
    fun createSettingsFrameworkState(): SettingsFrameworkState = SettingsFrameworkState(
        title = resourceProvider.stringResource(R.string.settings_about),
        content = listOf(
            ClickableState(
                title = resourceProvider.stringResource(R.string.action_follow_me),
                summary = { "https://bsky.app/profile/anthonycr.bsky.social" },
                onClick = ClickableOnClick.WebLink("https://bsky.app/profile/anthonycr.bsky.social")
            ),
            ClickableState(
                title = resourceProvider.stringResource(R.string.version),
                summary = { BuildConfig.VERSION_NAME },
                onClick = ClickableOnClick.Action {}
            ),
            // 上游署名：本项目基于 Anthony Restaino 的 Lightning Browser（MPL 2.0）分叉，
            // 代码可复用但名称/图标不随许可证授权，故在关于页显式标注来源。
            ClickableState(
                title = resourceProvider.stringResource(R.string.upstream_project),
                summary = { resourceProvider.stringResource(R.string.upstream_author) },
                onClick = ClickableOnClick.WebLink("https://github.com/anthonycr/Lightning-Browser")
            ),
            ClickableState(
                title = resourceProvider.stringResource(R.string.app_name),
                summary = { resourceProvider.stringResource(R.string.mpl_license) },
                onClick = ClickableOnClick.WebLink("http://www.mozilla.org/MPL/2.0/")
            ),
            ClickableState(
                title = resourceProvider.stringResource(R.string.android_open_source_project),
                summary = { resourceProvider.stringResource(R.string.apache) },
                onClick = ClickableOnClick.WebLink("http://www.apache.org/licenses/LICENSE-2.0")
            ),
            ClickableState(
                title = resourceProvider.stringResource(R.string.hphosts_ad_server_list),
                summary = { resourceProvider.stringResource(R.string.freeware) },
                onClick = ClickableOnClick.WebLink("http://hosts-file.net/")
            ),
        )
    )
}
