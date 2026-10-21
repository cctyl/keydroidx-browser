package acr.browser.lightning.keyphone

import acr.browser.lightning.BuildConfig
import acr.browser.lightning.R
import acr.browser.lightning.constant.FILE
import acr.browser.lightning.database.downloads.DownloadEntry
import acr.browser.lightning.database.downloads.DownloadsRepository
import acr.browser.lightning.di.injector
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 按键机「下载记录」页（独立 Activity，严格遵从 FEATURE_PHONE_UI_SPEC 与 keydroidx-core 官方组件）。
 *
 * 继承 [KeydroidxBaseActivity] 自动获得统一深色顶栏与按键解析能力。
 * 数据：[DownloadsRepository] 持久化历史 + 系统 [DownloadManager] 实时进度联动。
 * 弹窗组件：全量采用官方 [KeydroidxOptionsDialog] 与 [KeydroidxConfirmDialog]。
 *
 * 按键与交互规范：
 * - UP / DOWN：移动光标（列表循环导航），底栏中软键根据当前项智能切换（进行中为「取消」，完成为「打开」）；
 * - 确认键（SELECT / 5 / Enter）或中软键：
 *   - 进行中项：直接取消该下载任务；
 *   - 完成项：打开文件（系统查看器/安装器）；
 * - LSK（左软键）：呼出官方标准「选项」菜单；
 * - 方向左键（LEFT）：针对完成项弹出删除弹窗，针对进行中项弹出取消确认；
 * - RSK（右软键）/ BACK：返回上一页。
 */
class KeyphoneDownloadsActivity : KeydroidxBaseActivity() {

    @Inject
    internal lateinit var downloadsRepository: DownloadsRepository

    @Inject
    internal lateinit var downloadManager: DownloadManager

    private var focusHelper: KeydroidxListFocusHelper? = null
    private var rowsContainer: LinearLayout? = null
    private var scroll: ScrollView? = null
    private val focusables = ArrayList<View>()
    private var uiItems: List<DownloadItemUi> = emptyList()
    private val activeRowMap = HashMap<Long, KeyphoneViews.DownloadProgressRow>()
    private var lastFocusIndex = 0
    private var pollingJob: Job? = null

    override fun getContentLayoutRes(): Int = R.layout.keyphone_list

    override fun onCreate(savedInstanceState: Bundle?) {
        injector.inject(this)
        super.onCreate(savedInstanceState)
    }

    override fun onInitViews() {
        setPageTitle(getString(R.string.keyphone_downloads_title))
        setTitleIcon(KeydroidxIcons.ICON_DOWNLOAD)
        setStatusBarVisible(false)
        updateSoftKeys(focusedItem = null)
        scroll = findViewById(R.id.keyphone_scroll)
        rowsContainer = findViewById(R.id.keyphone_rows)
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        startPollingIfNecessary()
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
    }

    private fun updateSoftKeys(focusedItem: DownloadItemUi?) {
        if (focusedItem == null) {
            setSoftKeys(null, null, getString(R.string.keyphone_soft_back))
            return
        }

        val centerText = when (focusedItem) {
            is DownloadItemUi.Active -> getString(R.string.keyphone_downloads_opt_cancel)
            is DownloadItemUi.Completed -> getString(R.string.keyphone_soft_open)
        }

        setSoftKeys(
            getString(R.string.keyphone_soft_options),
            centerText,
            getString(R.string.keyphone_soft_back)
        )
    }

