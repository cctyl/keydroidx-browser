package acr.browser.lightning.keyphone

import acr.browser.lightning.BuildConfig
import acr.browser.lightning.R
import acr.browser.lightning.constant.FILE
import acr.browser.lightning.database.downloads.DownloadEntry
import acr.browser.lightning.database.downloads.DownloadsRepository
import acr.browser.lightning.di.injector
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import io.github.cctyl.nokia.common.log.KeydroidxLog
import io.github.cctyl.nokia.common.model.KeydroidxKeyAction
import io.github.cctyl.nokia.common.ui.KeydroidxIcons
import io.github.cctyl.nokia.common.ui.dialog.KeydroidxConfirmDialog
import io.github.cctyl.nokia.common.ui.page.KeydroidxListFocusHelper
import io.github.cctyl.nokia.keycore.ui.KeydroidxBaseActivity
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * 按键机「下载记录」页（独立 Activity，与 [KeyphoneHistoryActivity] 同款骨架）。
 *
 * 继承 [KeydroidxBaseActivity] 自动获得顶部标题栏与统一的暗色质感。
 * 数据：[DownloadsRepository] 走 AppComponent member injection（[AppComponent.inject]）。
 *
 * 按键与交互：
 * - UP / DOWN：移动光标（循环导航）；
 * - 确认键（SELECT）：打开下载的文件（调用系统查看器/关联应用）；若文件不存在则提示；
 * - 方向左键（LEFT）：删除当前条目及本地文件（确认弹窗）；
 * - LSK（左软键）：清空全部下载记录（确认弹窗）；
 * - RSK（右软键）/ BACK：返回上一页。
 */
class KeyphoneDownloadsActivity : KeydroidxBaseActivity() {

    @Inject
    internal lateinit var downloadsRepository: DownloadsRepository

    private var focusHelper: KeydroidxListFocusHelper? = null
    private var rowsContainer: LinearLayout? = null
    private var scroll: ScrollView? = null
    private val focusables = ArrayList<View>()
    private var entries: List<DownloadEntry> = emptyList()
    private var lastFocusIndex = 0

