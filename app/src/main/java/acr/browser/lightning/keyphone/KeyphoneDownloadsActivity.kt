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
import io.github.cctyl.nokia.common.ui.KeydroidxUi
import io.github.cctyl.nokia.common.ui.dialog.KeydroidxConfirmDialog
import io.github.cctyl.nokia.common.ui.dialog.KeydroidxOptionsDialog
import io.github.cctyl.nokia.common.ui.page.KeydroidxListFocusHelper
import io.github.cctyl.nokia.common.util.KeydroidxDimens
import io.github.cctyl.nokia.keycore.ui.KeydroidxBaseActivity
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * 按键机「下载记录」页（独立 Activity，严格遵从 FEATURE_PHONE_UI_SPEC 与 keydroidx-core 官方组件）。
 *
 * 继承 [KeydroidxBaseActivity] 自动获得统一深色顶栏与按键解析能力。
 * 数据：[DownloadsRepository] 走 AppComponent 依赖注入。
 * 弹窗组件：全量采用官方 [KeydroidxOptionsDialog] 与 [KeydroidxConfirmDialog]。
 *
 * 按键与交互规范：
 * - UP / DOWN：移动光标（列表循环导航）；
 * - 确认键（SELECT / 5 / Enter）或中软键：打开当前聚焦条目的文件（调用系统查看器/安装器）；
 * - LSK（左软键）：呼出官方标准「选项」菜单（打开文件 / 删除 / 清空全部记录）；
 * - 方向左键（LEFT）或选项菜单中选删除：弹出官方「删除下载」选项弹窗（仅删除记录 / 同时删除文件）；
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
        updateSoftKeys(hasItems = false)
        scroll = findViewById(R.id.keyphone_scroll)
        rowsContainer = findViewById(R.id.keyphone_rows)
        rebuild()
    }

    private fun updateSoftKeys(hasItems: Boolean) {
        setSoftKeys(
            if (hasItems) getString(R.string.keyphone_soft_options) else null,
            if (hasItems) getString(R.string.keyphone_soft_open) else null,
            getString(R.string.keyphone_soft_back)
        )
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
            updateSoftKeys(hasItems = false)
            focusHelper = null
            return
        }

        updateSoftKeys(hasItems = true)

        list.forEach { entry ->
            val filePath = entry.location.removePrefix(FILE)
            val file = File(filePath)
            val summary = entry.contentSize.ifBlank { null }
            val title = entry.title.ifBlank { file.name.ifBlank { entry.url } }

            val row = KeyphoneViews.iconRow(
                this,
                KeydroidxIcons.ICON_DOWNLOAD,
                null,
                title,
                summary
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

    /**
     * 打开下载的文件：通过系统 Intent 调用关联应用/安装器打开。
     */
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
        }.lowercase()

        val mimeType = if (ext == "apk") {
            "application/vnd.android.package-archive"
        } else {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        }

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

    /**
     * 左软键（LSK）呼出官方标准「选项」菜单（KeydroidxOptionsDialog）。
     */
    private fun showOptionsMenu() {
        if (entries.isEmpty()) return

        val focusedEntry = entries.getOrNull(lastFocusIndex) ?: entries[0]
        val currentTheme = KeydroidxUi.getTheme(this)
        val iconSize = KeydroidxDimens.dp(resources, 18f)
        val iconColor = currentTheme.textColor

        KeydroidxOptionsDialog(this, getString(R.string.keyphone_downloads_options_title))
            .addItem(
                ID_OPT_OPEN,
                getString(R.string.keyphone_downloads_opt_open),
                KeydroidxIcons.createDrawable(this, KeydroidxIcons.ICON_DOWNLOAD, iconSize, iconColor)
            )
            .addItem(
                ID_OPT_DELETE,
                getString(R.string.keyphone_downloads_opt_delete),
                KeydroidxIcons.createDrawable(this, KeydroidxIcons.ICON_DELETE, iconSize, iconColor)
            )
            .addItem(
                ID_OPT_CLEAR,
                getString(R.string.keyphone_downloads_opt_clear),
                KeydroidxIcons.createDrawable(this, KeydroidxIcons.ICON_CLEAR_ALL, iconSize, iconColor)
            )
            .setOnOptionSelectedListener { _, item ->
                when (item.id) {
                    ID_OPT_OPEN -> openDownload(focusedEntry)
                    ID_OPT_DELETE -> showDeleteChoiceDialog(focusedEntry)
                    ID_OPT_CLEAR -> confirmClearDownloads()
                }
            }
            .show()
    }

    /**
     * 删除选择弹窗（使用官方标准 KeydroidxOptionsDialog）：
     * 明确让用户选择是「仅删除记录」还是「同时删除文件」。
     * 若用户选择同时删除文件而物理文件已不存在，静默忽略并删除记录。
     */
    private fun showDeleteChoiceDialog(entry: DownloadEntry) {
        val currentTheme = KeydroidxUi.getTheme(this)
        val iconSize = KeydroidxDimens.dp(resources, 18f)
        val iconColor = currentTheme.textColor

        KeydroidxOptionsDialog(this, getString(R.string.keyphone_delete_download_choice_title))
            .addItem(
                ID_DEL_ONLY_RECORD,
                getString(R.string.keyphone_delete_download_only_record),
                KeydroidxIcons.createDrawable(this, KeydroidxIcons.ICON_CLOSE, iconSize, iconColor)
            )
            .addItem(
                ID_DEL_WITH_FILE,
                getString(R.string.keyphone_delete_download_with_file),
                KeydroidxIcons.createDrawable(this, KeydroidxIcons.ICON_DELETE, iconSize, iconColor)
            )
            .setOnOptionSelectedListener { _, item ->
                when (item.id) {
                    ID_DEL_ONLY_RECORD -> executeDelete(entry, deleteFile = false)
                    ID_DEL_WITH_FILE -> executeDelete(entry, deleteFile = true)
                }
            }
            .show()
    }

    /**
     * 执行删除逻辑。
     * @param deleteFile 是否尝试删除本地物理文件；如果文件不存在则静默忽略，只删记录。
     */
    private fun executeDelete(entry: DownloadEntry, deleteFile: Boolean) {
        lifecycleScope.launch {
            try {
                downloadsRepository.deleteDownload(entry.location)
                if (deleteFile) {
                    val filePath = entry.location.removePrefix(FILE)
                    val file = File(filePath)
                    if (file.exists()) {
                        file.delete()
                    }
                    Toast.makeText(
                        this@KeyphoneDownloadsActivity,
                        R.string.keyphone_downloads_deleted_both,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@KeyphoneDownloadsActivity,
                        R.string.keyphone_downloads_deleted,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                rebuild()
            } catch (t: Throwable) {
                KeydroidxLog.e(TAG, "删除下载记录异常: ${entry.location}", t)
            }
        }
    }

    /**
     * 清空全部下载记录（官方标准 KeydroidxConfirmDialog）。
     */
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
            KeydroidxKeyAction.UP, KeydroidxKeyAction.DOWN -> {
                val handled = helper.onDirection(action)
                lastFocusIndex = helper.focusIndex
                handled
            }

            KeydroidxKeyAction.SELECT -> {
                helper.focusedView?.performClick()
                true
            }

            KeydroidxKeyAction.LEFT -> {
                val entry = entries.getOrNull(lastFocusIndex) ?: return true
                showDeleteChoiceDialog(entry)
                true
            }

            KeydroidxKeyAction.SOFT_LEFT -> {
                showOptionsMenu()
                true
            }

            else -> super.onAction(action)
        }
    }

    companion object {
        private const val TAG = "KeyphoneDownloads"

        private const val ID_OPT_OPEN = 1
        private const val ID_OPT_DELETE = 2
        private const val ID_OPT_CLEAR = 3

        private const val ID_DEL_ONLY_RECORD = 10
        private const val ID_DEL_WITH_FILE = 20
    }
}
