package acr.browser.lightning.keyphone

import acr.browser.lightning.SDK_VERSION
import acr.browser.lightning.TestApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for [KeyphoneReadingSessionStore].
 *
 * 覆盖阅读会话落盘 / 按标签 URL 命中 / 主动清空这三条恢复链路依赖的行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [SDK_VERSION])
class KeyphoneReadingSessionStoreTest {

    private val context: android.app.Application = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        KeyphoneReadingSessionStore.clear(context)
    }

    @Test
    fun `load returns null when nothing saved`() {
        assertThat(KeyphoneReadingSessionStore.load(context, "https://example.com/ch1")).isNull()
    }

    @Test
    fun `save then load by entry url returns last chapter`() {
        KeyphoneReadingSessionStore.save(
            context,
            entryUrl = "https://example.com/ch1",
            lastUrl = "https://example.com/ch59",
            chapterCount = 59
        )

        val snapshot = KeyphoneReadingSessionStore.load(context, "https://example.com/ch1")

        assertThat(snapshot).isNotNull
        assertThat(snapshot!!.entryUrl).isEqualTo("https://example.com/ch1")
        assertThat(snapshot.lastUrl).isEqualTo("https://example.com/ch59")
        assertThat(snapshot.chapterCount).isEqualTo(59)
    }

    @Test
    fun `load matches last url as well as entry url`() {
        KeyphoneReadingSessionStore.save(
            context,
            entryUrl = "https://example.com/ch1",
            lastUrl = "https://example.com/ch59",
            chapterCount = 59
        )

        assertThat(KeyphoneReadingSessionStore.load(context, "https://example.com/ch59")?.lastUrl)
            .isEqualTo("https://example.com/ch59")
    }

    @Test
    fun `blank last url falls back to entry url`() {
        KeyphoneReadingSessionStore.save(
            context,
            entryUrl = "https://example.com/ch1",
            lastUrl = "",
            chapterCount = 1
        )

        assertThat(KeyphoneReadingSessionStore.load(context, "https://example.com/ch1")?.lastUrl)
            .isEqualTo("https://example.com/ch1")
    }

    @Test
    fun `saving same entry url overwrites previous record`() {
        KeyphoneReadingSessionStore.save(context, "https://example.com/ch1", "https://example.com/ch2", 2)
        KeyphoneReadingSessionStore.save(context, "https://example.com/ch1", "https://example.com/ch9", 9)

        val snapshot = KeyphoneReadingSessionStore.load(context, "https://example.com/ch1")

        assertThat(snapshot?.lastUrl).isEqualTo("https://example.com/ch9")
        assertThat(snapshot?.chapterCount).isEqualTo(9)
    }

    @Test
    fun `clear drops every session`() {
        KeyphoneReadingSessionStore.save(context, "https://example.com/ch1", "https://example.com/ch9", 9)

        KeyphoneReadingSessionStore.clear(context)

        assertThat(KeyphoneReadingSessionStore.load(context, "https://example.com/ch1")).isNull()
        assertThat(KeyphoneReadingSessionStore.load(context, "https://example.com/ch9")).isNull()
    }

    @Test
    fun `non http entry url is ignored`() {
        KeyphoneReadingSessionStore.save(context, "", "https://example.com/ch9", 9)

        assertThat(KeyphoneReadingSessionStore.load(context, "https://example.com/ch9")).isNull()
    }

    @Test
    fun `old sessions are not restored`() {
        // 手工写入一条 25 小时前的记录（超过 STALE_MS）
        val stale = "https://example.com/ch1\thttps://example.com/ch9\t9\t" +
            (System.currentTimeMillis() - 25L * 60 * 60 * 1000)
        context.applicationContext
            .getSharedPreferences("keyphone_reading_session", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("sessions", stale)
            .commit()

        assertThat(KeyphoneReadingSessionStore.load(context, "https://example.com/ch1")).isNull()
    }
}
