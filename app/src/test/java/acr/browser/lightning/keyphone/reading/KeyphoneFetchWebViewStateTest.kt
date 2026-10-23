package acr.browser.lightning.keyphone.reading

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * 取章 WebView 的状态机（方案 B 的重建判据）。
 *
 * 这些断言就是「渲染进程被杀后能不能自动恢复」的规格：
 * 死亡 → 下次取章必须重建；外壳还在加载 → 只等，不重复加载。
 */
class KeyphoneFetchWebViewStateTest {

    @Test
    fun `初始状态未就绪`() {
        val state = FetchWebViewState()
        assertThat(state.ready).isFalse()
        assertThat(state.dead).isFalse()
        assertThat(state.shellUrl).isNull()
    }

    @Test
    fun `载入外壳过程中仍未就绪，加载完才 ready`() {
        val state = FetchWebViewState()
        state.onShellLoading("https://a.com/1.html")
        assertThat(state.ready).isFalse()
        assertThat(state.loadingShellUrl()).isEqualTo("https://a.com/1.html")

        state.onShellLoaded("https://a.com/1.html")
        assertThat(state.ready).isTrue()
        assertThat(state.shellUrl).isEqualTo("https://a.com/1.html")
        assertThat(state.loadingShellUrl()).isNull()
    }

    @Test
    fun `渲染进程死亡后必须重建`() {
        val state = FetchWebViewState()
        state.onShellLoaded("https://a.com/1.html")
        assertThat(state.ready).isTrue()

        state.onRendererGone()
        assertThat(state.dead).isTrue()
        assertThat(state.ready).isFalse()
        assertThat(state.shellUrl).isNull()
    }

    @Test
    fun `重建成功后回到就绪`() {
        val state = FetchWebViewState()
        state.onRendererGone()
        state.onShellLoading("https://a.com/1.html")
        state.onShellLoaded("https://a.com/1.html")
        assertThat(state.dead).isFalse()
        assertThat(state.ready).isTrue()
    }

    @Test
    fun `外壳加载失败不算就绪，也不算死亡`() {
        val state = FetchWebViewState()
        state.onShellLoading("https://a.com/1.html")
        state.onShellLoadFailed()
        assertThat(state.ready).isFalse()
        assertThat(state.dead).isFalse()
        assertThat(state.shellUrl).isNull()
    }

    @Test
    fun `reset 清空全部状态`() {
        val state = FetchWebViewState()
        state.onShellLoaded("https://a.com/1.html")
        state.reset()
        assertThat(state.ready).isFalse()
        assertThat(state.dead).isFalse()
        assertThat(state.shellUrl).isNull()
        assertThat(state.loadingShellUrl()).isNull()
    }
}
