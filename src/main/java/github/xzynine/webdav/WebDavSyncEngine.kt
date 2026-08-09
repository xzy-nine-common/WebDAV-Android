package github.xzynine.webdav

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 同步结果状态
 */
enum class SyncResult {

    /**
     * 同步成功
     */
    SUCCESS,

    /**
     * 双方均有变更，自动合并后同步成功
     */
    MERGED,

    /**
     * 自动合并失败，产生冲突
     */
    CONFLICT,

    /**
     * 同步失败
     */
    FAILED
}

/**
 * 同步失败类型，供调用方映射用户提示文案
 */
enum class SyncFailureKind {

    /**
     * 网络异常
     */
    NETWORK,

    /**
     * 本地访问权限失效
     */
    PERMISSION,

    /**
     * 本地或远端文件不存在
     */
    FILE_NOT_FOUND,

    /**
     * 其他未知错误
     */
    UNKNOWN
}

/**
 * 同步执行结果
 *
 * @param result 同步结果状态
 * @param errorKind 失败类型（仅失败时有效）
 * @param errorMessage 原始错误信息
 * @param remoteModifiedAt 远端文件修改时间
 */
data class SyncOutcome(
    val result: SyncResult,
    val errorKind: SyncFailureKind = SyncFailureKind.UNKNOWN,
    val errorMessage: String? = null,
    val remoteModifiedAt: Long? = null
) {
    /**
     * 是否成功（含合并成功）
     */
    val isSuccess: Boolean
        get() = result == SyncResult.SUCCESS || result == SyncResult.MERGED
}

/**
 * 合并回调：将远端字节与本地数据库合并
 *
 * @param localPath 本地数据库定位（文件路径或 content:// Uri）
 * @param masterPassword 主密码
 * @param remoteBytes 远端数据库字节
 * @return 是否合并成功
 */
fun interface SyncMergeCallback {

    /**
     * 执行合并
     */
    suspend fun merge(localPath: String, masterPassword: String, remoteBytes: ByteArray): Boolean
}

/**
 * 通用 WebDAV 同步引擎
 *
 * 负责云端库的上传、下载与冲突合并，支持本地文件路径与 content:// Uri 两种定位。
 * 数据库合并逻辑通过 [SyncMergeCallback] 注入，由调用方提供，引擎不依赖具体数据库实现。
 *
 * @param context 用于访问 content:// Uri 的上下文
 */
class WebDavSyncEngine(private val context: Context) {

    /**
     * 从远端下载并写入本地定位
     *
     * @param remotePath 远端文件完整地址
     * @param username WebDAV 用户名
     * @param password WebDAV 密码
     * @param localPath 本地定位（文件路径或 content:// Uri）
     * @return 同步结果
     */
    suspend fun download(
        remotePath: String,
        username: String,
        password: String,
        localPath: String
    ): SyncOutcome {
        return withContext(Dispatchers.IO) {
            runCatching {
                val remote = WebDav(remotePath, Authorization(username, password))
                if (!remote.exists()) {
                    return@runCatching SyncOutcome(
                        result = SyncResult.FAILED,
                        errorKind = SyncFailureKind.FILE_NOT_FOUND,
                        errorMessage = "远端文件不存在，无法下载"
                    )
                }

                val remoteInfo = remote.getWebDavFile()
                val remoteModified = remoteInfo?.lastModify?.takeIf { it > 0 }
                val bytes = remote.download()
                writeBytesToLocalPath(localPath, bytes)

                SyncOutcome(
                    result = SyncResult.SUCCESS,
                    remoteModifiedAt = remoteModified
                )
            }.getOrElse { throwable ->
                val (kind, message) = classifyFailure(throwable, localPath)
                SyncOutcome(
                    result = SyncResult.FAILED,
                    errorKind = kind,
                    errorMessage = message ?: "下载失败"
                )
            }
        }
    }

