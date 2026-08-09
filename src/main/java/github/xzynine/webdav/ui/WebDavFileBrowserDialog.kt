package github.xzynine.webdav.ui

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import github.xzynine.webdav.Authorization
import github.xzynine.webdav.WebDav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.window.WindowDialog
import java.net.URLEncoder

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

private const val LOG_TAG = "tag:WebDAV-UI"

/**
 * WebDAV 文件浏览弹窗
 *
 * 提供账号连接、目录/文件列表浏览、选择文件或目录的回调。
 * 选择结果以相对路径回调，由调用方自行拼接完整远程地址。
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
    val coroutineScope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(initialServerUrl) }
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf(initialPassword) }
    var currentDirectory by remember { mutableStateOf(initialDirectory.trim().trim('/')) }
    var status by remember { mutableStateOf("") }
    var directoryListing by remember { mutableStateOf<List<String>>(emptyList()) }
    var listing by remember { mutableStateOf<List<String>>(emptyList()) }
    var isConnected by remember { mutableStateOf(false) }
    var accountPasswordVisible by remember { mutableStateOf(false) }

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
     * 拼接目录完整地址
     */
    fun buildDirectoryUrl(baseUrl: String, relativeDirectory: String): String {
        val rel = encodeRelativePath(relativeDirectory)
        return if (rel.isBlank()) baseUrl else "$baseUrl$rel/"
    }

    /**
     * 列出指定目录下的子目录与文件（相对路径列表）
     */
    suspend fun listCurrentDirectory(
        baseUrl: String,
        user: String,
        pass: String,
        relativeDirectory: String
    ): Pair<List<String>, List<String>> {
        return withContext(Dispatchers.IO) {
            val auth = Authorization(user, pass)
            val dirUrl = buildDirectoryUrl(baseUrl, relativeDirectory)
            Log.d(
                LOG_TAG,
                "listCurrentDirectory start, baseUrl=$baseUrl, relativeDirectory=$relativeDirectory, dirUrl=$dirUrl"
            )
            val entries = WebDav(dirUrl, auth).listFiles()
            Log.d(LOG_TAG, "listCurrentDirectory entries=${entries.size}, dirUrl=$dirUrl")

            val dirPrefix = normalizeRelativePath(relativeDirectory)
            val dirs = entries
                .filter { it.isDir }
                .map {
                    val segment = it.urlName.trim('/').substringAfterLast('/').ifBlank {
                        it.displayName.trim('/').substringAfterLast('/')
                    }
                    normalizeRelativePath(
                        if (dirPrefix.isBlank()) segment else "$dirPrefix/$segment"
                    )
                }
                .distinct()
                .sorted()

            val files = entries
                .filter {
                    !it.isDir && (fileExtensionFilter == null ||
                        it.displayName.endsWith(fileExtensionFilter, ignoreCase = true))
                }
                .map {
                    val segment = it.urlName.trim('/').substringAfterLast('/').ifBlank {
                        it.displayName.trim('/').substringAfterLast('/')
                    }
                    normalizeRelativePath(
                        if (dirPrefix.isBlank()) segment else "$dirPrefix/$segment"
                    )
                }
                .distinct()
                .sorted()

            Log.d(
                LOG_TAG,
                "listCurrentDirectory done, dirs=${dirs.size}, files=${files.size}, relativeDirectory=$relativeDirectory"
            )

            dirs to files
        }
    }

    /**
     * 连接并浏览指定目录
     */
    suspend fun connectAndBrowse(relativeDirectory: String, successStatus: String) {
        try {
            val baseUrl = normalizeServerRootUrl(serverUrl)
            currentDirectory = normalizeRelativePath(relativeDirectory)
            val (dirs, files) = listCurrentDirectory(baseUrl, username, password, currentDirectory)
            directoryListing = dirs
            listing = files
            isConnected = true
            status = successStatus
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            Log.e(LOG_TAG, "UI connectAndBrowse failed, dir=$relativeDirectory, message=$msg", e)
            status = "加载失败：$msg"
        }
    }

    WindowDialog(
        title = title,
        summary = "支持目录与文件选择",
        show = true,
        onDismissRequest = onDismiss,
        defaultWindowInsetsPadding = true
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = "WebDAV地址",
                singleLine = true
            )
            TextField(
                value = username,
                onValueChange = { username = it },
                label = "用户名",
                singleLine = true
            )
            TextField(
                value = password,
                onValueChange = { password = it },
                label = "密码",
                visualTransformation = if (accountPasswordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true
            )
            Button(onClick = { accountPasswordVisible = !accountPasswordVisible }) {
                Text(if (accountPasswordVisible) "隐藏密码" else "显示密码")
            }

            Button(onClick = {
                coroutineScope.launch {
                    connectAndBrowse("", "已连接，当前为根目录")
                }
            }) {
                Text(if (isConnected) "重新连接" else "连接并浏览")
            }

            if (isConnected) {
                Text(text = "当前目录: /${normalizeRelativePath(currentDirectory)}")

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                connectAndBrowse("", "已切换到根目录")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("回到根目录")
                    }

                    if (normalizeRelativePath(currentDirectory).isNotBlank()) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    val parent = normalizeRelativePath(currentDirectory)
                                        .substringBeforeLast('/', "")
                                    connectAndBrowse(parent, "已返回上级目录")
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("返回上级目录")
                        }
                    }
                }

                Text("子目录")
                directoryListing.forEach { dirPath ->
                    ArrowPreference(
                        title = "📁 ${dirPath.substringAfterLast('/')}",
                        summary = dirPath,
                        onClick = {
                            coroutineScope.launch {
                                connectAndBrowse(dirPath, "已进入目录：/$dirPath")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (mode == WebDavBrowseMode.PICK_FILE) {
                    Text("选择远端文件")
                    listing.forEach { filePath ->
                        ArrowPreference(
                            title = filePath.substringAfterLast('/'),
                            summary = filePath,
                            onClick = {
                                onSelected(normalizeServerRootUrl(serverUrl), filePath)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            onSelected(
                                normalizeServerRootUrl(serverUrl),
                                normalizeRelativePath(currentDirectory)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("选择当前目录")
                    }
                }
            }

            if (status.isNotBlank()) {
                Text(status)
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
