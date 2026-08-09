package github.xzynine.webdav.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import github.xzynine.webdav.Authorization
import github.xzynine.webdav.WebDav
import github.xzynine.webdav.WebDavFileEntry
import github.xzynine.webdav.WebDavFileFormat
import github.xzynine.webdav.WebDavFileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import java.net.URLEncoder
import java.text.Collator

/**
 * 文件浏览选择模式
 */
enum class WebDavBrowseMode {

    /**
     * 选择文件
     */
    PICK_FILE,

    /**
     * 选择当前目录
     */
    PICK_DIRECTORY
}

/**
 * 列表排序字段
 */
private enum class BrowserSortField {

    /**
     * 按名称
     */
    NAME,

    /**
     * 按大小
     */
    SIZE,

    /**
     * 按修改时间
     */
    TIME,

    /**
     * 按类型
     */
    EXTENSION
}

private const val LOG_TAG = "tag:WebDAV-UI"

/**
 * WebDAV 文件浏览弹窗
 *
 * 参考 scrcpy 文件管理器交互：
 * - 面包屑层级条，支持点击任意层级跳转
 * - 卡片网格展示文件（图标 + 名称 + 时间/大小摘要）
 * - 排序菜单（名称/大小/时间/类型，仅内存态，不做持久化）
 * - 更多菜单：跳转路径、新建文件夹、上传文件
 * - 长按/点击文件查看详情，支持下载与删除
 *
 * 选择结果以相对路径回调，由调用方自行拼接完整远程地址。
 *
 * 打开时使用传入的账号信息自动连接并加载 [initialDirectory]，连接失败时在浏览视图内展示错误提示。
 *
 * @param initialServerUrl 初始服务器地址
 * @param initialUsername 初始用户名
 * @param initialPassword 初始密码
 * @param initialDirectory 初始相对目录
 * @param mode 浏览模式：选择文件或选择当前目录
 * @param title 弹窗标题
 * @param fileExtensionFilter 文件扩展名过滤（如 ".kdbx"），为空时显示全部文件
 * @param onDismiss 关闭回调
 * @param onSelected 选择完成回调，返回 (服务器根地址, 相对路径)
 */