    /**
     * 将本地定位上传到远端，必要时先拉取远端并触发合并
     *
     * @param remotePath 远端文件完整地址
     * @param username WebDAV 用户名
     * @param password WebDAV 密码
     * @param localPath 本地定位（文件路径或 content:// Uri）
     * @param masterPassword 主密码，用于合并
     * @param merge 合并回调
     * @param lastRemoteModifiedAt 上次记录的远端修改时间，用于变更检测
     * @param lastSyncAt 上次同步时间戳，用于本地变更检测
     * @return 同步结果
     */
    suspend fun upload(
        remotePath: String,
        username: String,
        password: String,
        localPath: String,
        masterPassword: String,
        merge: SyncMergeCallback,
        lastRemoteModifiedAt: Long?,
        lastSyncAt: Long?
    ): SyncOutcome {
        return withContext(Dispatchers.IO) {
            runCatching {
                if (!localPathExists(localPath)) {
                    return@runCatching SyncOutcome(
                        result = SyncResult.FAILED,
                        errorKind = SyncFailureKind.FILE_NOT_FOUND,
                        errorMessage = "本地数据库文件不存在或不可访问"
                    )
                }

                val localPathIsUri = asContentUri(localPath) != null

                val remote = WebDav(remotePath, Authorization(username, password))
                val remoteInfo = remote.getWebDavFile()
                val remoteModified = remoteInfo?.lastModify?.takeIf { it > 0 }
                val remoteChangedAfterSync = remoteModified != null &&
                    (lastRemoteModifiedAt == null || remoteModified > lastRemoteModifiedAt)
                val localChangedAfterSync = if (localPathIsUri) {
                    true
                } else {
                    val localModifiedAt = getLocalPathLastModified(localPath)
                    lastSyncAt?.let { syncAt ->
                        (localModifiedAt ?: Long.MAX_VALUE) > syncAt
                    } ?: true
                }

                if (remoteChangedAfterSync) {
                    // 远端有变更，拉取后按本地是否变更决定合并或覆盖
                    val remoteBytes = remote.download()
                    val merged = if (localChangedAfterSync) {
                        merge.merge(localPath, masterPassword, remoteBytes)
                    } else {
                        writeBytesToLocalPath(localPath, remoteBytes)
                        true
                    }

                    if (!merged) {
                        return@runCatching SyncOutcome(
                            result = SyncResult.CONFLICT,
                            errorMessage = "自动合并失败，请先手动恢复后再同步",
                            remoteModifiedAt = remoteModified
                        )
                    }
                }

                if (localPathIsUri) {
                    remote.upload(readBytesFromLocalPath(localPath), "application/octet-stream")
                } else {
                    remote.upload(File(localPath), "application/octet-stream")
                }
                val refreshedRemoteModified = runCatching {
                    remote.getWebDavFile()?.lastModify
                }.getOrNull()?.takeIf { it > 0 } ?: remoteModified

                val finalStatus = if (remoteChangedAfterSync && localChangedAfterSync) {
                    SyncResult.MERGED
                } else {
                    SyncResult.SUCCESS
                }
                SyncOutcome(
                    result = finalStatus,
                    remoteModifiedAt = refreshedRemoteModified
                )
            }.getOrElse { throwable ->
                val (kind, message) = classifyFailure(throwable, localPath)
                SyncOutcome(
                    result = SyncResult.FAILED,
                    errorKind = kind,
                    errorMessage = message ?: "上传失败"
                )
            }
        }
    }

    /**
     * 上传字节数组到远端（新建空库等场景）
     *
     * @param remotePath 远端文件完整地址
     * @param username WebDAV 用户名
     * @param password WebDAV 密码
     * @param bytes 待上传字节
     * @param contentType 内容类型
     * @return 同步结果
     */
    suspend fun uploadBytes(
        remotePath: String,
        username: String,
        password: String,
        bytes: ByteArray,
        contentType: String = "application/octet-stream"
    ): SyncOutcome {
        return withContext(Dispatchers.IO) {
            runCatching {
                val remote = WebDav(remotePath, Authorization(username, password))
                remote.upload(bytes, contentType)
                SyncOutcome(result = SyncResult.SUCCESS)
            }.getOrElse { throwable ->
                val (kind, message) = classifyFailure(throwable, null)
                SyncOutcome(
                    result = SyncResult.FAILED,
                    errorKind = kind,
                    errorMessage = message ?: "上传失败"
                )
            }
        }
    }

