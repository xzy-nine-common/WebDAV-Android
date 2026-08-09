package github.xzynine.webdav

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 远端文件类型分类，用于图标选择与展示
 */
enum class WebDavFileKind {

    /**
     * 目录
     */
    DIRECTORY,

    /**
     * 图片
     */
    IMAGE,

    /**
     * 视频
     */
    VIDEO,

    /**
     * 音频
     */
    AUDIO,

    /**
     * 压缩包
     */
    ARCHIVE,

    /**
     * 文本
     */
    TEXT,

    /**
     * 其他类型
     */
    OTHER
}

/**
 * 浏览用远端文件条目
 *
 * @param name 文件名（最后一段）
 * @param relativePath 相对路径（不含服务器根地址）
 * @param sizeBytes 大小（字节）
 * @param modifiedAt 修改时间（毫秒时间戳）
 * @param kind 文件类型
 * @param isDirectory 是否为目录
 */
data class WebDavFileEntry(
    val name: String,
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val kind: WebDavFileKind,
    val isDirectory: Boolean
) {

    companion object {

        /**
         * 由 [WebDavFile] 转换为浏览条目
         *
         * @param file 源文件信息
         * @param directoryPrefix 当前目录相对路径（"" 表示根目录）
         */
        fun fromWebDavFile(file: WebDavFile, directoryPrefix: String): WebDavFileEntry {
            val segment = file.urlName.trim('/').substringAfterLast('/').ifBlank {
                file.displayName.trim('/').substringAfterLast('/')
            }
            val prefix = directoryPrefix.trim('/')
            val relativePath = if (prefix.isBlank()) segment else "$prefix/$segment"
            return WebDavFileEntry(
                name = segment,
                relativePath = relativePath,
                sizeBytes = file.size,
                modifiedAt = file.lastModify,
                kind = if (file.isDir) {
                    WebDavFileKind.DIRECTORY
                } else {
                    classifyKind(file.contentType, file.displayName)
                },
                isDirectory = file.isDir
            )
        }

        /**
         * 根据内容类型与文件名推断文件类型
         */
        private fun classifyKind(contentType: String, name: String): WebDavFileKind {
            val ct = contentType.lowercase()
            val extension = name.substringAfterLast('.', "").lowercase()
            return when {
                ct.startsWith("image/") -> WebDavFileKind.IMAGE
                ct.startsWith("video/") -> WebDavFileKind.VIDEO
                ct.startsWith("audio/") -> WebDavFileKind.AUDIO
                ct.startsWith("text/") -> WebDavFileKind.TEXT
                ct == "application/json" || ct == "application/xml" -> WebDavFileKind.TEXT
                ct.contains("zip") || ct.contains("compressed") -> WebDavFileKind.ARCHIVE
                extension in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "jar", "apk") ->
                    WebDavFileKind.ARCHIVE
                extension in setOf("txt", "md", "log", "json", "xml", "yml", "yaml", "csv", "ini", "conf") ->
                    WebDavFileKind.TEXT
                else -> WebDavFileKind.OTHER
            }
        }
    }
}

/**
 * 文件信息格式化工具
 */
object WebDavFileFormat {

    private val displayTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
    private val sizeFormatter = java.text.DecimalFormat("0.00")

    /**
     * 格式化修改时间为字符串（yyyy/MM/dd HH:mm）
     *
     * @param epochMillis 毫秒时间戳，小于等于 0 时返回"未知"
     */
    fun formatTime(epochMillis: Long): String {
        if (epochMillis <= 0L) return "未知"
        return LocalDateTime.ofInstant(
            Instant.ofEpochMilli(epochMillis),
            ZoneId.systemDefault()
        ).format(displayTimeFormatter)
    }

    /**
     * 格式化文件大小（B/KB/MB/GB/TB）
     */
    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${sizeFormatter.format(kb)} KB"
        val mb = kb / 1024.0
        if (mb < 1024) return "${sizeFormatter.format(mb)} MB"
        val gb = mb / 1024.0
        if (gb < 1024) return "${sizeFormatter.format(gb)} GB"
        return "${sizeFormatter.format(gb / 1024.0)} TB"
    }

    /**
     * 生成文件条目副标题摘要（时间 + 大小）
     */
    fun formatSummary(entry: WebDavFileEntry): String {
        val timeText = formatTime(entry.modifiedAt)
        return if (entry.isDirectory || entry.sizeBytes <= 0) {
            timeText
        } else {
            "$timeText ${formatSize(entry.sizeBytes)}"
        }
    }
}