    override fun getContentLayoutRes(): Int = R.layout.keyphone_list

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须先注入再 super：基类 onCreate 会回调 onInitViews() -> rebuild() 读库
        injector.inject(this)
        super.onCreate(savedInstanceState)
    }

    override fun onInitViews() {
        setPageTitle(getString(R.string.keyphone_downloads_title))
        setTitleIcon(KeydroidxIcons.ICON_DOWNLOAD)
        setStatusBarVisible(false)
        setSoftKeys(
            getString(R.string.keyphone_soft_clear),
            null,
            getString(R.string.keyphone_soft_back)
        )
        scroll = findViewById(R.id.keyphone_scroll)
        rowsContainer = findViewById(R.id.keyphone_rows)
        rebuild()
    }

    /** 从数据库重载下载记录（删除/清空后重新绘制并保持焦点）。 */
    private fun rebuild() {
        lifecycleScope.launch {
            try {
                render(downloadsRepository.getAllDownloads())
            } catch (t: Throwable) {
                KeydroidxLog.e(TAG, "加载下载记录失败", t)
                Toast.makeText(
                    this@KeyphoneDownloadsActivity,
                    R.string.keyphone_downloads_load_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun render(list: List<DownloadEntry>) {
        val container = rowsContainer ?: return
        entries = list
        container.removeAllViews()
        focusables.clear()

        if (list.isEmpty()) {
            container.addView(
                KeyphoneViews.sectionLabel(this, getString(R.string.keyphone_downloads_empty))
            )
            focusHelper = null
            return
        }

        list.forEach { entry ->
            val filePath = entry.location.removePrefix(FILE)
            val file = File(filePath)
            val exists = file.exists()

            val summary = when {
                exists && entry.contentSize.isNotBlank() -> entry.contentSize
                exists -> ""
                entry.contentSize.isNotBlank() ->
                    "${entry.contentSize}  [${getString(R.string.keyphone_downloads_file_lost)}]"
                else -> "[${getString(R.string.keyphone_downloads_file_lost)}]"
            }

            val title = entry.title.ifBlank { file.name.ifBlank { entry.url } }
            val icon = if (exists) KeydroidxIcons.ICON_DOWNLOAD else KeydroidxIcons.ICON_CLOSE
            val row = KeyphoneViews.iconRow(
                this,
                icon,
                null,
                title,
                summary.ifBlank { null }
            )
            row.tag = entry
            row.setOnClickListener {
                lastFocusIndex = focusables.indexOf(row).coerceAtLeast(0)
                focusHelper?.setFocusIndex(lastFocusIndex)
                openDownload(entry)
            }

            container.addView(row)
            focusables.add(row)
        }

        val helper = KeydroidxListFocusHelper(scroll ?: return)
        helper.setItems(focusables)
        helper.setFocusIndex(lastFocusIndex.coerceIn(0, focusables.size - 1))
        focusHelper = helper
    }

    /** 打开下载的文件：通过系统 Intent 调用关联应用打开。 */
    private fun openDownload(entry: DownloadEntry) {
        val filePath = entry.location.removePrefix(FILE)
        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(
                this,
                R.string.keyphone_downloads_file_not_found,
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val ext = MimeTypeMap.getFileExtensionFromUrl(file.name).ifBlank {
            file.extension
        }
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "*/*"

        try {
            val contentUri = FileProvider.getUriForFile(
                this,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeType)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            KeydroidxLog.w(TAG, "未找到可打开此文件的应用: ${file.name}")
            Toast.makeText(
                this,
                R.string.keyphone_downloads_open_failed,
                Toast.LENGTH_SHORT
            ).show()
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "打开下载文件异常: ${entry.location}", t)
            Toast.makeText(
                this,
                R.string.keyphone_downloads_open_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun confirmDeleteFocused() {
        val entry = entries.getOrNull(lastFocusIndex) ?: return
        KeydroidxConfirmDialog(
            this,
            getString(R.string.keyphone_delete_download_title),
            getString(R.string.keyphone_delete_download_message)
        )
            .setPositiveButton(getString(R.string.keyphone_soft_delete)) {
                lifecycleScope.launch {
                    try {
                        downloadsRepository.deleteDownload(entry.location)
                        val filePath = entry.location.removePrefix(FILE)
                        val file = File(filePath)
                        if (file.exists()) {
                            file.delete()
                        }
                        Toast.makeText(
                            this@KeyphoneDownloadsActivity,
                            R.string.keyphone_downloads_deleted,
                            Toast.LENGTH_SHORT
                        ).show()
                        rebuild()
                    } catch (t: Throwable) {
                        KeydroidxLog.e(TAG, "删除下载记录失败: ${entry.location}", t)
                    }
                }
            }
            .setNegativeButton(getString(R.string.keyphone_soft_cancel), null)
            .show()
    }

    private fun confirmClearDownloads() {
        if (entries.isEmpty()) return
        KeydroidxConfirmDialog(
            this,
            getString(R.string.keyphone_clear_downloads_title),
            getString(R.string.keyphone_clear_downloads_message)
        )
            .setPositiveButton(getString(R.string.keyphone_soft_clear)) {
                lifecycleScope.launch {
                    try {
                        downloadsRepository.deleteAllDownloads()
                        Toast.makeText(
                            this@KeyphoneDownloadsActivity,
                            R.string.keyphone_downloads_cleared,
                            Toast.LENGTH_SHORT
                        ).show()
                        lastFocusIndex = 0
                        rebuild()
                    } catch (t: Throwable) {
                        KeydroidxLog.e(TAG, "清空下载记录失败", t)
                    }
                }
            }
            .setNegativeButton(getString(R.string.keyphone_soft_cancel), null)
            .show()
    }

    override fun onAction(action: Int): Boolean {
        val helper = focusHelper ?: return super.onAction(action)
        return when (action) {
            KeydroidxKeyAction.UP, KeydroidxKeyAction.DOWN -> helper.onDirection(action)
            KeydroidxKeyAction.SELECT -> {
                helper.focusedView?.performClick()
                true
            }

            KeydroidxKeyAction.LEFT -> {
                confirmDeleteFocused()
                true
            }

            KeydroidxKeyAction.SOFT_LEFT -> {
                confirmClearDownloads()
                true
            }

            else -> super.onAction(action)
        }
    }

    companion object {
        private const val TAG = "KeyphoneDownloads"
    }
}
