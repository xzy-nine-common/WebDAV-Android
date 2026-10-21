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
 * 文件缺失来源，用于区分远端/本地文件不存在，避免调用方依赖错误文案判断
 */
enum class FileAbsenceSource {

    /**
     * 非文件缺失类错误
     */
    NONE,

    /**
     * 远端文件缺失
     */
    REMOTE,

    /**
     * 本地文件缺失
     */
    LOCAL
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
     * Android 17（SDK 37）起访问局域网需要「本地网络」权限，未授予或被撤销时触发。
     *
     * 未授予时系统不会返回明确错误：TCP 连接表现为超时，UDP 返回 EPERM，
     * 因此引擎会在发起请求前主动检查，并在网络类失败时结合目标地址回判为该类型，
     * 便于宿主提示用户授权而不是笼统提示"网络异常"。
     */
    LOCAL_NETWORK,

    /**
     * 其他未知错误
     */
    UNKNOWN;

    companion object {

        /**
         * 将异常归类为失败类型（不含本地路径权限判定）。
         */
        fun of(throwable: Throwable): SyncFailureKind = when {
            throwable is FileNotFoundException -> FILE_NOT_FOUND
            throwable is UnknownHostException ||
                throwable is SocketTimeoutException ||
                throwable is ConnectException ||
                throwable is SocketException -> NETWORK
            else -> UNKNOWN
        }
    }
}

/**
 * 同步执行结果
 *
 * @param result 同步结果状态
 * @param errorKind 失败类型（仅失败时有效）
 * @param errorMessage 原始错误信息
 * @param absenceSource 文件缺失来源（仅 FILE_NOT_FOUND 时有效）
 * @param remoteModifiedAt 远端文件修改时间
 * @param localBytesReplaced 本次操作用远端字节直接覆盖了本地文件，
 * 宿主需据此失效自身的内存数据库缓存，避免缓存与磁盘脱节
 */
