package github.xzynine.webdav

import android.annotation.SuppressLint
import cn.hutool.core.net.URLDecoder
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.intellij.lang.annotations.Language
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.MalformedURLException
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

@Suppress("unused", "MemberVisibilityCanBePrivate")
open class WebDav(
    val path: String,
    val authorization: Authorization
) {
    companion object {

        fun fromPath(path: String): WebDav {
            val authorization = Authorization("", "")
            return WebDav(path, authorization)
        }

        @SuppressLint("DateTimeFormatter")
        private val dateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME

        @Language("xml")
        private const val DIR =
            """<?xml version="1.0"?>
            <a:propfind xmlns:a="DAV:">
                <a:prop>
                    <a:displayname/>
                    <a:resourcetype/>
                    <a:getcontentlength/>
                    <a:creationdate/>
                    <a:getlastmodified/>
                    %s
                </a:prop>
            </a:propfind>"""

        @Language("xml")
        private const val EXISTS =
            """<?xml version="1.0"?>
            <propfind xmlns="DAV:">
               <prop>
                  <resourcetype />
               </prop>
            </propfind>"""

        private const val DEFAULT_CONTENT_TYPE = "application/octet-stream"

        /**
         * 共享底层客户端：所有 WebDav 实例通过 newBuilder 复用连接池与 TLS 会话，
         * 超时配置对全部实例一致。
         */
        private val baseClient: OkHttpClient = OkHttpClient.Builder().run {
            // 总时长不限（大词库/慢网络），但每个阶段都要有上限，避免弱网下无限挂起
            callTimeout(0, TimeUnit.SECONDS)
            connectTimeout(30, TimeUnit.SECONDS)
            readTimeout(120, TimeUnit.SECONDS)
            writeTimeout(120, TimeUnit.SECONDS)
            build()
        }
    }


    private val url: URL = URL(path)
    private val httpUrl: String? by lazy {
        val raw = url.toString()
            .replace("davs://", "https://")
            .replace("dav://", "http://")
        return@lazy kotlin.runCatching {
            raw.toHttpUrl().toString()
        }.getOrNull()
    }
    private val webDavClient: OkHttpClient by lazy {
        val authInterceptor = Interceptor { chain ->
            var request = chain.request()
            if (request.url.host.equals(host, true)) {
                request = request
                    .newBuilder()
                    .header(authorization.name, authorization.data)
                    .build()
            }
            chain.proceed(request)
        }
        baseClient.newBuilder()
            .addInterceptor(authInterceptor)
            .addNetworkInterceptor(authInterceptor)
            .build()
    }
    /**
     * 目标地址是否位于本地网络（局域网）。
     *
     * Android 17（SDK 37）起访问局域网默认被屏蔽，宿主应在访问前确认
     * 已通过 [LocalNetworkPermission] 获得「本地网络」权限。
     */
    val isLocalNetworkTarget: Boolean
        get() = LocalNetworkPermission.isLocalNetworkUrl(path)

    private val host: String?
        get() = url.host?.let {
            if (it.startsWith("[")) {
                it.substring(1, it.lastIndex)
            } else {
                it
            }
        }

    @Throws(WebDavException::class)
    suspend fun getWebDavFile(): WebDavFile? {
        // 404 视为远端文件不存在，返回 null 供调用方按远端缺失处理
        return propFindResponse(depth = 0, nullOn404 = true)?.let {
            parseBody(it).firstOrNull()
        }
    }

    @Throws(WebDavException::class)
    suspend fun listFiles(): List<WebDavFile> {
        propFindResponse()?.let { body ->
            return parseBody(body).filter {
                it.path != path
            }
        }
        return emptyList()
    }

    @Throws(WebDavException::class)
    private suspend fun propFindResponse(
        propsList: List<String> = emptyList(),
        depth: Int = 1,
        nullOn404: Boolean = false,
    ): String? {
        val requestProps = StringBuilder()
        for (p in propsList) {
            requestProps.append("<a:").append(p).append("/>\n")
        }
        val requestPropsStr: String = if (requestProps.toString().isEmpty()) {
            DIR.replace("%s", "")
        } else {
            String.format(DIR, requestProps.toString() + "\n")
        }
        val url = httpUrl ?: return null
        return webDavClient.newCallResponse {
            url(url)
            addHeader("Depth", depth.toString())
            val requestBody = requestPropsStr.toRequestBody("text/plain".toMediaType())
            method("PROPFIND", requestBody)
        }.use { response ->
            if (response.code == 404 && nullOn404) {
                null
            } else {
                checkResult(response)
                response.body.text()
            }
        }
    }

    private fun parseBody(s: String): List<WebDavFile> {
        val list = ArrayList<WebDavFile>()
        val document = kotlin.runCatching {
            Jsoup.parse(s, Parser.xmlParser())
        }.getOrElse {
            Jsoup.parse(s)
        }
        val ns = document.findNSPrefix("DAV:")
        val elements = document.findNS("response", ns)
        val urlStr = httpUrl ?: return list
        val baseUrl = NetworkUtils.getBaseUrl(urlStr)
        for (element in elements) {
            val href = element.findNS("href", ns)[0].text()
            val hrefDecode = URLDecoder.decodeForPath(href, Charsets.UTF_8)
            val fileName = hrefDecode.removeSuffix("/").substringAfterLast("/")
            val webDavFile: WebDav
            try {
                val urlName = hrefDecode.ifEmpty {
                    url.file.replace("/", "")
                }
                val displayName = element
                    .findNS("displayname", ns)
                    .firstOrNull()?.text()?.takeIf { it.isNotEmpty() }
                    ?.let { URLDecoder.decodeForPath(it, Charsets.UTF_8) } ?: fileName
                val contentType = element
                    .findNS("getcontenttype", ns)
                    .firstOrNull()?.text().orEmpty()
                val resourceType = element
                    .findNS("resourcetype", ns)
                    .firstOrNull()?.html()?.trim().orEmpty()
                val size = kotlin.runCatching {
                    element.findNS("getcontentlength", ns)
                        .firstOrNull()?.text()?.toLong() ?: 0
                }.getOrDefault(0)
                val lastModify: Long = kotlin.runCatching {
                    element.findNS("getlastmodified", ns)
                        .firstOrNull()?.text()?.let {
                            parseLastModifiedTime(it)
                        }
                }.getOrNull() ?: 0
                var fullURL = NetworkUtils.getAbsoluteURL(baseUrl, hrefDecode)
                if (WebDavFile.isDir(contentType, resourceType) && !fullURL.endsWith("/")) {
                    fullURL += "/"
                }
                webDavFile = WebDavFile(
                    fullURL,
                    authorization,
                    displayName = displayName,
                    urlName = urlName,
                    size = size,
                    contentType = contentType,
                    resourceType = resourceType,
                    lastModify = lastModify
                )
                list.add(webDavFile)
            } catch (e: MalformedURLException) {
                e.printStackTrace()
            }
        }
        return list
    }

    /**
     * 判断远端资源是否存在。
     * 404 或 ObjectNotFound 返回 false；网络异常与其他服务器错误直接抛出，由调用方分类处理。
     */
    @Throws(WebDavException::class)
    suspend fun existsOrThrow(): Boolean {
        val url = httpUrl ?: return false
        return webDavClient.newCallResponse {
            url(url)
            addHeader("Depth", "0")
            val requestBody = EXISTS.toRequestBody("application/xml".toMediaType())
            method("PROPFIND", requestBody)
        }.use { response ->
            when {
                response.isSuccessful -> true
                response.code == 404 -> false
                else -> {
                    val body = response.body.string()
                    val objectNotFound = Jsoup.parse(body.orEmpty())
                        .getElementsByTag("s:exception")
                        .firstOrNull()?.text() == "ObjectNotFound"
                    if (objectNotFound) false else throwExceptionFor(response, body)
                }
            }
        }
    }

    suspend fun exists(): Boolean {
        return kotlin.runCatching {
            existsOrThrow()
        }.onFailure {
            coroutineContext.ensureActive()
        }.getOrDefault(false)
    }

    /**
     * 解析 WebDAV 返回的最后修改时间。
     *
     * 优先按 RFC1123 解析并使用响应中的时区信息，
     * 失败后回退到旧实现，尽量保证兼容。
     */
    private fun parseLastModifiedTime(value: String): Long {
        return kotlin.runCatching {
            ZonedDateTime.parse(value, dateTimeFormatter).toInstant().toEpochMilli()
        }.getOrElse {
            LocalDateTime.parse(value, dateTimeFormatter)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        }
    }

    suspend fun check(): Boolean {
        return kotlin.runCatching {
            webDavClient.newCallResponse {
                url(url)
                addHeader("Depth", "0")
                val requestBody = EXISTS.toRequestBody("application/xml".toMediaType())
                method("PROPFIND", requestBody)
            }.use { it.code != 401 }
        }.onFailure {
            coroutineContext.ensureActive()
        }.getOrDefault(true)
    }

    suspend fun makeAsDir(): Boolean {
        val url = httpUrl ?: return false
        return kotlin.runCatching {
            if (!exists()) {
                webDavClient.newCallResponse {
                    url(url)
                    method("MKCOL", null)
                }.use {
                    checkResult(it)
                }
            }
        }.onFailure {
            coroutineContext.ensureActive()
        }.isSuccess
    }

    @Throws(WebDavException::class)
    suspend fun downloadTo(savedPath: String, replaceExisting: Boolean) {
        val file = File(savedPath)
        if (file.exists() && !replaceExisting) {
            return
        }
        downloadInputStream().use { byteStream ->
            FileOutputStream(file).use {
                byteStream.copyTo(it)
            }
        }
    }

    @Throws(WebDavException::class)
    suspend fun download(): ByteArray {
        return downloadInputStream().use {
            it.readBytes()
        }
    }

    @Throws(WebDavException::class)
    suspend fun upload(localPath: String, contentType: String = DEFAULT_CONTENT_TYPE) {
        upload(File(localPath), contentType)
    }

    @Throws(WebDavException::class)
    suspend fun upload(file: File, contentType: String = DEFAULT_CONTENT_TYPE) {
        kotlin.runCatching {
            withContext(IO) {
                if (!file.exists()) throw WebDavException("文件不存在")
                val fileBody = file.asRequestBody(contentType.toMediaType())
                val url = httpUrl ?: throw WebDavException("url不能为空")
                webDavClient.newCallResponse {
                    url(url)
                    put(fileBody)
                }.use {
                    checkResult(it)
                }
            }
        }.onFailure {
            coroutineContext.ensureActive()
            throw WebDavException("WebDav上传失败\n${it.localizedMessage}")
        }
    }

    @Throws(WebDavException::class)
    suspend fun upload(byteArray: ByteArray, contentType: String = DEFAULT_CONTENT_TYPE) {
        kotlin.runCatching {
            withContext(IO) {
                val fileBody = byteArray.toRequestBody(contentType.toMediaType())
                val url = httpUrl ?: throw WebDavException("url不能为空")
                webDavClient.newCallResponse {
                    url(url)
                    put(fileBody)
                }.use {
                    checkResult(it)
                }
            }
        }.onFailure {
            coroutineContext.ensureActive()
            throw WebDavException("WebDav上传失败\n${it.localizedMessage}")
        }
    }

    @Throws(WebDavException::class)
    suspend fun downloadInputStream(): InputStream {
        val url = httpUrl ?: throw WebDavException("WebDav下载出错\nurl为空")
        val byteStream = webDavClient.newCallResponse {
            url(url)
        }.apply {
            checkResult(this)
        }.body?.byteStream()
        return byteStream ?: throw WebDavException("WebDav下载出错\nNull Exception")
    }

    suspend fun delete(): Boolean {
        val url = httpUrl ?: return false
        return kotlin.runCatching {
            webDavClient.newCallResponse {
                url(url)
                method("DELETE", null)
            }.use {
                checkResult(it)
            }
        }.onFailure {
            coroutineContext.ensureActive()
        }.isSuccess
    }

    private fun checkResult(response: Response) {
        if (!response.isSuccessful) {
            throwExceptionFor(response, response.body.string())
        }
    }

    private fun throwExceptionFor(
        response: Response,
        body: String?,
    ): Nothing {
        if (response.code == 401) {
            val headers = response.headers("WWW-Authenticate")
            val supportBasicAuth = headers.any {
                it.startsWith("Basic", ignoreCase = true)
            }
            if (headers.isNotEmpty() && !supportBasicAuth) {
                // 预留：非 Basic 认证时的处理
            }
        }

        if (response.message.isNotBlank() || body.isNullOrBlank()) {
            throw WebDavException("${url}\n${response.code}:${response.message}")
        }
        val document = Jsoup.parse(body)
        val exception = document.getElementsByTag("s:exception").firstOrNull()?.text()
        val message = document.getElementsByTag("s:message").firstOrNull()?.text()
        if (exception == "ObjectNotFound") {
            throw ObjectNotFoundException(
                message ?: "$path doesn't exist. code:${response.code}"
            )
        }
        throw WebDavException(message ?: "未知错误 code:${response.code}")
    }
}
