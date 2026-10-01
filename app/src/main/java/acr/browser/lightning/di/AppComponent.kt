package acr.browser.lightning.di

import acr.browser.lightning.BrowserApp
import acr.browser.lightning.device.BuildInfo
import android.app.Application
import dagger.BindsInstance
import dagger.Component
import dagger.Module
import javax.inject.Singleton

@Singleton
@Component(modules = [AppModule::class, AppBindsModule::class, Submodules::class])
interface AppComponent {

    @Component.Builder
    interface Builder {

        @BindsInstance
        fun application(application: Application): Builder

        @BindsInstance
        fun buildInfo(buildInfo: BuildInfo): Builder

        @BindsInstance
        fun incognitoMode(@FullIncognitoMode incognitoMode: Boolean): Builder

        fun build(): AppComponent
    }

    fun inject(app: BrowserApp)

    /** 按键机首页（keyphone 适配层）注入书签/历史仓库 */
    fun inject(activity: acr.browser.lightning.keyphone.BrowserHomeActivity)

    /** 按键机历史记录页（独立 Activity）注入历史仓库 */
    fun inject(activity: acr.browser.lightning.keyphone.KeyphoneHistoryActivity)

    fun browserComponentBuilder(): BrowserComponent.Builder

    fun settingsComponentBuilder(): SettingsComponent.Builder

}

@Module(subcomponents = [BrowserComponent::class, SettingsComponent::class])
internal class Submodules