data class SyncOutcome(
    val result: SyncResult,
    val errorKind: SyncFailureKind = SyncFailureKind.UNKNOWN,
    val errorMessage: String? = null,
    val absenceSource: FileAbsenceSource = FileAbsenceSource.NONE,
    val remoteModifiedAt: Long? = null,
    val localBytesReplaced: Boolean = false
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
/**
 * 目标位于局域网但未获得「本地网络」权限时的统一提示文案。
 */
const val LOCAL_NETWORK_DENIED_MESSAGE: String =
    "访问局域网中的 WebDAV 服务器需要「本地网络」权限（Android 17 起），请允许后再重试"

class WebDavSyncEngine(private val context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * 访问给定远端地址是否需要「本地网络」权限。
     *
     * Android 17（SDK 37）起，访问局域网地址默认被屏蔽；
     * 宿主可在发起同步前调用本方法判断是否需要先引导用户授权。
     *
     * @param remotePath 远端文件完整地址
     * @return true 表示目标位于局域网且尚未授权
     */
    fun requiresLocalNetworkPermission(remotePath: String?): Boolean {
        return LocalNetworkPermission.needsRequest(appContext, remotePath)
    }

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
            // Android 17 起局域网访问被默认屏蔽，缺少权限时直接失败并给出可操作提示，
            // 避免用户只看到"连接超时"却不知道需要授权。
            localNetworkDeniedOutcome(remotePath)?.let { return@withContext it }
            runCatching {
                val remote = WebDav(remotePath, Authorization(username, password))
                // 网络异常直接抛出交由下方分类，404 才按远端缺失处理
                if (!remote.existsOrThrow()) {
                    return@runCatching SyncOutcome(
                        result = SyncResult.FAILED,
                        errorKind = SyncFailureKind.FILE_NOT_FOUND,
                        errorMessage = "远端文件不存在，无法下载",
                        absenceSource = FileAbsenceSource.REMOTE
                    )
                }

                val remoteInfo = remote.getWebDavFile()
                val remoteModified = remoteInfo?.lastModify?.takeIf { it > 0 }
                val bytes = remote.download()
                writeBytesToLocalPath(localPath, bytes)

                SyncOutcome(
                    result = SyncResult.SUCCESS,
                    remoteModifiedAt = remoteModified,
                    localBytesReplaced = true
                )
            }.getOrElse { throwable ->
                val (kind, message, absenceSource) =
                    classifyFailureInternal(throwable, localPath, remotePath)
                SyncOutcome(
                    result = SyncResult.FAILED,
                    errorKind = kind,
                    errorMessage = message ?: "下载失败",
                    absenceSource = absenceSource
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
            localNetworkDeniedOutcome(remotePath)?.let { return@withContext it }
            runCatching {
                if (!localPathExists(localPath)) {
                    return@runCatching SyncOutcome(
                        result = SyncResult.FAILED,
                        errorKind = SyncFailureKind.FILE_NOT_FOUND,
                        errorMessage = "本地数据库文件不存在或不可访问",
                        absenceSource = FileAbsenceSource.LOCAL
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
                    var localReplaced = false
                    val merged = if (localChangedAfterSync) {
                        merge.merge(localPath, masterPassword, remoteBytes)
                    } else {
                        writeBytesToLocalPath(localPath, remoteBytes)
                        localReplaced = true
                        true
                    }

                    if (!merged) {
                        return@runCatching SyncOutcome(
                            result = SyncResult.CONFLICT,
                            errorMessage = "自动合并失败，请先手动恢复后再同步",
                            remoteModifiedAt = remoteModified
                        )
                    }

                    if (localReplaced) {
                        // 远端字节已直接落盘，通知宿主失效内存缓存
                        return@runCatching SyncOutcome(
                            result = SyncResult.SUCCESS,
                            remoteModifiedAt = remoteModified,
                            localBytesReplaced = true
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
                val (kind, message, absenceSource) =
                    classifyFailureInternal(throwable, localPath, remotePath)
                SyncOutcome(
                    result = SyncResult.FAILED,
                    errorKind = kind,
                    errorMessage = message ?: "上传失败",
                    absenceSource = absenceSource
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
            localNetworkDeniedOutcome(remotePath)?.let { return@withContext it }
            runCatching {
                val remote = WebDav(remotePath, Authorization(username, password))
                remote.upload(bytes, contentType)
                SyncOutcome(result = SyncResult.SUCCESS)
            }.getOrElse { throwable ->
                val (kind, message, absenceSource) =
                    classifyFailureInternal(throwable, null, remotePath)
                SyncOutcome(
                    result = SyncResult.FAILED,
                    errorKind = kind,
                    errorMessage = message ?: "上传失败",
                    absenceSource = absenceSource
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
     *
     * 文件路径分支先写临时文件再原子替换，避免写入中断损坏原库；
     * Uri 分支受 SAF 限制无法原子替换，直接写入。
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
        val tempFile = File(localFile.parentFile, localFile.name + ".sync.tmp")
        try {
            tempFile.writeBytes(bytes)
            // 同目录 rename 原子替换；跨卷等异常时回退整文件复制
            if (!tempFile.renameTo(localFile)) {
                tempFile.copyTo(localFile, overwrite = true)
                tempFile.delete()
            }
        } finally {
            runCatching { tempFile.delete() }
        }
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
     * 判断本地定位自上次同步完成后是否有未同步的修改
     *
     * content:// 定位修改时间不可靠，恒视为已修改（由调用方走合并路径兜底）。
     *
     * @param localPath 本地定位（文件路径或 content:// Uri）
     * @param lastSyncAt 最近一次同步完成时间戳，null 表示从未同步
     */
    fun isLocalChangedSinceSync(localPath: String, lastSyncAt: Long?): Boolean {
        if (asContentUri(localPath) != null) {
            return true
        }
        if (lastSyncAt == null) {
            return true
        }
        val localModifiedAt = getLocalPathLastModified(localPath)
        return (localModifiedAt ?: Long.MAX_VALUE) > lastSyncAt
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
     * 将异常归类为同步失败结果，供调用方 catch 场景使用
     *
     * @param throwable 待归类的异常
     * @param localPath 本地定位（文件路径或 content:// Uri），用于权限类错误判定
     * @param remotePath 远端地址，用于识别 Android 17 起的本地网络权限缺失
     * @return 失败结果
     */
    fun classifyFailure(
        throwable: Throwable,
        localPath: String? = null,
        remotePath: String? = null
    ): SyncOutcome {
        val (kind, message, absenceSource) =
            classifyFailureInternal(throwable, localPath, remotePath)
        return SyncOutcome(
            result = SyncResult.FAILED,
            errorKind = kind,
            errorMessage = message,
            absenceSource = absenceSource
        )
    }

    /**
     * 按结构化失败类型构造失败结果，供调用方 catch 场景使用
     *
     * @param kind 失败类型
     * @param message 原始错误信息
     * @return 失败结果
     */
    fun buildFailureOutcome(kind: SyncFailureKind, message: String?): SyncOutcome {
        return SyncOutcome(
            result = SyncResult.FAILED,
            errorKind = kind,
            errorMessage = message
        )
    }

    /**
     * 将异常归类为结构化失败类型与缺失来源
     */
    private fun classifyFailureInternal(
        throwable: Throwable,
        localPath: String?,
        remotePath: String? = null
    ): Triple<SyncFailureKind, String?, FileAbsenceSource> {
        val isUriPath = !localPath.isNullOrBlank() && asContentUri(localPath) != null
        val kind = when {
            isLocalNetworkDenied(throwable, remotePath) -> SyncFailureKind.LOCAL_NETWORK
            isUriPath && isPermissionIssue(throwable) -> SyncFailureKind.PERMISSION
            else -> SyncFailureKind.of(throwable)
        }
        val absenceSource = if (localPath != null && throwable is FileNotFoundException) {
            FileAbsenceSource.LOCAL
        } else {
            FileAbsenceSource.NONE
        }
        val message = if (kind == SyncFailureKind.LOCAL_NETWORK) {
            LOCAL_NETWORK_DENIED_MESSAGE
        } else {
            throwable.message
        }
        return Triple(kind, message, absenceSource)
    }

    /**
     * 发起请求前的本地网络权限检查：目标位于局域网且尚未授权时返回失败结果
     */
    private fun localNetworkDeniedOutcome(remotePath: String?): SyncOutcome? {
        if (!LocalNetworkPermission.needsRequest(appContext, remotePath)) return null
        return SyncOutcome(
            result = SyncResult.FAILED,
            errorKind = SyncFailureKind.LOCAL_NETWORK,
            errorMessage = LOCAL_NETWORK_DENIED_MESSAGE
        )
    }

    /**
     * 判断失败是否由缺少「本地网络」权限导致。
     *
     * 未授权时系统不会给出明确错误：TCP 表现为超时、UDP 返回 EPERM，
     * 因此这里结合"目标是局域网地址 + 未授权 + 网络类异常/权限类异常"综合判定。
     */
    private fun isLocalNetworkDenied(throwable: Throwable, remotePath: String?): Boolean {
        if (!LocalNetworkPermission.isLocalNetworkUrl(remotePath)) return false
        if (LocalNetworkPermission.isGranted(appContext)) return false
        val text = flattenThrowableMessage(throwable)
        return throwable is SecurityException ||
            SyncFailureKind.of(throwable) == SyncFailureKind.NETWORK ||
            text.contains("EPERM", ignoreCase = true) ||
            text.contains("permission", ignoreCase = true)
    }
}