@Composable
fun WebDavFileBrowserDialog(
    initialServerUrl: String,
    initialUsername: String,
    initialPassword: String,
    initialDirectory: String = "",
    mode: WebDavBrowseMode = WebDavBrowseMode.PICK_FILE,
    title: String = "浏览 WebDAV 文件",
    fileExtensionFilter: String? = null,
    onDismiss: () -> Unit,
    onSelected: (baseUrl: String, relativePath: String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 账号信息
    var serverUrl by remember { mutableStateOf(initialServerUrl) }
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf(initialPassword) }

    // 浏览状态
    var currentDirectory by remember { mutableStateOf(initialDirectory.trim().trim('/')) }
    var entries by remember { mutableStateOf<List<WebDavFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var sortField by remember { mutableStateOf(BrowserSortField.NAME) }
    var sortDescending by remember { mutableStateOf(false) }

    // 视图切换状态
    var detailEntry by remember { mutableStateOf<WebDavFileEntry?>(null) }
    var showPathDialog by remember { mutableStateOf(false) }
    var pathInput by remember { mutableStateOf("") }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    /**
     * 保证服务器根地址以 "/" 结尾
     */
    fun normalizeServerRootUrl(raw: String): String {
        return if (raw.endsWith('/')) raw else "$raw/"
    }

    /**
     * 规范化相对路径：去除首尾斜杠并合并重复斜杠
     */
    fun normalizeRelativePath(path: String): String {
        return path.trim().trim('/').replace("//", "/")
    }

    /**
     * 对相对路径逐段 URL 编码
     */
    fun encodeRelativePath(path: String): String {
        return normalizeRelativePath(path)
            .split('/')
            .filter { it.isNotBlank() }
            .joinToString("/") {
                URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20")
            }
    }

    /**
     * 拼接文件完整地址（不带尾部斜杠）
     */
    fun buildFileUrl(baseUrl: String, relativePath: String): String {
        val rel = encodeRelativePath(relativePath)
        return if (rel.isBlank()) baseUrl else "$baseUrl$rel"
    }

    /**
     * 拼接目录完整地址（带尾部斜杠）
     */
    fun buildDirectoryUrl(baseUrl: String, relativeDirectory: String): String {
        val rel = encodeRelativePath(relativeDirectory)
        return if (rel.isBlank()) baseUrl else "$baseUrl$rel/"
    }

    /**
     * 判断文件是否满足扩展名过滤
     */
    fun matchesFilter(entry: WebDavFileEntry): Boolean {
        return fileExtensionFilter.isNullOrBlank() ||
            entry.name.endsWith(fileExtensionFilter, ignoreCase = true)
    }

    /**
     * 对条目排序：目录始终在前，字段排序方向可切换（仅内存态，不持久化）
     */
    fun sortEntries(raw: List<WebDavFileEntry>): List<WebDavFileEntry> {
        val collator = Collator.getInstance()
        val fieldCmp = Comparator<WebDavFileEntry> { a, b ->
            when (sortField) {
                BrowserSortField.NAME -> collator.compare(a.name, b.name)
                BrowserSortField.SIZE -> a.sizeBytes.compareTo(b.sizeBytes)
                BrowserSortField.TIME -> a.modifiedAt.compareTo(b.modifiedAt)
                BrowserSortField.EXTENSION ->
                    collator.compare(a.name.substringAfterLast('.'), b.name.substringAfterLast('.'))
            }
        }
        val dirCmp = compareByDescending<WebDavFileEntry> { it.isDirectory }
        return raw.sortedWith(
            if (sortDescending) dirCmp.then(fieldCmp.reversed()) else dirCmp.then(fieldCmp)
        )
    }

    /**
     * 展示列表（排序后的条目）
     */
    val displayedEntries = remember(entries, sortField, sortDescending) {
        sortEntries(entries)
    }

    /**
     * 加载指定相对目录的列表
     */
    suspend fun loadDirectory(relativeDirectory: String) {
        loading = true
        errorText = null
        message = null
        try {
            withContext(Dispatchers.IO) {
                val baseUrl = normalizeServerRootUrl(serverUrl)
                val dirUrl = buildDirectoryUrl(baseUrl, relativeDirectory)
                Log.d(LOG_TAG, "loadDirectory start, dirUrl=$dirUrl")
                val raw = WebDav(dirUrl, Authorization(username, password)).listFiles()
                    .map { WebDavFileEntry.fromWebDavFile(it, normalizeRelativePath(relativeDirectory)) }
                    .filter { it.isDirectory || matchesFilter(it) }
                    .distinctBy { it.relativePath }
                Log.d(LOG_TAG, "loadDirectory done, size=${raw.size}, dirUrl=$dirUrl")
                raw
            }.let { raw ->
                currentDirectory = normalizeRelativePath(relativeDirectory)
                entries = raw
            }
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            Log.e(LOG_TAG, "loadDirectory failed, dir=$relativeDirectory, message=$msg", e)
            errorText = msg.ifBlank { "未知错误" }
        } finally {
            loading = false
        }
    }

    /**
     * 上传所选 Uri 文件到当前目录
     */
    suspend fun uploadFrom(uri: Uri) {
        working = true
        message = null
        try {
            val fileName = withContext(Dispatchers.IO) {
                androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name
                    ?: uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
                    ?: "未命名文件"
            }
            val dirPrefix = normalizeRelativePath(currentDirectory)
            val url = buildFileUrl(
                serverUrl,
                if (dirPrefix.isBlank()) fileName else "$dirPrefix/$fileName"
            )
            withContext(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("无法读取所选文件")
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                WebDav(url, Authorization(username, password)).upload(bytes, mime)
            }
            Log.d(LOG_TAG, "upload done, url=$url")
            message = "上传成功：$fileName"
            loadDirectory(currentDirectory)
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            Log.e(LOG_TAG, "upload failed, message=$msg", e)
            message = "上传失败：${msg.ifBlank { "未知错误" }}"
        } finally {
            working = false
        }
    }

    /**
     * 下载条目文件到系统下载目录
     */
    suspend fun downloadEntry(entry: WebDavFileEntry) {
        working = true
        message = null
        try {
            val url = buildFileUrl(serverUrl, entry.relativePath)
            withContext(Dispatchers.IO) {
                val bytes = WebDav(url, Authorization(username, password)).download()
                val target = saveToDownloads(context, entry.name, bytes)
                if (target == null) {
                    error("保存到下载目录失败")
                }
            }
            Log.d(LOG_TAG, "download done, url=$url")
            message = "已保存到系统下载目录"
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            Log.e(LOG_TAG, "download failed, message=$msg", e)
            message = "下载失败：${msg.ifBlank { "未知错误" }}"
        } finally {
            working = false
        }
    }

    /**
     * 删除条目（文件或目录）
     */
    suspend fun deleteEntry(entry: WebDavFileEntry) {
        working = true
        message = null
        try {
            val url = buildFileUrl(serverUrl, entry.relativePath)
            withContext(Dispatchers.IO) {
                WebDav(url, Authorization(username, password)).delete()
            }
            Log.d(LOG_TAG, "delete done, url=$url")
            message = "已删除：${entry.name}"
            detailEntry = null
            loadDirectory(currentDirectory)
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            Log.e(LOG_TAG, "delete failed, message=$msg", e)
            message = "删除失败：${msg.ifBlank { "未知错误" }}"
        } finally {
            working = false
        }
    }

    // 上传文件选择器
    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch { uploadFrom(uri) }
        }
    }

    // 打开即自动连接并加载初始目录，失败时由浏览视图展示错误提示
    LaunchedEffect(Unit) {
        loadDirectory(initialDirectory)
    }

    WindowBottomSheet(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
        defaultWindowInsetsPadding = true
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(500.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                detailEntry != null -> DetailView(
                    entry = detailEntry!!,
                    working = working,
                    message = message,
                    onBack = {
                        detailEntry = null
                        message = null
                    },
                    onDownload = {
                        coroutineScope.launch { downloadEntry(detailEntry!!) }
                    },
                    onDelete = { showDeleteConfirm = true }
                )

                showPathDialog -> PathJumpView(
                    pathInput = pathInput,
                    onPathInputChange = { pathInput = it },
                    onCancel = { showPathDialog = false },
                    onConfirm = {
                        showPathDialog = false
                        coroutineScope.launch { loadDirectory(pathInput) }
                    }
                )

                showCreateFolderDialog -> CreateFolderView(
                    newFolderName = newFolderName,
                    onNewFolderNameChange = { newFolderName = it },
                    working = working,
                    onCancel = { showCreateFolderDialog = false },
                    onConfirm = {
                        showCreateFolderDialog = false
                        coroutineScope.launch {
                            working = true
                            message = null
                            try {
                                val dirPrefix = normalizeRelativePath(currentDirectory)
                                val url = buildDirectoryUrl(
                                    serverUrl,
                                    if (dirPrefix.isBlank()) {
                                        newFolderName.trim()
                                    } else {
                                        "$dirPrefix/${newFolderName.trim()}"
                                    }
                                )
                                withContext(Dispatchers.IO) {
                                    WebDav(url, Authorization(username, password)).makeAsDir()
                                }
                                Log.d(LOG_TAG, "create folder done, url=$url")
                                message = "创建成功：${newFolderName.trim()}"
                                loadDirectory(currentDirectory)
                            } catch (e: Exception) {
                                val msg = e.message.orEmpty()
                                Log.e(LOG_TAG, "create folder failed, message=$msg", e)
                                message = "创建失败：${msg.ifBlank { "未知错误" }}"
                            } finally {
                                working = false
                            }
                        }
                    }
                )

                showDeleteConfirm -> DeleteConfirmView(
                    entry = detailEntry!!,
                    working = working,
                    onCancel = { showDeleteConfirm = false },
                    onConfirm = {
                        showDeleteConfirm = false
                        coroutineScope.launch { deleteEntry(detailEntry!!) }
                    }
                )

                else -> BrowseView(
                    mode = mode,
                    currentDirectory = currentDirectory,
                    displayedEntries = displayedEntries,
                    loading = loading,
                    errorText = errorText,
                    message = message,
                    working = working,
                    sortField = sortField,
                    sortDescending = sortDescending,
                    onSortFieldChange = { sortField = it },
                    onSortDescendingChange = { sortDescending = it },
                    onJumpTo = { target ->
                        coroutineScope.launch { loadDirectory(target) }
                    },
                    onOpenEntry = { entry ->
                        if (entry.isDirectory) {
                            coroutineScope.launch { loadDirectory(entry.relativePath) }
                        } else if (mode == WebDavBrowseMode.PICK_FILE && matchesFilter(entry)) {
                            onSelected(normalizeServerRootUrl(serverUrl), entry.relativePath)
                        } else {
                            detailEntry = entry
                        }
                    },
                    onShowEntryDetails = { entry ->
                        detailEntry = entry
                        message = null
                    },
                    onPathDialog = {
                        pathInput = currentDirectory
                        showPathDialog = true
                    },
                    onCreateFolderDialog = {
                        newFolderName = ""
                        showCreateFolderDialog = true
                    },
                    onUpload = {
                        uploadLauncher.launch(arrayOf("*/*"))
                    },
                    onSelectCurrentDirectory = {
                        onSelected(
                            normalizeServerRootUrl(serverUrl),
                            normalizeRelativePath(currentDirectory)
                        )
                    }
                )
            }
        }
    }
}