    /**
     * 重载列表数据：融合系统 [DownloadManager] 活跃下载任务与数据库已完成历史。
     */
    private fun rebuild() {
        lifecycleScope.launch {
            try {
                val dbDownloads = downloadsRepository.getAllDownloads()
                val activeDownloads = queryActiveDownloads()

                // 活跃下载任务对应的 URL/文件路径集合，用于去重
                val activeKeys = HashSet<String>()
                activeDownloads.forEach {
                    if (it.url.isNotBlank()) activeKeys.add(it.url)
                    if (it.location.isNotBlank()) activeKeys.add(it.location)
                }

                val mergedList = ArrayList<DownloadItemUi>()
                // 1. 进行中的任务排在最前
                activeDownloads.forEach { mergedList.add(it) }

                // 2. 数据库完成的历史记录（排除与活跃下载重复的行）
                dbDownloads.forEach { entry ->
                    if (!activeKeys.contains(entry.url) && !activeKeys.contains(entry.location)) {
                        mergedList.add(DownloadItemUi.Completed(entry))
                    }
                }

                render(mergedList)
                startPollingIfNecessary()
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

    private fun render(list: List<DownloadItemUi>) {
        val container = rowsContainer ?: return
        uiItems = list
        container.removeAllViews()
        focusables.clear()
        activeRowMap.clear()

        if (list.isEmpty()) {
            container.addView(
                KeyphoneViews.sectionLabel(this, getString(R.string.keyphone_downloads_empty))
            )
            updateSoftKeys(null)
            focusHelper = null
            return
        }

        list.forEach { item ->
            when (item) {
                is DownloadItemUi.Active -> {
                    val statusText = formatStatusText(item)
                    val pRow = KeyphoneViews.downloadProgressRow(
                        this,
                        item.title,
                        statusText,
                        item.progress,
                        item.isIndeterminate
                    )
                    pRow.row.tag = item
                    pRow.row.setOnClickListener {
                        val idx = focusables.indexOf(pRow.row).coerceAtLeast(0)
                        onFocusItemChanged(idx)
                        cancelDownload(item)
                    }
                    container.addView(pRow.row)
                    focusables.add(pRow.row)
                    activeRowMap[item.id] = pRow
                }

                is DownloadItemUi.Completed -> {
                    val filePath = item.entry.location.removePrefix(FILE)
                    val file = File(filePath)
                    val summary = item.entry.contentSize.ifBlank { null }
                    val title = item.entry.title.ifBlank { file.name.ifBlank { item.entry.url } }

                    val row = KeyphoneViews.iconRow(
                        this,
                        KeydroidxIcons.ICON_DOWNLOAD,
                        null,
                        title,
                        summary
                    )
                    row.tag = item
                    row.setOnClickListener {
                        val idx = focusables.indexOf(row).coerceAtLeast(0)
                        onFocusItemChanged(idx)
                        openDownload(item.entry)
                    }
                    container.addView(row)
                    focusables.add(row)
                }
            }
        }

        val helper = KeydroidxListFocusHelper(scroll ?: return)
        helper.setItems(focusables)
        val validIndex = lastFocusIndex.coerceIn(0, focusables.size - 1)
        helper.setFocusIndex(validIndex)
        focusHelper = helper

        onFocusItemChanged(validIndex)
    }

    private fun onFocusItemChanged(index: Int) {
        lastFocusIndex = index
        focusHelper?.setFocusIndex(index)
        val item = uiItems.getOrNull(index)
        updateSoftKeys(item)
    }

    /**
     * 查询系统 DownloadManager 中正在进行的活跃任务。
     */
    private fun queryActiveDownloads(): List<DownloadItemUi.Active> {
        val list = ArrayList<DownloadItemUi.Active>()
        val query = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_RUNNING or
            DownloadManager.STATUS_PENDING or
            DownloadManager.STATUS_PAUSED
        )
        try {
            downloadManager.query(query)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                val titleIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val uriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_URI)
                val localUriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val bytesIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)

                while (cursor.moveToNext()) {
                    val id = if (idIdx >= 0) cursor.getLong(idIdx) else -1L
                    val title = if (titleIdx >= 0) cursor.getString(titleIdx) ?: "" else ""
                    val uri = if (uriIdx >= 0) cursor.getString(uriIdx) ?: "" else ""
                    val localUri = if (localUriIdx >= 0) cursor.getString(localUriIdx) ?: "" else ""
                    val bytes = if (bytesIdx >= 0) cursor.getLong(bytesIdx) else 0L
                    val total = if (totalIdx >= 0) cursor.getLong(totalIdx) else -1L
                    val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else DownloadManager.STATUS_RUNNING

                    list.add(
                        DownloadItemUi.Active(
                            id = id,
                            title = title.ifBlank { uri },
                            url = uri,
                            location = localUri,
                            bytesDownloaded = bytes,
                            totalBytes = total,
                            status = status
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            KeydroidxLog.e(TAG, "查询系统下载任务失败", t)
        }
        return list
    }

    private fun formatStatusText(item: DownloadItemUi.Active): String {
        return when (item.status) {
            DownloadManager.STATUS_PENDING -> getString(R.string.keyphone_downloads_status_pending)
            DownloadManager.STATUS_PAUSED -> getString(R.string.keyphone_downloads_status_paused)
            else -> {
                if (item.totalBytes > 0) {
                    val downloadedStr = Formatter.formatFileSize(this, item.bytesDownloaded)
                    val totalStr = Formatter.formatFileSize(this, item.totalBytes)
                    getString(
                        R.string.keyphone_downloads_status_downloading,
                        item.progress,
                        downloadedStr,
                        totalStr
                    )
                } else {
                    val downloadedStr = Formatter.formatFileSize(this, item.bytesDownloaded)
                    getString(R.string.keyphone_downloads_status_downloading_unknown, downloadedStr)
                }
            }
        }
    }

    /**
     * 当存在活跃下载时启动轻量协程轮询进度（每 800ms 查询一次，直接就地更新 UI）。
     */
    private fun startPollingIfNecessary() {
        val hasActive = uiItems.any { it is DownloadItemUi.Active }
        if (!hasActive) {
            stopPolling()
            return
        }

        if (pollingJob?.isActive == true) return

        pollingJob = lifecycleScope.launch {
            while (isActive) {
                delay(800)
                val actives = queryActiveDownloads()
                if (actives.isEmpty() || actives.size != activeRowMap.size) {
                    // 下载完成或有新任务，触发全表重构
                    rebuild()
                    break
                }

                // 就地更新各活跃行的进度条与文案，保证无闪烁、不丢焦点
                actives.forEach { active ->
                    val pRow = activeRowMap[active.id]
                    if (pRow != null) {
                        pRow.update(
                            formatStatusText(active),
                            active.progress,
                            active.isIndeterminate
                        )
                    }
                }
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * 打开已完成下载的文件。
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
     * 取消进行中的下载任务并清理记录。
     */
    private fun cancelDownload(item: DownloadItemUi.Active) {
        lifecycleScope.launch {
            try {
                downloadManager.remove(item.id)
                if (item.location.isNotBlank()) {
                    downloadsRepository.deleteDownload(item.location)
                    val filePath = item.location.removePrefix(FILE)
                    val file = File(filePath)
                    if (file.exists()) file.delete()
                }
                Toast.makeText(
                    this@KeyphoneDownloadsActivity,
                    R.string.keyphone_downloads_cancelled,
                    Toast.LENGTH_SHORT
                ).show()
                rebuild()
            } catch (t: Throwable) {
                KeydroidxLog.e(TAG, "取消下载任务异常: id=${item.id}", t)
            }
        }
    }

    /**
     * 左软键（LSK）呼出官方标准「选项」菜单（KeydroidxOptionsDialog）。
     */
    private fun showOptionsMenu() {
        if (uiItems.isEmpty()) return

        val focusedItem = uiItems.getOrNull(lastFocusIndex) ?: uiItems[0]
        val currentTheme = KeydroidxUi.getTheme(this)
        val iconSize = KeydroidxDimens.dp(resources, 18f)
        val iconColor = currentTheme.textColor

        when (focusedItem) {
            is DownloadItemUi.Active -> {
                KeydroidxOptionsDialog(this, getString(R.string.keyphone_downloads_options_title))
                    .addItem(
                        ID_OPT_CANCEL,
                        getString(R.string.keyphone_downloads_opt_cancel),
                        KeydroidxIcons.createDrawable(this, KeydroidxIcons.ICON_CLOSE, iconSize, iconColor)
                    )
                    .addItem(
                        ID_OPT_CLEAR,
                        getString(R.string.keyphone_downloads_opt_clear),
                        KeydroidxIcons.createDrawable(this, KeydroidxIcons.ICON_CLEAR_ALL, iconSize, iconColor)
                    )
                    .setOnOptionSelectedListener { _, item ->
                        when (item.id) {
                            ID_OPT_CANCEL -> cancelDownload(focusedItem)
                            ID_OPT_CLEAR -> confirmClearDownloads()
                        }
                    }
                    .show()
            }

            is DownloadItemUi.Completed -> {
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
                            ID_OPT_OPEN -> openDownload(focusedItem.entry)
                            ID_OPT_DELETE -> showDeleteChoiceDialog(focusedItem.entry)
                            ID_OPT_CLEAR -> confirmClearDownloads()
                        }
                    }
                    .show()
            }
        }
    }

    /**
     * 删除选择弹窗（KeydroidxOptionsDialog）：
     * 明确让用户选择「仅删除记录」或「同时删除文件」；物理文件不存在时静默忽略。
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
     * 清空全部下载记录（KeydroidxConfirmDialog）。
     */
    private fun confirmClearDownloads() {
        if (uiItems.isEmpty()) return
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
                onFocusItemChanged(helper.focusIndex)
                handled
            }

            KeydroidxKeyAction.SELECT -> {
                helper.focusedView?.performClick()
                true
            }

            KeydroidxKeyAction.LEFT -> {
                val focusedItem = uiItems.getOrNull(lastFocusIndex) ?: return true
                when (focusedItem) {
                    is DownloadItemUi.Active -> cancelDownload(focusedItem)
                    is DownloadItemUi.Completed -> showDeleteChoiceDialog(focusedItem.entry)
                }
                true
            }

            KeydroidxKeyAction.SOFT_LEFT -> {
                showOptionsMenu()
                true
            }

            else -> super.onAction(action)
        }
    }

    sealed interface DownloadItemUi {
        data class Active(
            val id: Long,
            val title: String,
            val url: String,
            val location: String,
            val bytesDownloaded: Long,
            val totalBytes: Long,
            val status: Int
        ) : DownloadItemUi {
            val progress: Int
                get() = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

            val isIndeterminate: Boolean
                get() = totalBytes <= 0
        }

        data class Completed(
            val entry: DownloadEntry
        ) : DownloadItemUi
    }

    companion object {
        private const val TAG = "KeyphoneDownloads"

        private const val ID_OPT_OPEN = 1
        private const val ID_OPT_DELETE = 2
        private const val ID_OPT_CLEAR = 3
        private const val ID_OPT_CANCEL = 4

        private const val ID_DEL_ONLY_RECORD = 10
        private const val ID_DEL_WITH_FILE = 20
    }
}