    /**
     * 判断路径是否为 Content Uri
     */
    private fun asContentUri(path: String): Uri? {
        val parsed = runCatching { Uri.parse(path) }.getOrNull() ?: return null
        return if (parsed.scheme.equals("content", ignoreCase = true)) parsed else null
    }

    /**
     * 申请并持久化 Uri 读写权限
     */
    private fun takePersistableUriPermission(uri: Uri) {
        if (!uri.scheme.equals("content", ignoreCase = true)) {
            return
        }

        val resolver = context.contentResolver
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    /**
     * 写入本地定位（文件路径或 Uri）
     */
    private fun writeBytesToLocalPath(localPath: String, bytes: ByteArray) {
        val uri = asContentUri(localPath)
        if (uri != null) {
            takePersistableUriPermission(uri)
            val output = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IllegalStateException("无法写入本地数据库")
            output.use { stream ->
                stream.write(bytes)
            }
            return
        }

        val localFile = File(localPath)
        localFile.parentFile?.let {
            if (!it.exists()) {
                it.mkdirs()
            }
        }
        localFile.writeBytes(bytes)
    }

    /**
     * 读取本地定位（文件路径或 Uri）
     */
    private fun readBytesFromLocalPath(localPath: String): ByteArray {
        val uri = asContentUri(localPath)
        if (uri != null) {
            takePersistableUriPermission(uri)
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取本地数据库")
            return input.use { stream ->
                stream.readBytes()
            }
        }
        return File(localPath).readBytes()
    }

    /**
     * 判断本地定位是否可访问
     */
    private fun localPathExists(localPath: String): Boolean {
        val uri = asContentUri(localPath)
        if (uri != null) {
            return runCatching {
                takePersistableUriPermission(uri)
                context.contentResolver.openInputStream(uri)?.use { true } ?: false
            }.getOrDefault(false)
        }
        return File(localPath).exists()
    }

    /**
     * 获取本地文件最近修改时间
     */
    private fun getLocalPathLastModified(localPath: String): Long? {
        val uri = asContentUri(localPath)
        if (uri != null) {
            val modified = runCatching {
                DocumentFile.fromSingleUri(context, uri)?.lastModified()
            }.getOrNull() ?: 0L
            return modified.takeIf { it > 0L }
        }

        val localFile = File(localPath)
        if (!localFile.exists()) {
            return null
        }
        return localFile.lastModified().takeIf { it > 0L }
    }

    /**
     * 拼接异常链文本，便于关键字匹配
     */
    private fun flattenThrowableMessage(throwable: Throwable): String {
        return generateSequence(throwable) { current ->
            current.cause
        }.joinToString(separator = " | ") { current ->
            "${current.javaClass.simpleName}:${current.message.orEmpty()}"
        }
    }

    /**
     * 判断是否为权限相关异常
     */
    private fun isPermissionIssue(throwable: Throwable): Boolean {
        if (throwable is SecurityException) {
            return true
        }
        val text = flattenThrowableMessage(throwable)
        return text.contains("permission", ignoreCase = true) ||
            text.contains("denied", ignoreCase = true) ||
            text.contains("ACTION_OPEN_DOCUMENT", ignoreCase = true) ||
            text.contains("persistable", ignoreCase = true) ||
            text.contains("EACCES", ignoreCase = true)
    }

    /**
     * 判断是否为网络异常
     */
    private fun isNetworkIssue(throwable: Throwable): Boolean {
        return throwable is UnknownHostException ||
            throwable is SocketTimeoutException ||
            throwable is ConnectException ||
            throwable is SocketException
    }

    /**
     * 将异常归类为结构化失败类型
     */
    private fun classifyFailure(throwable: Throwable, localPath: String?): Pair<SyncFailureKind, String?> {
        val isUriPath = !localPath.isNullOrBlank() && asContentUri(localPath) != null
        val kind = when {
            isUriPath && isPermissionIssue(throwable) -> SyncFailureKind.PERMISSION
            throwable is FileNotFoundException -> SyncFailureKind.FILE_NOT_FOUND
            isNetworkIssue(throwable) -> SyncFailureKind.NETWORK
            else -> SyncFailureKind.UNKNOWN
        }
        return kind to throwable.message
    }
}