/**
 * 文件详情视图（弹窗内切换，支持下载与删除）
 */
@Composable
private fun DetailView(
    entry: WebDavFileEntry,
    working: Boolean,
    message: String?,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(imageVector = Icons.Rounded.ArrowBack, contentDescription = "返回")
        }
        Text("文件详情")
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        InfoRow("名称", entry.name)
        InfoRow("位置", entry.relativePath)
        InfoRow("类型", kindLabel(entry))
        if (!entry.isDirectory) {
            InfoRow("大小", WebDavFileFormat.formatSize(entry.sizeBytes))
        }
        InfoRow("修改时间", WebDavFileFormat.formatTime(entry.modifiedAt))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onDownload,
            enabled = !entry.isDirectory && !working,
            modifier = Modifier.weight(1f)
        ) {
            Text("下载")
        }
        Button(
            onClick = onDelete,
            enabled = !working,
            modifier = Modifier.weight(1f)
        ) {
            Text("删除")
        }
    }

    message?.let { Text(it) }
}

/**
 * 详情信息行
 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 跳转路径视图
 */
@Composable
private fun PathJumpView(
    pathInput: String,
    onPathInputChange: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Text("跳转路径（相对根目录，如 WebDavPass/备份）")
    TextField(
        value = pathInput,
        onValueChange = onPathInputChange,
        label = "路径",
        useLabelAsPlaceholder = true,
        singleLine = true
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(
            text = "取消",
            onClick = onCancel,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            text = "跳转",
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary()
        )
    }
}

/**
 * 新建文件夹视图
 */
@Composable
private fun CreateFolderView(
    newFolderName: String,
    onNewFolderNameChange: (String) -> Unit,
    working: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Text("新建文件夹（在当前目录下）")
    TextField(
        value = newFolderName,
        onValueChange = onNewFolderNameChange,
        label = "文件夹名称",
        useLabelAsPlaceholder = true,
        singleLine = true
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(
            text = "取消",
            onClick = onCancel,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            text = "创建",
            onClick = onConfirm,
            enabled = newFolderName.isNotBlank() && !working,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary()
        )
    }
}

/**
 * 删除确认视图
 */
@Composable
private fun DeleteConfirmView(
    entry: WebDavFileEntry,
    working: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Text("确定删除「${entry.name}」？此操作不可恢复。")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(
            text = "取消",
            onClick = onCancel,
            enabled = !working,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            text = "删除",
            onClick = onConfirm,
            enabled = !working,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 浏览主视图（面包屑 + 工具行 + 卡片网格）
 */
@Composable
private fun BrowseView(
    mode: WebDavBrowseMode,
    currentDirectory: String,
    displayedEntries: List<WebDavFileEntry>,
    loading: Boolean,
    errorText: String?,
    message: String?,
    working: Boolean,
    sortField: BrowserSortField,
    sortDescending: Boolean,
    onSortFieldChange: (BrowserSortField) -> Unit,
    onSortDescendingChange: (Boolean) -> Unit,
    onJumpTo: (String) -> Unit,
    onOpenEntry: (WebDavFileEntry) -> Unit,
    onShowEntryDetails: (WebDavFileEntry) -> Unit,
    onPathDialog: () -> Unit,
    onCreateFolderDialog: () -> Unit,
    onUpload: () -> Unit,
    onSelectCurrentDirectory: () -> Unit
) {
    // 面包屑层级
    val segments = remember(currentDirectory) {
        currentDirectory.split('/').filter { it.isNotBlank() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        BreadcrumbText(
            text = "/",
            active = segments.isEmpty(),
            onClick = { onJumpTo("") }
        )
        segments.forEachIndexed { index, segment ->
            Text(
                text = "›",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            val target = segments.take(index + 1).joinToString("/")
            BreadcrumbText(
                text = segment,
                active = index == segments.lastIndex,
                onClick = { onJumpTo(target) }
            )
        }
    }

    // 工具行：排序 + 更多
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val sortOptions = listOf("按名称", "按大小", "按时间", "按类型")
        val sortFieldIdx = when (sortField) {
            BrowserSortField.NAME -> 0
            BrowserSortField.SIZE -> 1
            BrowserSortField.TIME -> 2
            BrowserSortField.EXTENSION -> 3
        }
        val dirOptions = listOf("升序", "降序")
        val dirIdx = if (sortDescending) 1 else 0
        WindowIconDropdownMenu(
            entries = listOf(
                DropdownEntry(
                    items = sortOptions.mapIndexed { index, option ->
                        DropdownItem(
                            text = option,
                            selected = index == sortFieldIdx,
                            onClick = {
                                onSortFieldChange(
                                    when (index) {
                                        1 -> BrowserSortField.SIZE
                                        2 -> BrowserSortField.TIME
                                        3 -> BrowserSortField.EXTENSION
                                        else -> BrowserSortField.NAME
                                    }
                                )
                            }
                        )
                    }
                ),
                DropdownEntry(
                    items = dirOptions.mapIndexed { index, option ->
                        DropdownItem(
                            text = option,
                            selected = index == dirIdx,
                            onClick = { onSortDescendingChange(index == 1) }
                        )
                    }
                )
            )
        ) {
            Icon(imageVector = Icons.Rounded.Tune, contentDescription = "排序")
        }

        WindowIconDropdownMenu(
            entry = DropdownEntry(
                items = listOf(
                    DropdownItem(
                        text = "跳转路径",
                        onClick = onPathDialog
                    ),
                    DropdownItem(
                        text = "新建文件夹",
                        onClick = onCreateFolderDialog
                    ),
                    DropdownItem(
                        text = "上传文件",
                        onClick = onUpload
                    )
                )
            )
        ) {
            Icon(imageVector = Icons.Rounded.MoreVert, contentDescription = "更多")
        }
    }

    // 列表区：加载/错误/空状态卡
    when {
        loading -> StatusCard("加载中...")

        errorText != null -> StatusCard("加载失败：$errorText")

        displayedEntries.isEmpty() -> StatusCard("目录为空")
    }

    // 卡片网格
    if (!loading && errorText == null && displayedEntries.isNotEmpty()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 200.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(displayedEntries, key = { it.relativePath }) { entry ->
                FileEntryCard(
                    entry = entry,
                    onClick = { onOpenEntry(entry) },
                    onLongClick = { onShowEntryDetails(entry) }
                )
            }
        }
    }

    // 选择当前目录（PICK_DIRECTORY 模式）
    if (mode == WebDavBrowseMode.PICK_DIRECTORY) {
        Button(
            onClick = onSelectCurrentDirectory,
            enabled = !working,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("选择当前目录")
        }
    }

    message?.let { Text(it) }
}

/**
 * 面包屑文本（当前层级高亮）
 */
@Composable
private fun BreadcrumbText(
    text: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = text,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        color = if (active) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * 状态提示卡（加载中/失败/空目录）
 */
@Composable
private fun StatusCard(message: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = message,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

/**
 * 文件条目卡片（图标 + 名称 + 摘要），点击打开，长按查看详情
 */
@Composable
private fun FileEntryCard(
    entry: WebDavFileEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = iconForEntry(entry),
                contentDescription = entry.name,
                tint = MiuixTheme.colorScheme.primary
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp)
            ) {
                Text(
                    text = entry.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = WebDavFileFormat.formatSummary(entry),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 根据文件类型选择图标
 */
private fun iconForEntry(entry: WebDavFileEntry): ImageVector = when (entry.kind) {
    WebDavFileKind.DIRECTORY -> Icons.Rounded.Folder
    WebDavFileKind.IMAGE -> Icons.Rounded.Image
    WebDavFileKind.VIDEO -> Icons.Rounded.VideoFile
    WebDavFileKind.AUDIO -> Icons.Rounded.AudioFile
    WebDavFileKind.ARCHIVE -> Icons.Rounded.Archive
    WebDavFileKind.TEXT -> Icons.Rounded.Description
    WebDavFileKind.OTHER -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

/**
 * 文件类型中文标签
 */
private fun kindLabel(entry: WebDavFileEntry): String = when (entry.kind) {
    WebDavFileKind.DIRECTORY -> "目录"
    WebDavFileKind.IMAGE -> "图片"
    WebDavFileKind.VIDEO -> "视频"
    WebDavFileKind.AUDIO -> "音频"
    WebDavFileKind.ARCHIVE -> "压缩包"
    WebDavFileKind.TEXT -> "文本"
    WebDavFileKind.OTHER -> "文件"
}

/**
 * 保存字节到系统下载目录（Android 10+ MediaStore，无需存储权限）
 *
 * @return 保存后的 Uri，失败返回 null
 */
private fun saveToDownloads(context: Context, fileName: String, bytes: ByteArray): Uri? {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }
    val uri = context.contentResolver.insert(
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        values
    ) ?: return null
    val ok = context.contentResolver.openOutputStream(uri)?.use { output ->
        output.write(bytes)
        true
    } ?: false
    if (!ok) {
        context.contentResolver.delete(uri, null, null)
        return null
    }
    return uri
}
